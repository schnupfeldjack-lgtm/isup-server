package com.hiki.isup.service;

import com.hiki.isup.exception.NotFoundException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理器：预览 / 回放 / 下载统一用一个 ConcurrentHashMap 管理。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>sessionId 与 ISUP SDK sessionId 的双向关联</li>
 *   <li>停止会话时按顺序释放：ISUP Stop -&gt; FFmpeg -&gt; 临时文件</li>
 *   <li>防止同一个 session 被重复 stop</li>
 *   <li>超时看护：设备回连超时、码流中断、下载超时</li>
 * </ul>
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final NativeSdkLoader sdkLoader;

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

    /**
     * 停止线程池：SDK 回调线程（回放结束信令、设备下线回调）内不能直接调用
     * NET_ESTREAM_StopPlayBack / NET_ECMS_StopPlayBack 等接口，否则可能出现重入死锁。
     * 因此由 SDK 回调触发的停止操作统一丢到这个独立线程执行。
     */
    private final ExecutorService stopExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "isup-session-stop");
        t.setDaemon(true);
        return t;
    });

    /** 下载任务完成后等待 ffmpeg 收尾的时间（秒） */
    private static final int FFMPEG_GRACE_SECONDS = 30;
    /** 终态会话保留时间（毫秒），便于客户端查询最后状态 */
    private static final long FINISHED_RETAIN_MILLIS = Duration.ofMinutes(10).toMillis();

    public SessionManager(NativeSdkLoader sdkLoader) {
        this.sdkLoader = sdkLoader;
        watchdog.scheduleWithFixedDelay(this::sweep, 15, 15, TimeUnit.SECONDS);
    }

    // ==================== 创建与查询 ====================

    public MediaSession create(SessionType type, String deviceId, int channel) {
        String sessionId = UUID.randomUUID().toString();
        MediaSession session = new MediaSession(sessionId, type, deviceId, channel);
        sessions.put(sessionId, session);
        return session;
    }

    public MediaSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public MediaSession require(String sessionId) {
        MediaSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NotFoundException("会话不存在或已清理：sessionId=" + sessionId);
        }
        return session;
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
        return sessionId == null ? null : sessions.get(sessionId);
    }

    /**
     * 回放 / 下载的 newlink 回调兜底匹配：当 SDK 返回的 lSessionID 无效（如 -1）时，
     * 按 deviceId + channel 找到唯一的待接流会话。
     */
    public MediaSession findPendingByDeviceChannel(SessionType type, String deviceId, int channel) {
        List<MediaSession> candidates = new ArrayList<>();
        for (MediaSession s : sessions.values()) {
            if (s.getType() == type
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
     * 停止会话并释放全部资源。
     *
     * @param targetStatus 停止后的目标状态（STOPPED / FAILED / COMPLETED）
     */
    public void stop(MediaSession session, SessionStatus targetStatus, String reason) {
        if (session == null) {
            return;
        }
        // 防止同一个 session 重复 stop
        if (!session.markStopping()) {
            log.debug("会话已在停止流程中，忽略重复调用：sessionId={}", session.getSessionId());
            return;
        }
        log.info("停止会话：sessionId={}, type={}, deviceId={}, channel={}, target={}, reason={}",
                session.getSessionId(), session.getType(), session.getDeviceId(),
                session.getChannel(), targetStatus, reason);

        if (reason != null) {
            session.setError(reason);
        }

        stopIsup(session);
        stopFfmpeg(session);
        cleanupFiles(session, targetStatus);

        session.runReleaseHook();
        session.setStatus(targetStatus);
        log.info("会话已停止：sessionId={}, status={}", session.getSessionId(), targetStatus);
    }

    /**
     * 由 SDK 回调线程触发的停止：异步执行，避免在回调线程内重入 SDK 导致死锁。
     */
    public void stopAsync(MediaSession session, SessionStatus targetStatus, String reason) {
        if (session == null) {
            return;
        }
        stopExecutor.submit(() -> {
            try {
                stop(session, targetStatus, reason);
            } catch (Exception e) {
                log.error("异步停止会话失败：sessionId={}", session.getSessionId(), e);
            }
        });
    }

    /**
     * 由 SDK 回放结束信令触发的下载收尾：异步执行。
     */
    public void finishDownloadAsync(MediaSession session, String reason) {
        if (session == null) {
            return;
        }
        stopExecutor.submit(() -> {
            try {
                finishDownload(session, reason);
            } catch (Exception e) {
                log.error("异步收尾下载任务失败：sessionId={}", session.getSessionId(), e);
            }
        });
    }

    /**
     * 设备下线时，停止该设备的全部会话（由注册回调触发，异步执行）。
     */
    public void stopByDeviceAsync(String deviceId) {
        if (deviceId == null) {
            return;
        }
        stopExecutor.submit(() -> {
            try {
                stopByDevice(deviceId);
            } catch (Exception e) {
                log.error("异步停止设备会话失败：deviceId={}", deviceId, e);
            }
        });
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

    private void stopIsup(MediaSession session) {
        if (!sdkLoader.isLoaded()) {
            return;
        }
        int lUserId = session.getLUserId();
        int isupSessionId = session.getIsupSessionId();
        int linkHandle = session.getLinkHandle();
        try {
            if (session.getType() == SessionType.PREVIEW) {
                HCISUPStream stream = sdkLoader.stream();
                if (linkHandle >= 0) {
                    log.info("NET_ESTREAM_StopPreview，handle={}, sessionId={}", linkHandle, session.getSessionId());
                    stream.NET_ESTREAM_StopPreview(linkHandle);
                }
                if (lUserId >= 0 && isupSessionId >= 0) {
                    HCISUPCMS cms = sdkLoader.cms();
                    log.info("NET_ECMS_StopGetRealStream，lUserID={}, isupSessionId={}", lUserId, isupSessionId);
                    cms.NET_ECMS_StopGetRealStream(lUserId, isupSessionId);
                }
            } else {
                HCISUPStream stream = sdkLoader.stream();
                if (linkHandle >= 0) {
                    log.info("NET_ESTREAM_StopPlayBack，handle={}, sessionId={}", linkHandle, session.getSessionId());
                    stream.NET_ESTREAM_StopPlayBack(linkHandle);
                }
                if (lUserId >= 0 && isupSessionId >= 0) {
                    HCISUPCMS cms = sdkLoader.cms();
                    log.info("NET_ECMS_StopPlayBack，lUserID={}, isupSessionId={}", lUserId, isupSessionId);
                    cms.NET_ECMS_StopPlayBack(lUserId, isupSessionId);
                }
            }
        } catch (Exception e) {
            log.error("停止 ISUP 会话异常（资源可能未完全释放），operation=StopSession, deviceId={}, channel={}, sessionId={}",
                    session.getDeviceId(), session.getChannel(), session.getSessionId(), e);
        } finally {
            isupSessionIndex.remove(indexKey(session.getType(), isupSessionId));
        }
    }

    private void stopFfmpeg(MediaSession session) {
        FfmpegProcess process = session.getFfmpegProcess();
        if (process == null) {
            return;
        }
        try {
            // 下载场景必须优雅关闭 stdin，ffmpeg 才能写出完整 MP4
            process.closeStdin();
            Integer exit = process.waitFor(FFMPEG_GRACE_SECONDS);
            if (exit != null && exit != 0) {
                log.warn("ffmpeg 退出码非 0，sessionId={}, exitCode={}", session.getSessionId(), exit);
            }
        } catch (Exception e) {
            log.warn("等待 ffmpeg 退出异常，sessionId={}", session.getSessionId(), e);
        } finally {
            process.destroy();
            session.setFfmpegProcess(null);
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

    public static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
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

    private void sweep() {
        try {
            long now = System.currentTimeMillis();
            for (MediaSession session : sessions.values()) {
                try {
                    if (session.isStopping() || session.isFinished()) {
                        sweepFinished(session, now);
                        continue;
                    }
                    long ageSec = Duration.between(session.getCreatedAt(), java.time.LocalDateTime.now()).getSeconds();
                    if (session.getStatus() == SessionStatus.STARTING) {
                        int timeout = propertiesStartTimeoutSec();
                        if (ageSec > timeout) {
                            stop(session, SessionStatus.FAILED,
                                    "设备回连超时（" + timeout + " 秒内未收到码流），请检查公网 IP/端口与安全组是否放行");
                        }
                        continue;
                    }
                    if (session.getStatus() == SessionStatus.RUNNING) {
                        long idleSec = (now - session.getLastDataTime()) / 1000;
                        if (idleSec > propertiesIdleTimeoutSec()) {
                            stop(session, SessionStatus.FAILED,
                                    "码流中断超过 " + propertiesIdleTimeoutSec() + " 秒，会话自动结束");
                            continue;
                        }
                        if (session.getType() == SessionType.DOWNLOAD
                                && ageSec > propertiesDownloadMaxSec()) {
                            finishDownload(session, "下载超时，强制结束");
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
        // 下载完成的文件必须保留，不能删；其余终态会话延迟移除以免内存堆积
        if (session.getType() == SessionType.DOWNLOAD && session.getStatus() == SessionStatus.COMPLETED) {
            return;
        }
        if (session.getUpdatedAt() == null) {
            return;
        }
        long updatedMillis = session.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (now - updatedMillis > FINISHED_RETAIN_MILLIS) {
            String dir = session.getOutputDir();
            if (dir != null) {
                try {
                    deleteRecursively(Path.of(dir));
                } catch (Exception e) {
                    log.warn("清理终态会话目录失败：sessionId={}", session.getSessionId(), e);
                }
            }
            sessions.remove(session.getSessionId());
            log.info("已移除终态会话：sessionId={}, status={}", session.getSessionId(), session.getStatus());
        }
    }

    /**
     * 下载结束（收到停止信令或超时）：关闭 ffmpeg 并判定结果。
     */
    public void finishDownload(MediaSession session, String reason) {
        if (session.getType() != SessionType.DOWNLOAD) {
            return;
        }
        if (!session.markStopping()) {
            return;
        }
        stopIsup(session);
        stopFfmpeg(session);

        String filePath = session.getFilePath();
        boolean success = false;
        if (filePath != null) {
            Path path = Path.of(filePath);
            success = Files.exists(path) && sizeOf(path) > 0;
        }
        session.runReleaseHook();
        if (success) {
            session.setStatus(SessionStatus.COMPLETED);
            log.info("录像下载完成：sessionId={}, file={}, size={} bytes",
                    session.getSessionId(), filePath, sizeOf(Path.of(filePath)));
        } else {
            session.setError(reason == null ? "下载失败：未生成有效的 MP4 文件" : reason);
            session.setStatus(SessionStatus.FAILED);
            cleanupFiles(session, SessionStatus.FAILED);
            log.error("录像下载失败：operation=Download, deviceId={}, channel={}, sessionId={}, reason={}",
                    session.getDeviceId(), session.getChannel(), session.getSessionId(), reason);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private int propertiesStartTimeoutSec() {
        return startTimeoutSec;
    }

    private int propertiesIdleTimeoutSec() {
        return idleTimeoutSec;
    }

    private int propertiesDownloadMaxSec() {
        return downloadMaxSec;
    }

    /** 由 IsupService 在启动时注入超时配置（默认值见字段） */
    private volatile int startTimeoutSec = 30;
    private volatile int idleTimeoutSec = 120;
    private volatile int downloadMaxSec = 3600;

    public void configureTimeouts(int startTimeoutSec, int idleTimeoutSec, int downloadMaxSec) {
        this.startTimeoutSec = startTimeoutSec;
        this.idleTimeoutSec = idleTimeoutSec;
        this.downloadMaxSec = downloadMaxSec;
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
        stopExecutor.shutdownNow();
        stopAll("服务关闭");
        for (Map.Entry<String, MediaSession> entry : sessions.entrySet()) {
            isupSessionIndex.remove(indexKey(entry.getValue().getType(), entry.getValue().getIsupSessionId()));
        }
        sessions.clear();
    }
}
