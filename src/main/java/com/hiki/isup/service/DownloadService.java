package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.model.response.DownloadTaskResponse;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.util.MediaPaths;
import com.hiki.isup.util.TimeRange;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按时间段下载录像。
 *
 * <p>不另起炉灶，直接复用回放流程（StartPlayBack + StartPushPlayBack），
 * 只是后端输出为 MP4 文件而不是 HLS。请求立即返回，下载在后台异步进行。</p>
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IsupProperties properties;
    private final IsupService isupService;
    private final DeviceRegistry deviceRegistry;
    private final SessionManager sessionManager;
    private final FfmpegService ffmpegService;

    /** native Start 可能不返回；固定线程数、无排队，不让业务等待线程无限增长。 */
    private final ExecutorService packingStartExecutor = new ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "isup-packing-start");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public DownloadService(IsupProperties properties,
                           IsupService isupService,
                           DeviceRegistry deviceRegistry,
                           SessionManager sessionManager,
                           FfmpegService ffmpegService) {
        this.properties = properties;
        this.isupService = isupService;
        this.deviceRegistry = deviceRegistry;
        this.sessionManager = sessionManager;
        this.ffmpegService = ffmpegService;
    }

    public DownloadTaskResponse startDownload(DownloadRequest request) {
        return startDownload(request, false);
    }

    /** 出库录像专用：保留原始时钟，不改变手动下载行为。 */
    public DownloadTaskResponse startPackingDownload(DownloadRequest request) {
        return startDownload(request, true);
    }

    /** 出库任务使用同一单调时钟截止时间，native Start 卡住也不能占满业务取流线程。 */
    public DownloadTaskResponse startPackingDownload(DownloadRequest request, long deadlineNanos) {
        MediaSession session = prepareSession(request);
        if (!startupAllowed(session, deadlineNanos)) {
            return responseFor(session);
        }
        Future<DownloadTaskResponse> startup;
        try {
            startup = packingStartExecutor.submit(() -> startSession(session, true, deadlineNanos));
        } catch (RejectedExecutionException e) {
            sessionManager.stop(session, SessionStatus.FAILED, "录像启动线程已满或已关闭，请稍后重试");
            return responseFor(session);
        }
        // 超时/中断只结束业务等待；worker 登记迟到句柄，finally 交还 lease 触发清理。
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            return startup.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            sessionManager.stop(session, SessionStatus.FAILED, "启动录像下载超时");
            return responseFor(session);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sessionManager.stop(session, SessionStatus.FAILED, "启动录像下载被中断");
            return responseFor(session);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw IsupException.wrap("启动录像下载", session.getDeviceId(), session.getChannel(),
                    session.getSessionId(), cause);
        }
    }

    private DownloadTaskResponse startDownload(DownloadRequest request, boolean preserveSource) {
        return startSession(prepareSession(request), preserveSource, Long.MAX_VALUE);
    }

    private MediaSession prepareSession(DownloadRequest request) {
        String deviceId = request.getDeviceId().trim();
        int channel = request.getChannel();
        LocalDateTime[] range = TimeRange.validate(request.getStartTime(), request.getEndTime());
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];

        isupService.requireReady("NET_ECMS_StartPlayBack");
        DeviceInfo connection = deviceRegistry.requireConnection(deviceId, "下载录像");
        int lUserId = connection.getLUserId();

        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, connection, channel);
        session.setLUserId(lUserId);
        session.setStartTime(start);
        session.setEndTime(end);
        return session;
    }

    private DownloadTaskResponse startSession(MediaSession session, boolean preserveSource, long deadlineNanos) {
        if (!session.tryBeginNativeOperation()) {
            return responseFor(session);
        }
        String deviceId = session.getDeviceId();
        int channel = session.getChannel();
        int lUserId = session.getLUserId();
        LocalDateTime start = session.getStartTime();
        LocalDateTime end = session.getEndTime();
        try {
            if (!startupAllowed(session, deadlineNanos)) {
                return responseFor(session);
            }
            Path dir = MediaPaths.downloadDir(properties.getMediaDir(), session.getSessionId());
            Path file = dir.resolve(buildFileName(deviceId, channel, start, end));
            session.setOutputDir(dir.toString());
            session.setFilePath(file.toString());

            HCISUPCMS cms = isupService.cms();

            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN playbackIn = PlaybackService.buildPlaybackInfoIn(
                    channel, start, end, properties.getPublicIp(), properties.getStream().getPlaybackPort());

            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT playbackOut = new HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT();
            playbackOut.write();

            if (!startupAllowed(session, deadlineNanos)) {
                return responseFor(session);
            }
            if (!cms.NET_ECMS_StartPlayBack(lUserId, playbackIn, playbackOut)) {
                throw IsupException.sdkFailed("NET_ECMS_StartPlayBack", deviceId, channel,
                        session.getSessionId(), cms.NET_ECMS_GetLastError(),
                        "startTime=" + start + ", endTime=" + end);
            }
            playbackOut.read();
            int isupSessionId = playbackOut.lSessionID;
            // 即使业务已取消，Start 迟到返回的句柄也必须登记后再由停止流程释放。
            sessionManager.registerIsupSession(session, isupSessionId);
            if (!startupAllowed(session, deadlineNanos)) {
                return responseFor(session);
            }
            log.info("下载录像 NET_ECMS_StartPlayBack 成功：deviceId={}, channel={}, startTime={}, endTime={}, taskId={}, isupSessionId={}",
                    deviceId, channel, start, end, session.getSessionId(), isupSessionId);

            FfmpegProcess writer;
            if (preserveSource) {
                Path rawFile = dir.resolve("recording-source.ps");
                session.setRawFilePath(rawFile.toString());
                writer = ffmpegService.startPackingCapture(properties, rawFile, session.getSessionId());
            } else {
                writer = ffmpegService.startMp4(properties, file, session.getSessionId());
            }
            synchronized (session) {
                session.setFfmpegProcess(writer);
            }
            if (!startupAllowed(session, deadlineNanos)) {
                return responseFor(session);
            }

            HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN pushIn = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN();
            pushIn.read();
            pushIn.dwSize = pushIn.size();
            pushIn.lSessionID = isupSessionId;
            pushIn.write();

            HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT pushOut = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT();
            pushOut.read();
            pushOut.dwSize = pushOut.size();
            pushOut.write();

            if (!startupAllowed(session, deadlineNanos)) {
                return responseFor(session);
            }
            if (!cms.NET_ECMS_StartPushPlayBack(lUserId, pushIn, pushOut)) {
                throw IsupException.sdkFailed("NET_ECMS_StartPushPlayBack", deviceId, channel,
                        session.getSessionId(), cms.NET_ECMS_GetLastError(),
                        PlaybackService.pushPlayBackFailureHint(deviceRegistry, deviceId, start, end));
            }
            if (!startupAllowed(session, deadlineNanos)) {
                return responseFor(session);
            }
            log.info("下载录像 NET_ECMS_StartPushPlayBack 成功：deviceId={}, channel={}, taskId={}",
                    deviceId, channel, session.getSessionId());

            return responseFor(session);
        } catch (RuntimeException e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw IsupException.wrap("启动录像下载", deviceId, channel, session.getSessionId(), e);
        } finally {
            sessionManager.endNativeOperation(session);
        }
    }

    private boolean startupAllowed(MediaSession session, long deadlineNanos) {
        if (session.isStopping() || session.isFinished()) {
            return false;
        }
        if (deadlineNanos != Long.MAX_VALUE && deadlineNanos - System.nanoTime() <= 0) {
            sessionManager.stop(session, SessionStatus.FAILED, "启动录像下载超时");
            return false;
        }
        return true;
    }

    private static DownloadTaskResponse responseFor(MediaSession session) {
        DownloadTaskResponse response = new DownloadTaskResponse(session.getSessionId(), session.getStatus());
        response.setDeviceId(session.getDeviceId());
        response.setChannel(session.getChannel());
        response.setFileUrl("/api/v1/download/" + session.getSessionId() + "/file");
        return response;
    }

    @PreDestroy
    public void shutdown() {
        // 不强杀 native：已接受的 worker 必须走完 finally，交还操作 lease。
        packingStartExecutor.shutdown();
    }

    /**
     * 生成下载文件名，同时把 deviceId 中的非法字符替换掉，避免路径穿越。
     */
    public static String buildFileName(String deviceId, int channel, LocalDateTime start, LocalDateTime end) {
        // 只保留字母数字与 _ - ，其余（含 / \ .）全部替换，杜绝路径穿越
        String safeDeviceId = deviceId.replaceAll("[^A-Za-z0-9_-]", "_");
        return safeDeviceId + "_ch" + channel + "_" + FILE_TIME.format(start) + "-" + FILE_TIME.format(end) + ".mp4";
    }
}
