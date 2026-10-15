package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.NotFoundException;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.sdk.NativeSdkLoader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话管理器：预览 / 回放 / 下载统一用一个 ConcurrentHashMap 管理。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>sessionId 与 ISUP SDK sessionId 的双向关联</li>
 *   <li>独立停止本地写入与 ISUP；两者均确认释放后归还并发名额</li>
 *   <li>防止同一个 session 被重复 stop</li>
 *   <li>超时看护：设备回连超时、码流中断、下载超时</li>
 * </ul>
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final NativeSdkLoader sdkLoader;
    private final IsupProperties properties;

    /** 停机未确认释放的 manager 持续持有 JNA callback，不能随 Spring context 一起被 GC。 */
    private static final Set<SessionManager> UNRELEASED_MANAGERS = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private boolean closing;
    /** 与会话发布共用生命周期锁；每设备只保留一个截止代次，拒绝已取到旧快照的迟到请求。 */
    private final Map<String, Long> expiredConnectionGenerations = new HashMap<>();

    /** sessionId -> 会话 */
    private final ConcurrentHashMap<String, MediaSession> sessions = new ConcurrentHashMap<>();

    /** 类型 + ISUP 会话 ID -> 本服务 sessionId */
    private final ConcurrentHashMap<String, String> isupSessionIndex = new ConcurrentHashMap<>();

    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "isup-session-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** native Stop 可能无限阻塞，不能与本地 writer/进程收尾共用工作线程。 */
    private final ExecutorService nativeStopExecutor = cleanupExecutor("isup-session-native-stop");
    private final ExecutorService stopExecutor = cleanupExecutor("isup-session-local-stop");

    /** 每会话只保留一份清理状态；队列拒绝时由 watchdog 重试，不创建无界溢出队列。 */
    private final ConcurrentHashMap<String, PendingCleanup> pendingCleanup = new ConcurrentHashMap<>();

    private static ExecutorService cleanupExecutor(String name) {
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), r -> {
                    Thread thread = new Thread(r, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 下载任务完成后等待 ffmpeg 收尾的时间（秒） */
    private static final int FFMPEG_GRACE_SECONDS = 30;
    /** 下载写入中断时的失败原因。出库任务据此标 FAILED，由下一轮收回重试 */
    static final String INCOMPLETE_DOWNLOAD = "录像写入中断，录像不完整，已中止以便重试";
    /** 超过 download-max-sec 强制结束。半成品不能标完成 */
    static final String DOWNLOAD_TIMEOUT = "下载超时，强制结束";
    static final String DOWNLOAD_MUX_FAILED = "录像写入或转封装未正常完成，录像不完整";
    /** 终态会话保留时间（毫秒），便于客户端查询最后状态 */
    private static final long FINISHED_RETAIN_MILLIS = Duration.ofMinutes(10).toMillis();
    /** 启动时可安全清理的媒体子目录。会话表只在内存里，重启后这些目录都无人认领。 */
    private static final List<String> TEMP_MEDIA_SUBDIRS = List.of("preview", "playback", "download");

    public SessionManager(NativeSdkLoader sdkLoader, IsupProperties properties) {
        this.sdkLoader = sdkLoader;
        this.properties = properties;
        watchdog.scheduleWithFixedDelay(this::sweep, 15, 15, TimeUnit.SECONDS);
    }

    // ==================== 创建与查询 ====================

    public MediaSession create(SessionType type, String deviceId, int channel) {
        return create(type, deviceId, channel, null);
    }

    public MediaSession create(SessionType type, DeviceInfo connection, int channel) {
        return create(type, connection.getDeviceId(), channel, connection);
    }

    private MediaSession create(SessionType type, String deviceId, int channel, DeviceInfo connection) {
        synchronized (lifecycleMonitor) {
            if (closing) {
                throw new IllegalStateException("ISUP 服务正在关闭，不能创建新的媒体会话");
            }
            if (connection != null && connection.getConnectionGeneration()
                    <= expiredConnectionGenerations.getOrDefault(deviceId, 0L)) {
                throw new IllegalStateException("设备连接已过期，请等待设备重新注册后重试");
            }
            reserveSlot();
            String sessionId = UUID.randomUUID().toString();
            MediaSession session = new MediaSession(sessionId, type, deviceId, channel, connection);
            sessions.put(sessionId, session);
            return session;
        }
    }

    /**
     * 占用一个并发会话名额。每路会话背后都是一个 ffmpeg 进程，路数不受控时
     * 转码 CPU 与堆内存都会被吃干，因此在入口处限流。0 表示不限制。
     */
    private void reserveSlot() {
        int max = properties.getSession().getMaxActiveSessions();
        if (max <= 0) {
            activeSlots.incrementAndGet();
            return;
        }
        while (true) {
            int current = activeSlots.get();
            if (current >= max) {
                throw new IllegalStateException("并发会话数已达上限 " + max
                        + "，请先停止不需要的预览/回放/下载，或调大 isup.session.max-active-sessions");
            }
            if (activeSlots.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    private void releaseSlot() {
        activeSlots.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public MediaSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 等待下载/回放会话进入终态。超时后会主动将任务标为 FAILED。
     */
    public MediaSession awaitFinished(String sessionId, long timeoutMs) {
        MediaSession session = get(sessionId);
        if (session == null) {
            return null;
        }
        if (!session.isFinished()) {
            try {
                if (!session.awaitTerminal(timeoutMs)) {
                    stop(session, SessionStatus.FAILED, "等待下载超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop(session, SessionStatus.FAILED, "等待下载被中断");
            }
            session = get(sessionId);
        }
        return session;
    }

    public MediaSession require(String sessionId) {
        MediaSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NotFoundException("会话不存在或已清理：sessionId=" + sessionId);
        }
        return session;
    }

    /**
     * OSS 上传成功后立刻丢掉本地下载：删 MP4 目录并从会话表移除。
     * 下载已经结束，不再走 stop（避免再次调 SDK / 占停止线程）。
     */
    public void discardDownload(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        MediaSession session = sessions.get(sessionId);
        if (session == null || session.getType() != SessionType.DOWNLOAD
                || !session.isFinished() || !session.beginFileCleanup()) {
            return;
        }
        if (pendingCleanup.containsKey(sessionId)) {
            // 记住调用方已交还文件，但不能从会话表移除仍承载 native callback 的对象。
            return;
        }
        String dir = session.getOutputDir();
        try {
            if (dir != null && !dir.isBlank()) {
                deleteRecursively(Path.of(dir));
                log.info("已删除本地下载：sessionId={}, dir={}", sessionId, dir);
            }
            dropIsupIndex(session);
            sessions.remove(sessionId, session);
        } catch (Exception e) {
            // 保留终态会话，让看护任务继续重试；不能遗忘占磁盘的原始 PS / MP4。
            log.warn("删除本地下载失败，保留会话等待重试：sessionId={}, dir={}", sessionId, dir, e);
        }
    }

    /**
     * 建立 ISUP SDK 会话 ID 与本服务 sessionId 的关联。
     */
    public void registerIsupSession(MediaSession session, int isupSessionId) {
        if (isupSessionId < 0) {
            return;
        }
        session.setIsupSessionId(isupSessionId);
        isupSessionIndex.put(indexKey(session.getType(), isupSessionId), session.getSessionId());
    }

    /**
     * 根据设备回连回调中的 ISUP 会话 ID 反查本服务会话。
     */
    public MediaSession findByIsupSessionId(SessionType type, int isupSessionId) {
        if (isupSessionId < 0) {
            return null;
        }
        String sessionId = isupSessionIndex.get(indexKey(type, isupSessionId));
        MediaSession session = sessionId == null ? null : sessions.get(sessionId);
        // 精确命中旧终态也必须返回，由 newlink 拒绝；不能误兜底到下一单。
        return session;
    }

    /**
     * 回放 / 下载的 newlink 回调兜底匹配：当 SDK 返回的 lSessionID 无效（如 -1）时，
     * 按 deviceId + channel 找到唯一的待接流会话。
     */
    public MediaSession findPendingByDeviceChannel(SessionType type, String deviceId, int channel) {
        return findPendingByDeviceChannel(List.of(type), deviceId, channel);
    }

    /** 回放与下载共用监听器，设备未提供可靠 ID 时必须跨两种类型保证唯一。 */
    public MediaSession findPendingPlaybackByDeviceChannel(String deviceId, int channel) {
        return findPendingByDeviceChannel(List.of(SessionType.PLAYBACK, SessionType.DOWNLOAD), deviceId, channel);
    }

    private MediaSession findPendingByDeviceChannel(List<SessionType> types, String deviceId, int channel) {
        List<MediaSession> candidates = new ArrayList<>();
        for (MediaSession s : sessions.values()) {
            if (types.contains(s.getType())
                    && !s.isStopping()
                    && s.getStatus() == SessionStatus.STARTING
                    && deviceId.equals(s.getDeviceId())
                    && s.getChannel() == channel) {
                candidates.add(s);
            }
        }
        if (candidates.size() != 1) {
            return null;
        }
        // 取最近创建的一个
        candidates.sort(Comparator.comparing(MediaSession::getCreatedAt).reversed());
        return candidates.get(0);
    }

    public List<MediaSession> list() {
        List<MediaSession> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparing(MediaSession::getCreatedAt).reversed());
        return list;
    }

    public long activeCount() {
        return sessions.values().stream()
                .filter(s -> !s.isFinished())
                .count();
    }

    // ==================== 停止与清理 ====================

    /**
     * 发布停止终态并异步释放资源。
     *
     * @param targetStatus 停止后的目标状态（STOPPED / FAILED / COMPLETED）
     */
    public void stop(MediaSession session, SessionStatus targetStatus, String reason) {
        if (session == null) {
            return;
        }
        PendingCleanup cleanup;
        synchronized (session) {
            if (session.isFinished()) {
                return;
            }
            cleanup = beginCleanup(session, false, reason);
            if (reason != null) {
                session.setError(reason);
            }
            // 终态与资源释放分开：调用方不能等待卡住的 native Stop。
            session.setStatus(targetStatus);
        }
        requestCleanup(cleanup, false);
    }

    /** SDK 回调线程只发布状态和提交任务，绝不重入 native Stop。 */
    public void stopAsync(MediaSession session, SessionStatus targetStatus, String reason) {
        stop(session, targetStatus, reason);
    }

    public void finishDownloadAsync(MediaSession session, String reason) {
        if (session == null || session.getType() != SessionType.DOWNLOAD) {
            return;
        }
        PendingCleanup cleanup;
        synchronized (session) {
            if (session.isStopping() || session.isFinished()) {
                return;
            }
            cleanup = beginCleanup(session, true, reason);
        }
        requestCleanup(cleanup, false);
    }

    public void stopByDeviceAsync(String deviceId) {
        stopByDevice(deviceId);
    }

    /** 清理截至本次掉线的各代会话，包含宽限期内保留的旧会话，排除之后重连的新会话。 */
    public void stopByConnectionAsync(DeviceInfo connection) {
        if (connection == null) {
            return;
        }
        List<MediaSession> expiredSessions = new ArrayList<>();
        synchronized (lifecycleMonitor) {
            long cutoff = expiredConnectionGenerations.merge(connection.getDeviceId(),
                    connection.getConnectionGeneration(), Math::max);
            for (MediaSession session : sessions.values()) {
                DeviceInfo sessionConnection = session.getDeviceConnection();
                if (connection.getDeviceId().equals(session.getDeviceId()) && sessionConnection != null
                        && sessionConnection.getConnectionGeneration() <= cutoff
                        && !session.isFinished()) {
                    expiredSessions.add(session);
                }
            }
        }
        // 停止可能取得 session 锁并调度 native/进程清理，必须离开全局生命周期锁后执行。
        for (MediaSession session : expiredSessions) {
            stop(session, SessionStatus.FAILED, "设备下线，会话被强制结束");
        }
    }

    /** 必须持有 session 锁，确保重复取消与正常完成共用同一清理任务。 */
    private PendingCleanup beginCleanup(MediaSession session, boolean download, String reason) {
        PendingCleanup existing = pendingCleanup.get(session.getSessionId());
        if (existing != null) {
            return existing;
        }
        session.markStopping();
        PendingCleanup cleanup = new PendingCleanup(session, download, reason);
        pendingCleanup.put(session.getSessionId(), cleanup);
        // 输入关闭是非阻塞请求，本地收尾不必等待 native Stop。
        requestFfmpegStop(session);
        return cleanup;
    }

    private void requestCleanup(PendingCleanup cleanup, boolean localSynchronous) {
        if (cleanup.session.hasNativeOperations()) {
            return;
        }
        requestNativeStop(cleanup);
        if (cleanup.localStopped || !cleanup.localRunning.compareAndSet(false, true)) {
            return;
        }
        Runnable localTask = () -> {
            try {
                finishLocalCleanup(cleanup);
            } catch (Exception e) {
                log.error("本地会话收尾失败，保留资源等待重试：sessionId={}",
                        cleanup.session.getSessionId(), e);
            } finally {
                cleanup.localRunning.set(false);
                completeCleanup(cleanup);
            }
        };
        if (localSynchronous) {
            localTask.run();
            return;
        }
        // 没有本地进程/文件时无需占工作线程，保持纯状态会话的即时释放语义。
        if (cleanup.session.getFfmpegProcess() == null && cleanup.session.getOutputDir() == null
                && !cleanup.download) {
            localTask.run();
            return;
        }
        try {
            stopExecutor.execute(localTask);
        } catch (RejectedExecutionException e) {
            cleanup.localRunning.set(false);
            log.warn("本地停止队列暂不可用，已关闭输入并保留清理任务：sessionId={}",
                    cleanup.session.getSessionId());
        }
    }

    /** native Start/注册返回后，归还租约并收回可能迟到创建的资源。 */
    public void endNativeOperation(MediaSession session) {
        if (!session.endNativeOperation()) {
            return;
        }
        PendingCleanup cleanup = pendingCleanup.get(session.getSessionId());
        if (cleanup != null) {
            requestFfmpegStop(session);
            requestCleanup(cleanup, false);
        }
    }

    private void requestNativeStop(PendingCleanup cleanup) {
        if (cleanup.nativeStopped || !cleanup.nativeRunning.compareAndSet(false, true)) {
            return;
        }
        MediaSession session = cleanup.session;
        if (!sdkLoader.isLoaded()
                || (session.getLinkHandle() < 0 && (session.getLUserId() < 0 || session.getIsupSessionId() < 0))) {
            cleanup.nativeStopped = true;
            cleanup.nativeRunning.set(false);
            completeCleanup(cleanup);
            return;
        }
        try {
            nativeStopExecutor.execute(() -> {
                try {
                    cleanup.nativeStopped = stopIsup(cleanup);
                } finally {
                    cleanup.nativeRunning.set(false);
                    completeCleanup(cleanup);
                }
            });
        } catch (RejectedExecutionException e) {
            cleanup.nativeRunning.set(false);
            log.warn("native 停止队列暂不可用，保留 callback 与会话名额等待重试：sessionId={}",
                    session.getSessionId());
        }
    }

    private void finishLocalCleanup(PendingCleanup cleanup) {
        MediaSession session = cleanup.session;
        FfmpegProcess process = session.getFfmpegProcess();
        boolean muxSucceeded = awaitFfmpegExit(session);
        boolean writeFailed = process != null && process.isWriteFailed();
        boolean localStopped = session.getFfmpegProcess() == null;
        if (cleanup.download) {
            String filePath = session.getRawFilePath();
            if (filePath == null || filePath.isBlank()) {
                filePath = session.getFilePath();
            }
            long size = filePath == null ? 0 : sizeOf(Path.of(filePath));
            synchronized (session) {
                if (!session.isFinished()) {
                    if (localStopped && downloadComplete(size, writeFailed, muxSucceeded, cleanup.reason)) {
                        session.setFileSize(size);
                        session.setStatus(SessionStatus.COMPLETED);
                        log.info("录像下载完成：sessionId={}, file={}, size={} bytes",
                                session.getSessionId(), filePath, size);
                    } else {
                        session.setError(downloadFailureReason(writeFailed, muxSucceeded, cleanup.reason));
                        session.setStatus(SessionStatus.FAILED);
                    }
                }
            }
        }
        // 输入关闭且本地 writer 退出后才允许删除；取消优先于迟到的下载成功结果。
        if (localStopped) {
            cleanupFiles(session, session.getStatus());
        }
        cleanup.localStopped = localStopped;
    }

    private void completeCleanup(PendingCleanup cleanup) {
        if (cleanup.session.hasNativeOperations() || !cleanup.nativeStopped || !cleanup.localStopped
                || !cleanup.released.compareAndSet(false, true)) {
            return;
        }
        MediaSession session = cleanup.session;
        // native Stop 必须确认成功；超时、异常、队列拒绝均不能丢 JNA callback 强引用。
        releaseSlot();
        session.runReleaseHook();
        session.setDataCallback(null);
        pendingCleanup.remove(session.getSessionId(), cleanup);
        synchronized (lifecycleMonitor) {
            lifecycleMonitor.notifyAll();
        }
    }

    private static final class PendingCleanup {
        private final long startedNanos = System.nanoTime();
        private final MediaSession session;
        private final boolean download;
        private final String reason;
        private final AtomicBoolean nativeRunning = new AtomicBoolean();
        private final AtomicBoolean localRunning = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile boolean nativeStopped;
        private volatile boolean localStopped;
        private boolean streamStopped;
        private boolean cmsStopped;

        private PendingCleanup(MediaSession session, boolean download, String reason) {
            this.session = session;
            this.download = download;
            this.reason = reason;
        }
    }
    private void dropIsupIndex(MediaSession session) {
        // SDK 启动返回 ID 与设备回连实际 ID 可能曾不同，移除会话时清掉全部别名。
        isupSessionIndex.entrySet().removeIf(entry -> session.getSessionId().equals(entry.getValue()));
    }

    private void destroyFfmpegNow(MediaSession session) {
        FfmpegProcess process = session.getFfmpegProcess();
        if (process != null) {
            process.destroy();
            if (!process.isAlive()) {
                session.setFfmpegProcess(null);
            }
        }
    }

    /**
     * 设备下线时，停止该设备的全部会话。
     */
    public void stopByDevice(String deviceId) {
        if (deviceId == null) {
            return;
        }
        for (MediaSession session : sessions.values()) {
            if (deviceId.equals(session.getDeviceId()) && !session.isFinished()) {
                stop(session, SessionStatus.FAILED, "设备下线，会话被强制结束");
            }
        }
    }

    private boolean stopIsup(PendingCleanup cleanup) {
        MediaSession session = cleanup.session;
        int lUserId = session.getLUserId();
        int isupSessionId = session.getIsupSessionId();
        int linkHandle = session.getLinkHandle();
        try {
            if (!cleanup.streamStopped) {
                if (linkHandle < 0) {
                    cleanup.streamStopped = true;
                } else {
                    HCISUPStream stream = sdkLoader.stream();
                    cleanup.streamStopped = session.getType() == SessionType.PREVIEW
                            ? stream.NET_ESTREAM_StopPreview(linkHandle)
                            : stream.NET_ESTREAM_StopPlayBack(linkHandle);
                }
            }
            if (!cleanup.cmsStopped) {
                if (lUserId < 0 || isupSessionId < 0) {
                    cleanup.cmsStopped = true;
                } else {
                    HCISUPCMS cms = sdkLoader.cms();
                    cleanup.cmsStopped = session.getType() == SessionType.PREVIEW
                            ? cms.NET_ECMS_StopGetRealStream(lUserId, isupSessionId)
                            : cms.NET_ECMS_StopPlayBack(lUserId, isupSessionId);
                }
            }
            if (!cleanup.streamStopped || !cleanup.cmsStopped) {
                log.warn("native Stop 未确认成功，保留资源并等待重试：sessionId={}, streamStopped={}, cmsStopped={}",
                        session.getSessionId(), cleanup.streamStopped, cleanup.cmsStopped);
            }
            return cleanup.streamStopped && cleanup.cmsStopped;
        } catch (Exception e) {
            log.error("停止 ISUP 会话异常，保留 callback 与名额：deviceId={}, channel={}, sessionId={}",
                    session.getDeviceId(), session.getChannel(), session.getSessionId(), e);
            return false;
        }
    }
    /**
     * 请求 ffmpeg 结束输入。下载场景必须走这一步，ffmpeg 才能写出完整 MP4。
     */
    private void requestFfmpegStop(MediaSession session) {
        FfmpegProcess process = session.getFfmpegProcess();
        if (process != null) {
            process.closeStdin();
        }
    }

    /**
     * 等待 ffmpeg 退出并销毁进程。耗时较长，只在后台线程中调用。
     */
    private boolean awaitFfmpegExit(MediaSession session) {
        FfmpegProcess process = session.getFfmpegProcess();
        if (process == null) {
            return false;
        }
        try {
            Integer exit = process.waitFor(FFMPEG_GRACE_SECONDS);
            if (exit != null && exit != 0) {
                log.warn("ffmpeg 退出码非 0，sessionId={}, exitCode={}", session.getSessionId(), exit);
            }
            if (exit == null) {
                log.warn("ffmpeg 未在收尾期限内退出，sessionId={}", session.getSessionId());
            }
            return exit != null && exit == 0;
        } catch (Exception e) {
            log.warn("等待 ffmpeg 退出异常，sessionId={}", session.getSessionId(), e);
            return false;
        } finally {
            process.destroy();
            if (!process.isAlive()) {
                session.setFfmpegProcess(null);
            }
        }
    }

    /**
     * 清理文件：
     * <ul>
     *   <li>下载成功：保留 MP4</li>
     *   <li>下载失败/停止：删除临时文件</li>
     *   <li>预览/回放：删除 HLS 切片目录</li>
     * </ul>
     */
    private void cleanupFiles(MediaSession session, SessionStatus targetStatus) {
        String dir = session.getOutputDir();
        if (dir == null) {
            return;
        }
        boolean keep = session.getType() == SessionType.DOWNLOAD && targetStatus == SessionStatus.COMPLETED;
        if (keep) {
            log.info("保留下载文件：sessionId={}, dir={}", session.getSessionId(), dir);
            return;
        }
        try {
            deleteRecursively(Path.of(dir));
            log.info("已清理媒体目录：sessionId={}, dir={}", session.getSessionId(), dir);
        } catch (Exception e) {
            log.warn("清理媒体目录失败：sessionId={}, dir={}", session.getSessionId(), dir, e);
        }
    }

    /**
     * 递归删除目录。采用后序遍历边走边删，避免把整棵目录树读进内存再排序。
     */
    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 清理上一进程遗留的媒体目录（预览 / 回放 HLS，以及下载 MP4）。
     * 会话表只存在于内存，重启后这些目录无人认领便会永久堆积。
     *
     * @return 清理掉的目录数量
     */
    public int purgeTempMediaDirs(Path mediaRoot) {
        if (mediaRoot == null || !Files.isDirectory(mediaRoot)) {
            return 0;
        }
        int removed = 0;
        for (String sub : TEMP_MEDIA_SUBDIRS) {
            Path parent = mediaRoot.resolve(sub);
            if (!Files.isDirectory(parent)) {
                continue;
            }
            List<Path> children;
            try (var list = Files.list(parent)) {
                children = list.toList();
            } catch (Exception e) {
                log.warn("扫描残留媒体目录失败：{}", parent, e);
                continue;
            }
            for (Path child : children) {
                try {
                    deleteRecursively(child);
                    removed++;
                } catch (Exception e) {
                    log.warn("清理残留媒体目录失败：{}", child, e);
                }
            }
        }
        if (removed > 0) {
            log.info("启动清理：已删除上一进程遗留的临时媒体目录 {} 个", removed);
        }
        return removed;
    }

    /**
     * 删除指定目录，但保留 exceptFile（下载中断时保留半成品 MP4 之外的情况）。
     */
    public void purgeSessionFiles(MediaSession session) {
        String dir = session.getOutputDir();
        if (dir == null) {
            return;
        }
        try {
            deleteRecursively(Path.of(dir));
        } catch (Exception e) {
            log.warn("删除会话目录失败：sessionId={}, dir={}", session.getSessionId(), dir, e);
        }
    }

    // ==================== 超时看护 ====================

    /**
     * 一轮超时检查。
     *
     * <p>停止动作一律走 stopExecutor：stop / finishDownload 内部会调用海康 SDK 接口，
     * 个别设备无响应时可能长时间不返回。看护线程只有一个，被卡住会让其余会话的
     * 超时判停全部失效，因此这里只做判定，不直接执行停止。</p>
     */
    void sweep() {
        try {
            long now = System.currentTimeMillis();
            for (MediaSession session : sessions.values()) {
                try {
                    PendingCleanup cleanup = pendingCleanup.get(session.getSessionId());
                    if (cleanup != null) {
                        requestCleanup(cleanup, false);
                        continue;
                    }
                    if (session.isStopping() || session.isFinished()) {
                        sweepFinished(session, now);
                        continue;
                    }
                    long ageSec = Duration.between(session.getCreatedAt(), java.time.LocalDateTime.now()).getSeconds();
                    if (session.getStatus() == SessionStatus.STARTING) {
                        int timeout = propertiesStartTimeoutSec();
                        if (ageSec > timeout) {
                            stopAsync(session, SessionStatus.FAILED,
                                    "设备回连超时（" + timeout + " 秒内未收到码流），请检查公网 IP/端口与安全组是否放行");
                        }
                        continue;
                    }
                    if (session.getStatus() == SessionStatus.RUNNING) {
                        FfmpegProcess ffmpeg = session.getFfmpegProcess();
                        if (ffmpeg != null && !ffmpeg.isAlive()) {
                            stopAsync(session, SessionStatus.FAILED, "ffmpeg 进程已退出，会话终止");
                            continue;
                        }
                        if (session.getType() == SessionType.DOWNLOAD
                                && ffmpeg != null && ffmpeg.isWriteFailed()) {
                            finishDownloadAsync(session, INCOMPLETE_DOWNLOAD);
                            continue;
                        }
                        long idleSec = (now - session.getLastDataTime()) / 1000;
                        if (idleSec > propertiesIdleTimeoutSec()) {
                            stopAsync(session, SessionStatus.FAILED,
                                    "码流中断超过 " + propertiesIdleTimeoutSec() + " 秒，会话自动结束");
                            continue;
                        }
                        if (session.getType() == SessionType.DOWNLOAD
                                && ageSec > propertiesDownloadMaxSec()) {
                            finishDownloadAsync(session, DOWNLOAD_TIMEOUT);
                        }
                    }
                } catch (Exception e) {
                    log.error("看护会话异常：sessionId={}", session.getSessionId(), e);
                }
            }
        } catch (Exception e) {
            log.error("会话看护任务异常", e);
        }
    }

    private void sweepFinished(MediaSession session, long now) {
        if (pendingCleanup.containsKey(session.getSessionId())) {
            return;
        }
        // 已显式交还的文件不再受保留期保护，删除失败在下一轮看护继续重试。
        if (!session.isFileCleanupStarted()) {
            if (session.getUpdatedAt() == null) {
                return;
            }
            long updatedMillis = session.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (now - updatedMillis <= retainMillisFor(session)) {
                return;
            }
        }
        if (!session.beginFileCleanup()) {
            return;
        }
        destroyFfmpegNow(session);
        session.runReleaseHook();
        dropIsupIndex(session);
        String dir = session.getOutputDir();
        if (dir != null) {
            try {
                deleteRecursively(Path.of(dir));
            } catch (Exception e) {
                log.warn("清理终态会话目录失败：sessionId={}", session.getSessionId(), e);
                return;
            }
        }
        sessions.remove(session.getSessionId());
        log.info("已移除终态会话：sessionId={}, status={}", session.getSessionId(), session.getStatus());
    }

    /**
     * 终态会话的保留时长。已完成的下载需要留足时间让调用方取走文件，
     * 到期后连同 MP4 一起清理，否则每下一个录像都会永久占用一份磁盘与内存。
     */
    private long retainMillisFor(MediaSession session) {
        boolean finishedDownload = session.getType() == SessionType.DOWNLOAD
                && session.getStatus() == SessionStatus.COMPLETED;
        if (!finishedDownload) {
            return FINISHED_RETAIN_MILLIS;
        }
        int downloadRetainMinutes = properties.getSession().getDownloadRetainMinutes();
        return downloadRetainMinutes > 0 ? Duration.ofMinutes(downloadRetainMinutes).toMillis() : Long.MAX_VALUE;
    }

    /**
     * 下载结束（收到停止信令或超时）：关闭 ffmpeg 并判定结果。
     */
    public void finishDownload(MediaSession session, String reason) {
        if (session == null || session.getType() != SessionType.DOWNLOAD) {
            return;
        }
        PendingCleanup cleanup;
        synchronized (session) {
            if (session.isStopping() || session.isFinished()) {
                return;
            }
            cleanup = beginCleanup(session, true, reason);
        }
        // 保留显式调用方等待本地文件收尾的语义，native Stop 始终独立执行。
        requestCleanup(cleanup, true);
    }
    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private int propertiesStartTimeoutSec() {
        return properties.getSession().getStartTimeoutSec();
    }

    private int propertiesIdleTimeoutSec() {
        return properties.getSession().getIdleTimeoutSec();
    }

    private int propertiesDownloadMaxSec() {
        return properties.getSession().getDownloadMaxSec();
    }

    /** 本地写入与 native 会话尚未全部释放的名额数，终态本身不会释放名额。 */
    private final java.util.concurrent.atomic.AtomicInteger activeSlots =
            new java.util.concurrent.atomic.AtomicInteger();

    public record ResourceSnapshot(int reservedSlots, int pendingCleanup, int nativeStopActive,
                                   int nativeStopQueued, long oldestCleanupAgeMillis, long bufferedBytes) { }

    /** 纯内存诊断；业务终态不代表 native 资源已经归还。 */
    public ResourceSnapshot resourceSnapshot() {
        long now = System.nanoTime(), oldestAge = 0;
        for (PendingCleanup cleanup : pendingCleanup.values())
            oldestAge = Math.max(oldestAge, Math.max(0, now - cleanup.startedNanos));
        ThreadPoolExecutor nativeStops = (ThreadPoolExecutor) nativeStopExecutor;
        return new ResourceSnapshot(activeSlots.get(), pendingCleanup.size(), nativeStops.getActiveCount(),
                nativeStops.getQueue().size(), TimeUnit.NANOSECONDS.toMillis(oldestAge),
                FfmpegProcess.getTotalBufferedBytes());
    }

    /** 先暂停领取，避免设备资源尚未归还时把新单的重试次数耗尽。 */
    public boolean recordingAdmissionBlocked() {
        if (FfmpegProcess.globalBudgetSaturated()) return true;
        int maximum = properties.getSession().getMaxActiveSessions();
        if (maximum > 0 && activeSlots.get() >= maximum) return true;
        long now = System.nanoTime();
        for (PendingCleanup cleanup : pendingCleanup.values()) {
            if ((!cleanup.nativeStopped || cleanup.session.hasNativeOperations())
                    && now - cleanup.startedNanos >= TimeUnit.SECONDS.toNanos(5)) return true;
        }
        return false;
    }

    private static String indexKey(SessionType type, int isupSessionId) {
        return type + ":" + isupSessionId;
    }

    /**
     * 服务关闭：停止全部会话。
     */
    public void stopAll(String reason) {
        for (MediaSession session : sessions.values()) {
            if (!session.isFinished()) {
                stop(session, SessionStatus.STOPPED, reason);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        watchdog.shutdownNow();
        prepareSdkUnload(5000);
        stopExecutor.shutdown();
        nativeStopExecutor.shutdown();
        // 不中断 native Stop，也不清空尚未确认停止的会话/callback。
        // 两个池均使用 daemon 线程；仍运行的任务会在返回后完成实际释放。
    }

    /**
     * 关闭新会话入口，并在给定期限内等待全部本地/native 收尾确认。
     * false 表示 SDK 仍可能使用 callback，调用方必须保留 listener、SDK 库和强引用。
     */
    public boolean prepareSdkUnload(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        synchronized (lifecycleMonitor) {
            closing = true;
        }
        UNRELEASED_MANAGERS.add(this);
        stopAll("服务关闭");
        // 先前队列拒绝/停止失败的会话也必须计入本轮卸载检查。
        for (PendingCleanup cleanup : pendingCleanup.values()) {
            requestCleanup(cleanup, false);
        }
        try {
            synchronized (lifecycleMonitor) {
                while (!pendingCleanup.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lifecycleMonitor, remaining);
                }
            }
            // pending 已清空后禁止任何 native 新提交，并确认最后一个 Stop 线程真正返回。
            nativeStopExecutor.shutdown();
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!nativeStopExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                return false;
            }
            UNRELEASED_MANAGERS.remove(this);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    /** 只有设备正常结束、文件有内容、且写入未中断，才算下载完成。超时强制结束不算。 */
    private static boolean downloadComplete(long size, boolean writeFailed, boolean muxSucceeded, String reason) {
        return size > 0 && size < RecordingStorageGuard.MAX_OUTPUT_BYTES
                && !writeFailed && muxSucceeded && !DOWNLOAD_TIMEOUT.equals(reason);
    }

    private static String downloadFailureReason(boolean writeFailed, boolean muxSucceeded, String reason) {
        if (writeFailed) {
            return INCOMPLETE_DOWNLOAD;
        }
        if (!muxSucceeded) {
            return DOWNLOAD_MUX_FAILED;
        }
        return reason == null ? "下载失败：未生成有效的录像文件" : reason;
    }

    void shutdownStopExecutor() {
        stopExecutor.shutdown();
        try {
            if (!stopExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                stopExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            stopExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
