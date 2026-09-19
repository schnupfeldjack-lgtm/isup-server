package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.model.response.DownloadTaskResponse;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.util.MediaPaths;
import com.hiki.isup.util.TimeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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
        String deviceId = request.getDeviceId().trim();
        int channel = request.getChannel();
        LocalDateTime[] range = TimeRange.validate(request.getStartTime(), request.getEndTime());
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];

        isupService.requireReady("NET_ECMS_StartPlayBack");
        int lUserId = deviceRegistry.requireLUserId(deviceId, "下载录像");

        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, deviceId, channel);
        session.setLUserId(lUserId);
        session.setStartTime(start);
        session.setEndTime(end);

        Path dir = MediaPaths.downloadDir(properties.getMediaDir(), session.getSessionId());
        Path file = dir.resolve(buildFileName(deviceId, channel, start, end));
        try {
            session.setOutputDir(dir.toString());
            session.setFilePath(file.toString());
            session.setFfmpegProcess(ffmpegService.startMp4(properties, file, session.getSessionId()));

            HCISUPCMS cms = isupService.cms();

            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN playbackIn = PlaybackService.buildPlaybackInfoIn(
                    channel, start, end, properties.getPublicIp(), properties.getStream().getPlaybackPort());

            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT playbackOut = new HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT();
            playbackOut.write();

            if (!cms.NET_ECMS_StartPlayBack(lUserId, playbackIn, playbackOut)) {
                throw IsupException.sdkFailed("NET_ECMS_StartPlayBack", deviceId, channel,
                        session.getSessionId(), cms.NET_ECMS_GetLastError(),
                        "startTime=" + start + ", endTime=" + end);
            }
            playbackOut.read();
            int isupSessionId = playbackOut.lSessionID;
            sessionManager.registerIsupSession(session, isupSessionId);
            log.info("下载录像 NET_ECMS_StartPlayBack 成功：deviceId={}, channel={}, startTime={}, endTime={}, taskId={}, isupSessionId={}",
                    deviceId, channel, start, end, session.getSessionId(), isupSessionId);

            HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN pushIn = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN();
            pushIn.read();
            pushIn.dwSize = pushIn.size();
            pushIn.lSessionID = isupSessionId;
            pushIn.write();

            HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT pushOut = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT();
            pushOut.read();
            pushOut.dwSize = pushOut.size();
            pushOut.write();

            if (!cms.NET_ECMS_StartPushPlayBack(lUserId, pushIn, pushOut)) {
                throw IsupException.sdkFailed("NET_ECMS_StartPushPlayBack", deviceId, channel,
                        session.getSessionId(), cms.NET_ECMS_GetLastError());
            }
            log.info("下载录像 NET_ECMS_StartPushPlayBack 成功：deviceId={}, channel={}, taskId={}",
                    deviceId, channel, session.getSessionId());

            DownloadTaskResponse response = new DownloadTaskResponse(session.getSessionId(), session.getStatus());
            response.setDeviceId(deviceId);
            response.setChannel(channel);
            response.setFileUrl("/api/v1/download/" + session.getSessionId() + "/file");
            return response;
        } catch (RuntimeException e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw IsupException.wrap("启动录像下载", deviceId, channel, session.getSessionId(), e);
        }
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
