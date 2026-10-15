package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.CameraChannel;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.WaybillVideoRecord;
import com.hiki.isup.model.WaybillVideoStatus;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.model.response.DownloadTaskResponse;
import com.hiki.isup.repository.WaybillVideoRepository;
import com.hiki.isup.util.TimeRange;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 出库包裹录像：按在线设备和表里的 {@code channel_code} 匹配全景 / 细节通道，
 * 下载后上传 OSS，回写 video_url1 / video_url2。
 */
@Service
public class WaybillVideoSyncService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WaybillVideoSyncService.class);

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 向录像机要的窗口：开始再提前这么多秒，结束再延后这么多秒。表里的时间不改 */
    private static final int DOWNLOAD_PADDING_SECONDS = 5;
    // 保留旧指纹版本，升级封装方式不能清零已有隔离次数或恢复授权。
    private static final String PACKING_WINDOW_VERSION = "packing-window-v4/";
    private static final String PACKING_SEGMENT_VERSION = "packing-segment-v1/";

    private final IsupProperties properties;
    private final DownloadService downloadService;
    private final SessionManager sessionManager;
    private final OssService ossService;
    private final WaybillVideoRepository repository;
    private final ChannelService channelService;
    private final DeviceRegistry deviceRegistry;
    private final RecordingRemuxer recordingRemuxer;
    private final LongSupplier nanoTime;
    private final ExecutorService workers;
    private final int workerCount;
    private volatile boolean closing;
    private volatile boolean retryUnavailable;
    private volatile RecordingRetryPolicy retryPolicy;
    private int failedScanOffset;
    private final Object controlLock = new Object();
    private volatile boolean running;
    private volatile LocalDateTime lastRoundStartedAt;
    private volatile LocalDateTime lastRoundFinishedAt;
    private volatile LocalDateTime lastSuccessAt;
    private volatile String lastError;
    private volatile QueueSample queueSample;
    private volatile RunStatistics currentRound;

    @Autowired
    public WaybillVideoSyncService(IsupProperties properties,
                                   DownloadService downloadService,
                                   SessionManager sessionManager,
                                   OssService ossService,
                                   WaybillVideoRepository repository,
                                   ChannelService channelService,
                                   DeviceRegistry deviceRegistry,
                                   RecordingRemuxer recordingRemuxer) {
        this(properties, downloadService, sessionManager, ossService, repository,
                channelService, deviceRegistry, recordingRemuxer, System::nanoTime);
    }

    WaybillVideoSyncService(IsupProperties properties,
                            DownloadService downloadService,
                            SessionManager sessionManager,
                            OssService ossService,
                            WaybillVideoRepository repository,
                            ChannelService channelService,
                            DeviceRegistry deviceRegistry,
                            RecordingRemuxer recordingRemuxer,
                            LongSupplier nanoTime) {
        this.properties = properties;
        this.downloadService = downloadService;
        this.sessionManager = sessionManager;
        this.ossService = ossService;
        this.repository = repository;
        this.channelService = channelService;
        this.deviceRegistry = deviceRegistry;
        this.recordingRemuxer = recordingRemuxer;
        this.nanoTime = nanoTime;
        this.workerCount = Math.max(4, properties.getWaybillVideo().getDownloadConcurrency() * 2);
        this.workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "waybill-video-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void syncOnce() {
        synchronized (controlLock) {
            if (closing || running) return;
            running = true;
            lastRoundStartedAt = LocalDateTime.now();
            currentRound = null;
        }
        try {
            doSyncOnce();
        } catch (RuntimeException e) {
            lastError = failureReason(e);
            throw e;
        } finally {
            lastRoundFinishedAt = LocalDateTime.now();
            running = false;
        }
    }

    private void doSyncOnce() {
        try {
            RecordingFailureEvidence.prune(Path.of(properties.getMediaDir()));
        } catch (IOException | RuntimeException e) {
            log.warn("录像失败样本清理暂不可用，继续原有同步流程：{}", e.getMessage());
        }
        if (sessionManager.recordingAdmissionBlocked()) {
            lastError = "录像资源仍占用或释放积压，暂停领取";
            log.warn(lastError);
            return;
        }
        recoverStaleProcessing();
        if (!ossService.isConfigured()) {
            lastError = "OSS 未配置";
            log.error("OSS 未配置（需要 endpoint / bucket / access-key-id / access-key-secret），跳过本轮出库录像同步");
            return;
        }
        String deviceId = onlineDeviceId();
        if (deviceId == null) {
            lastError = "在线设备不唯一或未在线";
            return;
        }
        retryUnavailable = false;
        List<WaybillVideoRecord> failedCandidates;
        try {
            RecordingStorageGuard.checkRoot(Path.of(properties.getMediaDir(), "download"));
            retryPolicy();
            failedCandidates = findFailedCandidates();
        } catch (IOException e) {
            failedScanOffset = 0;
            retryUnavailable = true;
            lastError = failureReason(e);
            log.error("录像磁盘或耐久重试台账不可用，本轮停止领取；请保留台账排查", e);
            return;
        }
        List<WaybillVideoRecord> firstBatch = samplePending();
        if (firstBatch.isEmpty() && failedCandidates.isEmpty()) {
            log.debug("出库录像队列为空，跳过设备通道查询");
            return;
        }
        List<DeviceChannel> deviceChannels;
        try {
            deviceChannels = channelService.listChannels(deviceId);
        } catch (RuntimeException e) {
            failedScanOffset = 0;
            lastError = failureReason(e);
            log.error("查询设备通道失败，本轮跳过出库录像：deviceId={}", deviceId, e);
            return;
        }
        try {
            if (recoverFailedForRetry(failedCandidates, deviceId, deviceChannels) > 0) {
                firstBatch = samplePending();
            }
        } catch (IOException e) {
            failedScanOffset = 0;
            retryUnavailable = true;
            lastError = failureReason(e);
            log.error("耐久重试台账不可用，本轮停止领取；请保留台账排查", e);
            return;
        }
        if (firstBatch.isEmpty()) return;
        int concurrency = effectiveDownloadConcurrency();
        Semaphore downloadSlots = new Semaphore(concurrency);
        RunStatistics statistics = new RunStatistics();
        currentRound = statistics;
        long startedAt = System.nanoTime();
        try {
            drainPending(downloadSlots, concurrency, runDeadlineNanos(), deviceId, deviceChannels,
                    firstBatch, statistics);
        } finally {
            statistics.logSummary(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
    }

    /**
     * 这台服务只挂一台录像机时用它下载。没有在线设备或同时在线多台时本轮不改行状态。
     */
    private String onlineDeviceId() {
        List<String> online = deviceRegistry.onlineDeviceIds();
        if (online.size() == 1) {
            return online.get(0);
        }
        if (online.isEmpty()) {
            log.error("没有在线设备，本轮跳过出库录像下载");
        } else {
            log.error("在线设备不唯一，本轮跳过出库录像下载：{}", online);
        }
        return null;
    }

    private int effectiveDownloadConcurrency() {
        int configured = properties.getWaybillVideo().getDownloadConcurrency();
        int maxSessions = properties.getSession().getMaxActiveSessions();
        if (maxSessions > 0 && configured > maxSessions) {
            log.warn("isup.waybill-video.download-concurrency={} 超过 max-active-sessions={}，已按后者限流",
                    configured, maxSessions);
            return maxSessions;
        }
        return configured;
    }

    private void recoverStaleProcessing() {
        int minutes = properties.getWaybillVideo().getStaleProcessingMinutes();
        int recovered = repository.recoverStaleProcessing(minutes);
        if (recovered > 0) {
            log.warn("回收卡住的 PROCESSING {} 条，已改回 PENDING：staleMinutes={}", recovered, minutes);
        }
    }

    private List<WaybillVideoRecord> findFailedCandidates() {
        int minutes = properties.getWaybillVideo().getFailedRetryAfterMinutes();
        if (minutes <= 0) return List.of();
        int limit = Math.min(100, properties.getWaybillVideo().getBatchSize());
        List<WaybillVideoRecord> candidates = repository.findFailedForRetry(minutes, limit, failedScanOffset);
        if (candidates.isEmpty()) {
            failedScanOffset = 0;
            return List.of();
        }
        return candidates;
    }

    private int recoverFailedForRetry(List<WaybillVideoRecord> candidates, String deviceId,
                                      List<DeviceChannel> deviceChannels) throws IOException {
        if (candidates.isEmpty()) return 0;
        int minutes = properties.getWaybillVideo().getFailedRetryAfterMinutes();
        List<String> eligible = new ArrayList<>();
        for (WaybillVideoRecord row : candidates) {
            row.setDeviceId(deviceId);
            if (hasAllVerifiedUrls(row, deviceChannels)
                    || retryPolicy().canAttempt(row.getId(), fingerprint(row, deviceChannels))) eligible.add(row.getId());
        }
        int recovered;
        try {
            recovered = repository.recoverFailedForRetry(minutes, eligible);
        } catch (RuntimeException uncertainUpdate) {
            // 连接异常不能证明 UPDATE 未生效；从最旧页重扫，不能跳过已经移前的记录。
            failedScanOffset = 0;
            throw uncertainUpdate;
        }
        // 只有实际完成检查后才推进。已回收行离开 FAILED 结果集，后续行左移，
        // 因此只跨过本页明确检查过且未回收的行；设备/台账早退不会预先推进游标。
        int retained = Math.max(0, candidates.size() - Math.max(0, recovered));
        failedScanOffset = failedScanOffset > Integer.MAX_VALUE - retained ? 0 : failedScanOffset + retained;
        if (recovered > 0) {
            log.info("收回 FAILED {} 条改回 PENDING，本轮接着处理：retryAfterMinutes={}", recovered, minutes);
        }
        return recovered;
    }

    private void drainPending(Semaphore downloadSlots, int rowConcurrency, long deadlineNanos,
                              String deviceId, List<DeviceChannel> deviceChannels,
                              List<WaybillVideoRecord> firstBatch, RunStatistics statistics) {
        List<CameraChannel> channels = resolvedChannels();
        int batchSize = properties.getWaybillVideo().getBatchSize();
        int window = rowConcurrency <= 1 ? 1 : Math.min(batchSize, workerCount);
        Deque<WaybillVideoRecord> candidates = new ArrayDeque<>(firstBatch);
        Set<String> submittedIds = new HashSet<>();
        Set<RowTask> inFlight = new LinkedHashSet<>();
        BlockingQueue<RowTask> completed = new LinkedBlockingQueue<>();
        boolean accepting = true;
        boolean interrupted = false;
        try {
            while (true) {
                if (accepting && (closing || retryUnavailable || sessionManager.recordingAdmissionBlocked() || deadlineReached(deadlineNanos))) {
                    accepting = false;
                    log.info("出库录像停止领取新任务，等待 {} 条在途记录完成", inFlight.size());
                }
                if (accepting) {
                    try {
                        while (inFlight.size() < window && !closing && !retryUnavailable
                                && !sessionManager.recordingAdmissionBlocked() && !deadlineReached(deadlineNanos)) {
                            if (candidates.isEmpty()) {
                                for (WaybillVideoRecord row : samplePending()) {
                                    if (!submittedIds.contains(row.getId())) {
                                        candidates.addLast(row);
                                    }
                                }
                                if (candidates.isEmpty()) {
                                    break;
                                }
                            }
                            WaybillVideoRecord row = candidates.removeFirst();
                            if (!submittedIds.add(row.getId())) {
                                continue;
                            }
                            long rowDeadline = RecordingDeadline.afterSeconds(properties.getWaybillVideo().getRowTimeoutSeconds());
                            RowTask task = new RowTask(() -> processRowWithStatistics(row, deviceId,
                                    deviceChannels, channels, downloadSlots, rowDeadline, statistics), completed, statistics);
                            workers.execute(task);
                            statistics.submitted();
                            inFlight.add(task);
                        }
                    } catch (RuntimeException e) {
                        accepting = false;
                        log.error("出库录像领取任务失败，等待已有任务收尾", e);
                    }
                }
                if (inFlight.isEmpty()) {
                    return;
                }
                try {
                    RowTask task = completed.take();
                    inFlight.remove(task);
                    try {
                        task.get();
                    } catch (CancellationException e) {
                        log.debug("出库录像任务已取消并完成收尾");
                    } catch (ExecutionException e) {
                        log.error("出库录像行任务异常", e.getCause());
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    accepting = false;
                    for (RowTask task : inFlight) {
                        task.cancel(true);
                    }
                    // 完成队列在工作线程实际退出后才通知，取消 Future 不等于数据库已收尾。
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private long runDeadlineNanos() {
        int maxRunSeconds = properties.getWaybillVideo().getMaxRunSeconds();
        if (maxRunSeconds <= 0) {
            return Long.MAX_VALUE;
        }
        long now = nanoTime.getAsLong();
        long budget = TimeUnit.SECONDS.toNanos(maxRunSeconds);
        return now > Long.MAX_VALUE - budget ? Long.MAX_VALUE : now + budget;
    }

    private boolean deadlineReached(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && nanoTime.getAsLong() >= deadlineNanos;
    }

    private void processRowWithStatistics(WaybillVideoRecord row, String deviceId,
                                          List<DeviceChannel> deviceChannels, List<CameraChannel> channels,
                                          Semaphore downloadSlots, long deadlineNanos, RunStatistics statistics) {
        RowOutcome outcome = RowOutcome.ERROR;
        try {
            outcome = processRow(row, deviceId, deviceChannels, channels, downloadSlots, deadlineNanos);
        } catch (RuntimeException e) {
            log.error("出库录像记录处理异常：id={}", row.getId(), e);
        } finally {
            statistics.completed(outcome);
        }
    }

    private RowOutcome processRow(WaybillVideoRecord row, String deviceId, List<DeviceChannel> deviceChannels,
                                  List<CameraChannel> channels, Semaphore downloadSlots, long deadlineNanos) {
        if (closing || sessionManager.recordingAdmissionBlocked() || Thread.currentThread().isInterrupted()) {
            return RowOutcome.SKIPPED;
        }
        if (!row.hasChannelCode()) {
            log.warn("跳过空 channel_code：id={}", row.getId());
            return RowOutcome.SKIPPED;
        }
        row.setDeviceId(deviceId);
        boolean attemptAllowed;
        try {
            RecordingDeadline.check(deadlineNanos);
            RecordingStorageGuard.checkRoot(Path.of(properties.getMediaDir(), "download"));
            // 两个已验证 URL 只需补数据库终态，不再消耗或受限于取流重试次数。
            attemptAllowed = hasAllVerifiedUrls(row, deviceChannels)
                    || retryPolicy().canAttempt(row.getId(), fingerprint(row, deviceChannels));
        } catch (IOException e) {
            retryUnavailable = true;
            lastError = failureReason(e);
            log.error("录像预算/磁盘/耐久重试台账不允许领取，保留 PENDING：id={}", row.getId(), e);
            return RowOutcome.SKIPPED;
        }
        if (!repository.claimSnapshot(row)) {
            log.info("记录已被抢占或状态已变，跳过：id={}", row.getId());
            return RowOutcome.SKIPPED;
        }
        List<PreparedChannel> prepared = new ArrayList<>();
        try {
            if (attemptAllowed && !hasAllVerifiedUrls(row, deviceChannels)) {
                attemptAllowed = retryPolicy().beginAttempt(row.getId(), fingerprint(row, deviceChannels));
            }
            if (!attemptAllowed) {
                log.warn("录像已隔离、次数用尽或仍在退避期，停止本次处理：id={}", row.getId());
                return finishRow(row, WaybillVideoStatus.FAILED);
            }
            boolean failed = false;
            if (row.getStartTime() == null || row.getEndTime() == null
                    || !row.getEndTime().isAfter(row.getStartTime())) {
                throw new RecordingValidationException("录像业务时间区间无效");
            }
            if (channels.stream().anyMatch(channel -> row.hasUrl(channel)
                    && !isVerifiedUrl(row, channel, deviceChannels))) {
                log.error("已有录像不属于当前校验版本或本单通道，保留原地址并拒绝混用：id={}", row.getId());
                throw new RecordingValidationException("已有录像地址不属于当前校验版本或本单通道");
            }
            ChannelStations.Match matched = ChannelStations.match(deviceChannels, row.getChannelCode());
            // 所有缺 URL 的通道必须先一起通过检查，避免下载完全景才发现细节缺失。
            for (CameraChannel channel : channels) {
                if (!row.hasUrl(channel) && matched.errorOf(channel) != null) {
                    throw new RecordingValidationException("出库录像通道匹配失败：column="
                            + channel.getUrlColumn() + ", " + matched.errorOf(channel));
                }
            }
            for (CameraChannel channel : channels) {
                if (Thread.currentThread().isInterrupted()) {
                    failed = true;
                    break;
                }
                if (row.hasUrl(channel)) {
                    log.info("通道已有 URL，跳过：id={}, channel={}", row.getId(), channel.getCode());
                    continue;
                }
                PreparedChannel result = prepareChannel(row, channel, matched.channelOf(channel), downloadSlots, deadlineNanos);
                if (result != null) {
                    prepared.add(result);
                } else {
                    failed = true;
                }
            }
            // 任一路时间轴/覆盖范围未验证通过时，两路均不得先上传或回写。
            if (failed || Thread.currentThread().isInterrupted()) {
                return finishRow(row, WaybillVideoStatus.FAILED);
            }
            for (PreparedChannel result : prepared) {
                if (Thread.currentThread().isInterrupted()) {
                    failed = true;
                    break;
                }
                try {
                    RecordingDeadline.check(deadlineNanos);
                    String url = ossService.upload(result.file, objectKey(row, result.channel, deviceChannels), deadlineNanos);
                    if (!persistWithoutInterrupt(() -> repository.updateUrlSnapshot(row, result.channel, url))) {
                        log.warn("回写 URL 未生效，记录已不是 PROCESSING：id={}, channel={}",
                                row.getId(), result.channel.getCode());
                        lastError = "订单快照已变化，停止旧任务回写：" + row.getId();
                        return RowOutcome.SKIPPED;
                    }
                    row.setUrl(result.channel, url);
                } catch (IOException | RuntimeException e) {
                    lastError = failureReason(e);
                    retryPolicy().failed(row.getId(), isPermanentFailure(e, row), lastError);
                    log.error("已验证录像上传或地址回写失败：id={}, channel={}",
                            row.getId(), result.channel.getCode(), e);
                    failed = true;
                    break;
                }
            }
            WaybillVideoStatus status = !failed && row.hasAllUrls(channels)
                    ? WaybillVideoStatus.SUCCESS : WaybillVideoStatus.FAILED;
            return finishRow(row, status);
        } catch (IOException | RuntimeException e) {
            log.error("出库录像处理或回写异常，尝试标记失败：id={}", row.getId(), e);
            try {
                lastError = failureReason(e);
                retryPolicy().failed(row.getId(), isPermanentFailure(e, row), lastError);
            } catch (IOException ledgerError) {
                retryUnavailable = true;
                log.error("录像失败分类无法持久化，停止领取：id={}", row.getId(), ledgerError);
            }
            try {
                return finishRow(row, WaybillVideoStatus.FAILED);
            } catch (RuntimeException writeError) {
                log.error("出库录像终态回写失败，记录等待过期恢复：id={}", row.getId(), writeError);
                return RowOutcome.ERROR;
            }
        } finally {
            for (PreparedChannel result : prepared) {
                releasePrepared(result.session);
            }
        }
    }

    private synchronized RecordingRetryPolicy retryPolicy() throws IOException {
        if (retryPolicy == null) {
            IsupProperties.WaybillVideo settings = properties.getWaybillVideo();
            retryPolicy = new RecordingRetryPolicy(Path.of(properties.getMediaDir(), "waybill-video-retries.properties"),
                    settings.getRetryMaxAttempts(), settings.getRetryMaxEntries(),
                    TimeUnit.MINUTES.toMillis(Math.max(1, settings.getFailedRetryAfterMinutes())),
                    TimeUnit.MINUTES.toMillis(settings.getRetryMaxBackoffMinutes()),
                    TimeUnit.DAYS.toMillis(settings.getRetryMaxAgeDays()), System::currentTimeMillis);
        }
        return retryPolicy;
    }

    private List<WaybillVideoRecord> samplePending() {
        int limit = properties.getWaybillVideo().getBatchSize();
        List<WaybillVideoRecord> rows = repository.findPending(limit);
        LocalDateTime oldest = rows.stream().map(WaybillVideoRecord::getEndTime)
                .filter(java.util.Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        queueSample = new QueueSample(true, limit, rows.size(), rows.size() >= limit, oldest, LocalDateTime.now());
        return rows;
    }

    /** 不触发数据库、SDK、迁移或重试；积压是最近一次有界抽样，不冒充总量。 */
    public java.util.Map<String, Object> diagnostics() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("enabled", properties.getWaybillVideo().isEnabled());
        result.put("running", running);
        result.put("resources", sessionManager.resourceSnapshot());
        result.put("admissionBlocked", sessionManager.recordingAdmissionBlocked() || retryUnavailable);
        result.put("queue", queueSample == null
                ? new QueueSample(false, properties.getWaybillVideo().getBatchSize(), 0, false, null, null) : queueSample);
        result.put("lastRoundStartedAt", lastRoundStartedAt);
        result.put("lastRoundFinishedAt", lastRoundFinishedAt);
        result.put("lastSuccessAt", lastSuccessAt);
        result.put("lastError", lastError);
        RunStatistics statistics = currentRound;
        result.put("round", statistics == null ? null : statistics.snapshot());
        RecordingRetryPolicy current = retryPolicy;
        java.util.Map<String, Object> retry = new java.util.LinkedHashMap<>();
        retry.put("initialized", current != null);
        retry.put("capacity", properties.getWaybillVideo().getRetryMaxEntries());
        try {
            if (current != null) retry.put("stats", current.stats());
            retry.put("summaryAvailable", current != null);
        } catch (IOException e) {
            retry.put("summaryAvailable", false);
            retry.put("error", "重试账本不可读，请保留原文件排查");
        }
        result.put("retry", retry);
        return result;
    }

    public Object inspectRetry(String id) throws IOException {
        RecordingRetryPolicy current = retryPolicy;
        Object snapshot = current == null ? null : current.inspect(id);
        if (snapshot == null) throw new com.hiki.isup.exception.NotFoundException("未找到已初始化的录像重试记录");
        return snapshot;
    }

    /** 只授予下一轮一次重试，版本核对和审计持久化一起完成；不直接改订单或调用设备。 */
    public Object releaseRetry(String id, String revision, String fingerprint, String reason) throws IOException {
        synchronized (controlLock) {
            if (running || closing) throw new RecoveryConflictException("同步正在执行或关闭，请等待本轮结束后再核对恢复");
            RecordingRetryPolicy current = retryPolicy;
            if (current == null || current.inspect(id) == null) {
                throw new com.hiki.isup.exception.NotFoundException("未找到已初始化的录像重试记录");
            }
            WaybillVideoRecord row = repository.findById(id);
            if (row == null || !WaybillVideoStatus.FAILED.name().equals(row.getStatus())) {
                throw new RecoveryConflictException("订单当前不是 FAILED，拒绝释放重试授权");
            }
            try {
                return current.release(id, revision, fingerprint, reason);
            } catch (IllegalArgumentException conflict) {
                throw new RecoveryConflictException(conflict.getMessage());
            }
        }
    }

    public record QueueSample(boolean sampled, int sampleLimit, int sampleSize, boolean capped,
                              LocalDateTime oldestEndTime, LocalDateTime sampledAt) { }

    public record RoundSnapshot(int submitted, int success, int failed, int skipped, int errors, int incomplete) { }

    public static final class RecoveryConflictException extends RuntimeException {
        public RecoveryConflictException(String message) { super(message); }
    }

    private static String failureReason(Throwable error) {
        String value = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private boolean isPermanentFailure(Throwable error, WaybillVideoRecord row) throws IOException {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof RecordingDurationShortageException) {
                return !recentAtFirstAttempt(row);
            }
            if (current instanceof RecordingCoverageException coverage) {
                boolean onlyTail = !coverage.availableStart().isAfter(coverage.requestedStart())
                        && coverage.availableEnd().isBefore(coverage.requestedEnd());
                return !onlyTail || !recentAtFirstAttempt(row);
            }
            if (current instanceof RecordingValidationException || current instanceof IllegalArgumentException) return true;
        }
        return false;
    }

    private boolean recentAtFirstAttempt(WaybillVideoRecord row) throws IOException {
        // 退避可长于录像落盘窗口；按首次尝试判定资格，重启后仍受原有次数和年龄上限约束。
        RecordingRetryPolicy.Snapshot attempt = retryPolicy == null ? null : retryPolicy.inspect(row.getId());
        LocalDateTime firstAttempt = attempt == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(attempt.firstAt()),
                        java.time.ZoneId.systemDefault());
        int window = properties.getWaybillVideo().getRecordingTailRetryWindowSeconds();
        return window > 0 && row.getEndTime() != null
                && !row.getEndTime().isAfter(firstAttempt)
                && !row.getEndTime().isBefore(firstAttempt.minusSeconds(window));
    }

    /** 服务自身回填的已验证 URL 不改变尝试次数；人工修正旧 URL 或业务输入后可重新评估。 */
    private String fingerprint(WaybillVideoRecord row, List<DeviceChannel> deviceChannels) {
        List<String> values = new ArrayList<>(List.of(String.valueOf(row.getDeviceId()),
                String.valueOf(row.getStartTime()), String.valueOf(row.getEndTime()),
                String.valueOf(row.getChannelCode()), resolvedChannels().toString(), PACKING_WINDOW_VERSION));
        ChannelStations.Match match = ChannelStations.match(deviceChannels, row.getChannelCode());
        for (CameraChannel channel : resolvedChannels()) {
            values.add(channel + ":" + match.channelOf(channel) + ":" + match.errorOf(channel));
            if (row.hasUrl(channel) && !isVerifiedUrl(row, channel, deviceChannels)) values.add(channel + ":" + row.urlOf(channel));
        }
        StringBuilder input = new StringBuilder();
        values.forEach(value -> input.append(value.length()).append(':').append(value));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String objectKey(WaybillVideoRecord row, CameraChannel channel, List<DeviceChannel> deviceChannels) {
        return buildRecordingObjectKey(properties.getOss().getPrefix(), row, channel,
                ChannelStations.match(deviceChannels, row.getChannelCode()).channelOf(channel));
    }

    private boolean hasAllVerifiedUrls(WaybillVideoRecord row, List<DeviceChannel> deviceChannels) {
        return resolvedChannels().stream().allMatch(channel -> row.hasUrl(channel)
                && isVerifiedUrl(row, channel, deviceChannels));
    }

    /** URL 同时绑定录像窗口和实际设备通道，业务时间/通道修改后不能沿用旧文件。 */
    static String buildRecordingObjectKey(String prefix, WaybillVideoRecord row, CameraChannel role, Integer physicalChannel) {
        return buildRecordingObjectKey(prefix, row, role, physicalChannel, PACKING_SEGMENT_VERSION);
    }

    private static String buildRecordingObjectKey(String prefix, WaybillVideoRecord row, CameraChannel role,
                                                  Integer physicalChannel, String version) {
        prefix = prefix == null ? "" : prefix.trim();
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        StringBuilder mediaInput = new StringBuilder();
        List.of(String.valueOf(row.getDeviceId()), String.valueOf(physicalChannel),
                String.valueOf(row.getStartTime()), String.valueOf(row.getEndTime()),
                String.valueOf(row.getChannelCode()), String.valueOf(role.getCode()))
                .forEach(value -> mediaInput.append(value.length()).append(':').append(value));
        String inputHash;
        try {
            inputHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(mediaInput.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return OssService.buildObjectKey(prefix + version + inputHash + "/", row.getId(),
                role.getCode(), row.getStartTime());
    }

    /** 兼容已验证 v4 成品；两种版本都须完整匹配业务输入、记录 ID 和通道。 */
    private boolean isVerifiedUrl(WaybillVideoRecord row, CameraChannel channel, List<DeviceChannel> deviceChannels) {
        try {
            java.net.URI uri = java.net.URI.create(row.urlOf(channel));
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (("/" + objectKey(row, channel, deviceChannels)).equals(uri.getRawPath())
                    || ("/" + buildRecordingObjectKey(properties.getOss().getPrefix(), row, channel,
                    ChannelStations.match(deviceChannels, row.getChannelCode()).channelOf(channel),
                    PACKING_WINDOW_VERSION)).equals(uri.getRawPath()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private void releasePrepared(MediaSession session) {
        session.releaseFiles();
        try {
            sessionManager.discardDownload(session.getSessionId());
        } catch (RuntimeException e) {
            log.warn("已完成本地录像清理失败：sessionId={}", session.getSessionId(), e);
        }
    }

    private RowOutcome finishRow(WaybillVideoRecord row, WaybillVideoStatus status) {
        if (!persistWithoutInterrupt(() -> repository.updateStatusSnapshot(row, status))) {
            log.warn("回写状态未生效，记录已不是 PROCESSING：id={}, status={}", row.getId(), status);
            return RowOutcome.SKIPPED;
        }
        boolean interrupted = Thread.interrupted();
        try {
            if (status == WaybillVideoStatus.SUCCESS) {
                retryPolicy().succeeded(row.getId());
                lastSuccessAt = LocalDateTime.now();
            }
            else if (status == WaybillVideoStatus.FAILED) retryPolicy().failed(row.getId(), false);
        } catch (IOException e) {
            retryUnavailable = true;
            log.error("录像终态已回写，但重试台账更新失败，停止后续领取：id={}", row.getId(), e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        log.info("出库录像处理结束：id={}, status={}, url1={}, url2={}",
                row.getId(), status, row.getVideoUrl1(), row.getVideoUrl2());
        return status == WaybillVideoStatus.SUCCESS ? RowOutcome.SUCCESS : RowOutcome.FAILED;
    }

    /** 取消信号保留给调用方；必要的数据库收尾不能带着中断标志借用连接。 */
    private static boolean persistWithoutInterrupt(BooleanSupplier write) {
        boolean interrupted = Thread.interrupted();
        try {
            return write.getAsBoolean();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private enum RowOutcome {
        SUCCESS, FAILED, SKIPPED, ERROR
    }

    /** 每轮独立统计；所有已提交任务实际退出后才汇总。 */
    private static final class RunStatistics {
        private int submitted;
        private int success;
        private int failed;
        private int skipped;
        private int errors;

        synchronized void submitted() {
            submitted++;
        }

        synchronized void completed(RowOutcome outcome) {
            switch (outcome) {
                case SUCCESS -> success++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case ERROR -> errors++;
            }
        }

        synchronized void logSummary(long elapsedMs) {
            int completed = success + failed + skipped + errors;
            log.info("出库录像本轮汇总：提交={}, 已结束={}, 成功={}, 失败={}, 跳过={}, 异常={}, 未完成={}, 耗时={}ms",
                    submitted, completed, success, failed, skipped, errors, submitted - completed, elapsedMs);
        }

        synchronized RoundSnapshot snapshot() {
            return new RoundSnapshot(submitted, success, failed, skipped, errors,
                    submitted - success - failed - skipped - errors);
        }
    }

    private PreparedChannel prepareChannel(WaybillVideoRecord row, CameraChannel channel, int deviceChannel,
                                             Semaphore downloadSlots, long deadlineNanos) throws IOException {
        MediaSession session = null;
        boolean acquired = false;
        boolean filesRetained = false;
        boolean prepared = false;
        try {
            if (!downloadSlots.tryAcquire(RecordingDeadline.remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                throw new java.net.SocketTimeoutException("等待录像下载槽位超过整行预算");
            }
            acquired = true;
            try {
                RecordingDeadline.check(deadlineNanos);
                session = downloadToFile(row, channel, deviceChannel, deadlineNanos);
                filesRetained = session.retainFiles();
                if (!filesRetained) {
                    throw new java.io.IOException("下载文件已进入清理流程，不能继续裁切上传");
                }
            } finally {
                downloadSlots.release();
                acquired = false;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("出库录像裁切前检测到中断");
            }
            RecordingDeadline.check(deadlineNanos);
            RecordingStorageGuard.check(Path.of(session.getRawFilePath()).getParent());
            Path file = recordingRemuxer.remux(session, row.getStartTime(), row.getEndTime(), deadlineNanos);
            RecordingDeadline.check(deadlineNanos);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("本单录像裁切后任务被中断");
            }
            prepared = true;
            return new PreparedChannel(channel, file, session);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopIncompleteDownload(session);
            InterruptedIOException interrupted = new InterruptedIOException("录像通道处理被中断");
            interrupted.initCause(e);
            throw interrupted;
        } catch (IOException | RuntimeException e) {
            if (filesRetained && session != null && session.getRawFilePath() != null) {
                try {
                    Path evidence = RecordingFailureEvidence.preserve(Path.of(properties.getMediaDir()),
                            Path.of(session.getRawFilePath()), row.getId(), deviceChannel,
                            row.getStartTime(), row.getEndTime(), e, deadlineNanos);
                    if (evidence != null) log.warn("已保留有界录像失败样本：id={}, channel={}, file={}",
                            row.getId(), deviceChannel, evidence);
                } catch (IOException | RuntimeException evidenceError) {
                    log.warn("录像失败样本未保存，保留原失败结果：id={}, channel={}, reason={}",
                            row.getId(), deviceChannel, evidenceError.getMessage());
                }
            }
            throw e;
        } finally {
            if (filesRetained && !prepared) {
                releasePrepared(session);
            }
            if (acquired) {
                downloadSlots.release();
            }
        }
    }

    private MediaSession downloadToFile(WaybillVideoRecord row, CameraChannel channel, int deviceChannel,
                                        long deadlineNanos) throws IOException {
        String deviceId = row.getDeviceId().trim();
        LocalDateTime downloadStart = paddedStart(row);
        LocalDateTime downloadEnd = paddedEnd(row);
        String start = WALL_CLOCK.format(downloadStart);
        String end = WALL_CLOCK.format(downloadEnd);
        TimeRange.validate(start, end);

        DownloadRequest request = new DownloadRequest();
        request.setDeviceId(deviceId);
        request.setChannel(deviceChannel);
        request.setStartTime(start);
        request.setEndTime(end);

        RecordingDeadline.check(deadlineNanos);
        DownloadTaskResponse response = downloadService.startPackingDownload(request, deadlineNanos);
        String taskId = response.getTaskId();
        MediaSession session;
        try {
            session = sessionManager.awaitFinished(taskId,
                    Math.min(timeoutMillis(row, downloadEnd), RecordingDeadline.remainingMillis(deadlineNanos)));
        } catch (IOException | RuntimeException e) {
            stopIncompleteDownload(sessionManager.get(taskId));
            throw e;
        }
        if (session == null) {
            throw IsupException.failed("等待下载", deviceId, deviceChannel, taskId, "会话已消失");
        }
        if (session.getStatus() != SessionStatus.COMPLETED) {
            if (!session.isFinished()) {
                stopIncompleteDownload(session);
            }
            throw IsupException.failed("下载出库录像", deviceId, deviceChannel, taskId,
                    session.getError() == null ? String.valueOf(session.getStatus()) : session.getError());
        }
        Path file = session.getRawFilePath() == null ? null : Path.of(session.getRawFilePath());
        try {
            RecordingDeadline.check(deadlineNanos);
            if (file == null || !Files.isRegularFile(file) || Files.size(file) <= 0) {
                throw IsupException.failed("下载出库录像", deviceId, deviceChannel, taskId,
                        "原始 PS 未生成或大小为 0: " + file);
            }
        } catch (java.io.IOException e) {
            sessionManager.discardDownload(taskId);
            throw IsupException.wrap("下载出库录像", deviceId, deviceChannel, taskId, e);
        } catch (RuntimeException e) {
            sessionManager.discardDownload(taskId);
            throw e;
        }
        return session;
    }

    private static LocalDateTime paddedStart(WaybillVideoRecord row) {
        return row.getStartTime().minusSeconds(DOWNLOAD_PADDING_SECONDS);
    }

    private LocalDateTime paddedEnd(WaybillVideoRecord row) throws IOException {
        RecordingRetryPolicy.Snapshot attempt = retryPolicy == null ? null : retryPolicy.inspect(row.getId());
        // 首轮取尾后 15 秒，第二轮 25 秒，第三轮起最多 35 秒，仍须验证真实覆盖范围。
        // 只扩大取流窗口，实际裁切和对象键继续使用原始打包起止时间。
        int extra = attempt == null ? 10 : Math.min(3, Math.max(1, attempt.attempts())) * 10;
        LocalDateTime base = row.getEndTime().plusSeconds(DOWNLOAD_PADDING_SECONDS);
        LocalDateTime limit = paddedStart(row).plusHours(TimeRange.MAX_RANGE_HOURS);
        // 原 start-5/end+5 已超范围的请求继续拒绝；合法请求不能因额外扩尾变成非法。
        if (!base.isBefore(limit)) return base;
        LocalDateTime extended = base.plusSeconds(extra);
        return extended.isAfter(limit) ? limit : extended;
    }

    private long timeoutMillis(WaybillVideoRecord row, LocalDateTime downloadEnd) {
        long videoMs = Duration.between(paddedStart(row), downloadEnd).toMillis();
        long startTimeoutMs = properties.getSession().getStartTimeoutSec() * 1000L;
        long maxMs = properties.getSession().getDownloadMaxSec() * 1000L;
        long timeoutMs = Math.min(videoMs + startTimeoutMs + 30_000L, maxMs);
        return Math.max(timeoutMs, startTimeoutMs);
    }

    private void stopIncompleteDownload(MediaSession session) {
        if (session != null && !session.isFinished()) {
            sessionManager.stopAsync(session, SessionStatus.FAILED, "出库录像下载超时或任务被中断");
        }
    }

    List<CameraChannel> resolvedChannels() {
        List<Integer> codes = properties.getWaybillVideo().getChannels();
        if (codes == null || codes.isEmpty()) {
            return List.of(CameraChannel.PANORAMA, CameraChannel.DETAIL);
        }
        Set<CameraChannel> unique = new LinkedHashSet<>();
        for (Integer code : codes) {
            if (code == null) {
                throw new IllegalArgumentException("isup.waybill-video.channels 不能包含空值");
            }
            unique.add(CameraChannel.fromCode(code));
        }
        return unique.isEmpty()
                ? List.of(CameraChannel.PANORAMA, CameraChannel.DETAIL)
                : new ArrayList<>(unique);
    }

    @PreDestroy
    @Override
    public void close() {
        closing = true;
        workers.shutdown();
        boolean interrupted = false;
        boolean terminated = false;
        try {
            try {
                terminated = workers.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (!terminated) {
                stopWorkers();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                long remaining;
                while (!terminated && (remaining = deadline - System.nanoTime()) > 0) {
                    try {
                        terminated = workers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (!terminated) {
                    log.warn("出库录像工作线程未在关闭期限内退出，未完成记录等待过期恢复");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopWorkers() {
        for (Runnable queued : workers.shutdownNow()) {
            if (queued instanceof RowTask task) {
                task.cancel(false);
                task.run();
            }
        }
    }

    /** Future 取消只发出中断；实际执行退出后才通知调度器释放运行保护。 */
    private static final class RowTask extends FutureTask<Void> {
        private final BlockingQueue<RowTask> completed;
        private final RunStatistics statistics;
        private final AtomicBoolean started;
        private final AtomicBoolean dispatched = new AtomicBoolean();

        RowTask(Runnable action, BlockingQueue<RowTask> completed, RunStatistics statistics) {
            this(action, completed, statistics, new AtomicBoolean());
        }

        private RowTask(Runnable action, BlockingQueue<RowTask> completed,
                        RunStatistics statistics, AtomicBoolean started) {
            super(() -> {
                started.set(true);
                action.run();
                return null;
            });
            this.completed = completed;
            this.statistics = statistics;
            this.started = started;
        }

        @Override
        public void run() {
            if (!dispatched.compareAndSet(false, true)) {
                return;
            }
            try {
                super.run();
            } finally {
                if (!started.get()) {
                    statistics.completed(RowOutcome.SKIPPED);
                }
                completed.add(this);
            }
        }
    }

    private static final class PreparedChannel {
        private final CameraChannel channel;
        private final Path file;
        private final MediaSession session;

        private PreparedChannel(CameraChannel channel, Path file, MediaSession session) {
            this.channel = channel;
            this.file = file;
            this.session = session;
        }
    }
}
