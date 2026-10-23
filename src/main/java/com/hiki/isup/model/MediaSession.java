package com.hiki.isup.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hiki.isup.service.FfmpegProcess;
import com.hiki.isup.service.HikStreamClock;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一路媒体会话（预览 / 回放 / 下载）。
 *
 * <p>关键点：</p>
 * <ul>
 *   <li>dataCallback 必须保持强引用，JNA 回调对象被 GC 后 native 回调会直接崩溃。</li>
 *   <li>sessionId（本服务生成）与 isupSessionId（海康 SDK 会话 ID）必须正确关联。</li>
 *   <li>stopRequested 保证同一会话不会被重复停止。</li>
 * </ul>
 */
public class MediaSession {

    private final String sessionId;
    private final SessionType type;
    private final String deviceId;
    private final int channel;
    private final LocalDateTime createdAt;
    /** 创建会话时的注册连接快照；对象身份区分 SDK 登录句柄复用后的新连接。 */
    private final DeviceInfo deviceConnection;

    private volatile SessionStatus status = SessionStatus.STARTING;
    private volatile long viewerLeaseExpiresAt;
    private volatile boolean playbackReleased;
    public static final long VIEWER_LEASE_MILLIS = 90_000;

    public synchronized void enableViewerLease() {
        if (type != SessionType.PLAYBACK) throw new IllegalArgumentException("仅回放支持观看租约");
        viewerLeaseExpiresAt = System.currentTimeMillis() + VIEWER_LEASE_MILLIS;
    }
    public boolean hasViewerLease() { return viewerLeaseExpiresAt > 0; }
    public long getViewerLeaseExpiresAt() { return viewerLeaseExpiresAt; }
    public boolean isPlaybackReleased() { return playbackReleased; }
    public synchronized void releasePlayback() { playbackReleased = true; }
    public synchronized boolean renewViewerLease(long now) {
        if (!hasViewerLease() || playbackReleased || fileCleanupStarted || recoveryFrozen || now >= viewerLeaseExpiresAt
                || (isFinished() && status != SessionStatus.COMPLETED)) return false;
        viewerLeaseExpiresAt = now + VIEWER_LEASE_MILLIS;
        return true;
    }
    private volatile LocalDateTime updatedAt = LocalDateTime.now();

    /** 设备登录句柄 */
    private volatile int lUserId = -1;
    /** 海康 SDK 会话 ID（预览来自 StartGetRealStreamV11，回放来自 StartPlayBack / newlink 回调） */
    private volatile int isupSessionId = -1;
    /** 设备回连后的链路句柄（预览句柄 / 回放句柄） */
    private volatile int linkHandle = -1;

    /** HLS 播放地址 */
    private volatile String playUrl;
    /** 下载文件绝对路径 */
    private volatile String filePath;
    /** 输出目录（HLS 切片目录或下载目录） */
    private volatile String outputDir;
    /** 下载文件大小（字节），-1 表示尚未确定，确定后不再反复查磁盘 */
    private volatile long fileSize = -1;
    private int fileConsumers;
    private boolean recoveryFrozen;
    private boolean fileCleanupStarted;

    /** 成片排队、转码和上传期间保护已完成下载的文件。 */
    public synchronized boolean retainFiles() {
        if (fileCleanupStarted || recoveryFrozen) {
            return false;
        }
        fileConsumers++;
        return true;
    }

    public synchronized void releaseFiles() {
        if (fileConsumers <= 0) {
            throw new IllegalStateException("录像文件使用计数不匹配");
        }
        fileConsumers--;
    }

    public synchronized boolean beginFileCleanup() {
        if (fileConsumers > 0) {
            return false;
        }
        fileCleanupStarted = true;
        return true;
    }

    /** 已取得清理权；删除失败后仍需重试，不再等待文件保留期。 */
    public synchronized boolean isFileCleanupStarted() {
        return fileCleanupStarted;
    }

    public synchronized boolean recoveryQuiescent() {
        // 来源已结束不代表观看已结束；有效租约仍保护 HLS，不能因 API 暂时静默而重启。
        if (hasViewerLease() && !playbackReleased && System.currentTimeMillis() < viewerLeaseExpiresAt) {
            return false;
        }
        return isFinished() && nativeOperations == 0 && fileConsumers == 0
                && (ffmpegProcess == null || !ffmpegProcess.isAlive());
    }
    public synchronized void freezeForProcessRecovery() { recoveryFrozen = true; }

    private volatile LocalDateTime startTime;
    private volatile LocalDateTime endTime;
    private volatile String error;
    private volatile String rawFilePath;

    public String getRawFilePath() { return rawFilePath; }
    public void setRawFilePath(String rawFilePath) { this.rawFilePath = rawFilePath; }

    private volatile FfmpegProcess ffmpegProcess;
    /** 下载码流里读到的首末帧绝对时间，用于验证业务时间区间。 */
    private final HikStreamClock streamClock = new HikStreamClock();
    /** JNA 数据回调的强引用持有者 */
    private volatile Object dataCallback;

    private volatile long lastDataTime = System.currentTimeMillis();
    /** 码流字节数。每包都会累加，用 LongAdder 降低多路回调线程写同一计数器时的竞争 */
    private final LongAdder bytesReceived = new LongAdder();

    /**
     * 最后收包时间的刷新间隔（毫秒）。看护任务以秒为单位判定空闲，
     * 无需每个码流包都写一次 volatile 字段。
     */
    private static final long LAST_DATA_REFRESH_MILLIS = 200;

    /** 保证同一 session 不会被重复 stop */
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    /** 正在创建/注册 native 资源的调用数。取消不能等待 SDK，但释放必须等这些调用返回。 */
    private int nativeOperations;

    public synchronized boolean tryBeginNativeOperation() {
        if (recoveryFrozen || isStopping() || isFinished()) {
            return false;
        }
        nativeOperations++;
        return true;
    }

    /** 检查取消、登记句柄和 callback 强引用必须是一个原子操作；SDK 调用在锁外执行。 */
    public synchronized boolean tryBeginLinkRegistration(int handle, Object callback, Runnable hook) {
        if (handle < 0 || linkHandle >= 0 || !tryBeginNativeOperation()) {
            return false;
        }
        linkHandle = handle;
        dataCallback = callback;
        releaseHook = hook;
        return true;
    }

    public synchronized boolean hasNativeOperations() {
        return nativeOperations > 0;
    }

    /** 只能由 SessionManager 调用；最后一个调用返回后立即重试延迟的资源释放。 */
    public synchronized boolean endNativeOperation() {
        if (nativeOperations <= 0) {
            throw new IllegalStateException("native 操作计数不匹配");
        }
        return --nativeOperations == 0;
    }
    /** 进入终态时唤醒等待方（出库同步等），避免固定间隔轮询 */
    private final CountDownLatch terminalLatch = new CountDownLatch(1);

    /** 会话结束后执行的清理钩子（用于释放 newlink 回调里保存的 handle -> callback 引用） */
    private volatile Runnable releaseHook;
    private final AtomicBoolean releaseHookRun = new AtomicBoolean(false);

    public MediaSession(String sessionId, SessionType type, String deviceId, int channel) {
        this(sessionId, type, deviceId, channel, null);
    }

    public MediaSession(String sessionId, SessionType type, String deviceId, int channel, DeviceInfo deviceConnection) {
        this.sessionId = sessionId;
        this.type = type;
        this.deviceId = deviceId;
        this.channel = channel;
        this.createdAt = LocalDateTime.now();
        this.deviceConnection = deviceConnection;
    }

    @JsonIgnore
    public DeviceInfo getDeviceConnection() {
        return deviceConnection;
    }

    /**
     * 标记开始停止，返回 true 表示本次调用是第一个请求停止的（防止重复 stop）。
     */
    public boolean markStopping() {
        return stopRequested.compareAndSet(false, true);
    }

    public boolean isStopping() {
        return stopRequested.get();
    }

    /**
     * 是否处于已经结束的终态
     */
    public boolean isFinished() {
        return status == SessionStatus.COMPLETED
                || status == SessionStatus.FAILED
                || status == SessionStatus.STOPPED;
    }

    public void touchData(int len) {
        long now = System.currentTimeMillis();
        if (now - lastDataTime >= LAST_DATA_REFRESH_MILLIS) {
            lastDataTime = now;
        }
        if (len > 0) {
            bytesReceived.add(len);
        }
    }

    public String getSessionId() {
        return sessionId;
    }

    public SessionType getType() {
        return type;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int getChannel() {
        return channel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(SessionStatus status) {
        // 超时/取消一旦发布，迟到的首帧或收尾结果不能将任务重新置为运行/成功。
        if (isFinished() && !(type == SessionType.PLAYBACK && this.status == SessionStatus.COMPLETED
                && playbackReleased && (status == SessionStatus.STOPPED || status == SessionStatus.FAILED))) {
            return;
        }
        this.status = status;
        this.updatedAt = LocalDateTime.now();
        if (isFinished()) {
            terminalLatch.countDown();
        }
    }

    /**
     * 阻塞直到会话进入终态或超时。
     *
     * @return true 表示已终态，false 表示超时且仍未终态
     */
    public boolean awaitTerminal(long timeoutMs) throws InterruptedException {
        if (isFinished()) {
            return true;
        }
        if (timeoutMs <= 0) {
            return isFinished();
        }
        if (terminalLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return true;
        }
        return isFinished();
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        if (updatedAt != null) {
            this.updatedAt = updatedAt;
        }
    }

    public int getLUserId() {
        return lUserId;
    }

    public void setLUserId(int lUserId) {
        this.lUserId = lUserId;
    }

    public int getIsupSessionId() {
        return isupSessionId;
    }

    public void setIsupSessionId(int isupSessionId) {
        this.isupSessionId = isupSessionId;
    }

    public int getLinkHandle() {
        return linkHandle;
    }

    public void setLinkHandle(int linkHandle) {
        this.linkHandle = linkHandle;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public FfmpegProcess getFfmpegProcess() {
        return ffmpegProcess;
    }

    public void setFfmpegProcess(FfmpegProcess ffmpegProcess) {
        this.ffmpegProcess = ffmpegProcess;
    }

    public HikStreamClock streamClock() {
        return streamClock;
    }

    public Object getDataCallback() {
        return dataCallback;
    }

    public void setDataCallback(Object dataCallback) {
        this.dataCallback = dataCallback;
    }

    public void setReleaseHook(Runnable releaseHook) {
        Runnable previous = this.releaseHook;
        this.releaseHook = releaseHook;
        if (previous != null && previous != releaseHook && !releaseHookRun.get()) {
            try {
                previous.run();
            } catch (Exception ignored) {
                // 旧钩子失败不影响挂上新钩子
            }
        }
    }

    /**
     * 执行清理钩子（只会执行一次），释放回调引用，避免内存泄漏。
     */
    public void runReleaseHook() {
        if (releaseHook == null || !releaseHookRun.compareAndSet(false, true)) {
            return;
        }
        try {
            releaseHook.run();
        } catch (Exception e) {
            // 清理钩子失败不影响主流程
        }
    }

    public long getLastDataTime() {
        return lastDataTime;
    }

    public long getBytesReceived() {
        return bytesReceived.sum();
    }
}
