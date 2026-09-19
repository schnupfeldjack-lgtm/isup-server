package com.hiki.isup.model;

import com.hiki.isup.service.FfmpegProcess;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    private volatile SessionStatus status = SessionStatus.STARTING;
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

    private volatile LocalDateTime startTime;
    private volatile LocalDateTime endTime;
    private volatile String error;

    private volatile FfmpegProcess ffmpegProcess;
    /** JNA 数据回调的强引用持有者 */
    private volatile Object dataCallback;

    private volatile long lastDataTime = System.currentTimeMillis();
    private final AtomicLong bytesReceived = new AtomicLong(0);

    /** 保证同一 session 不会被重复 stop */
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /** 会话结束后执行的清理钩子（用于释放 newlink 回调里保存的 handle -> callback 引用） */
    private volatile Runnable releaseHook;
    private final AtomicBoolean releaseHookRun = new AtomicBoolean(false);

    public MediaSession(String sessionId, SessionType type, String deviceId, int channel) {
        this.sessionId = sessionId;
        this.type = type;
        this.deviceId = deviceId;
        this.channel = channel;
        this.createdAt = LocalDateTime.now();
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
        lastDataTime = System.currentTimeMillis();
        if (len > 0) {
            bytesReceived.addAndGet(len);
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

    public void setStatus(SessionStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
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

    public Object getDataCallback() {
        return dataCallback;
    }

    public void setDataCallback(Object dataCallback) {
        this.dataCallback = dataCallback;
    }

    public void setReleaseHook(Runnable releaseHook) {
        this.releaseHook = releaseHook;
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
        return bytesReceived.get();
    }
}
