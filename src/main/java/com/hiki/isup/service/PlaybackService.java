package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.PlaybackRequest;
import com.hiki.isup.model.response.StreamSessionResponse;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.util.MediaPaths;
import com.hiki.isup.util.TimeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static com.hiki.isup.sdk.NativeSdkLoader.copyTo;

/**
 * 按时间段回放。
 *
 * <p>流程：StartPlayBack -&gt; StartPushPlayBack -&gt; 设备回连 8004 -&gt; FFmpeg -&gt; HLS。</p>
 * <p>时间按录像机设备本地时间处理（byLocalOrUTC = 0）。</p>
 */
@Service
public class PlaybackService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

    private final IsupProperties properties;
    private final IsupService isupService;
    private final DeviceRegistry deviceRegistry;
    private final SessionManager sessionManager;
    private final FfmpegService ffmpegService;

    public PlaybackService(IsupProperties properties,
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

    public StreamSessionResponse startPlayback(PlaybackRequest request) {
        String deviceId = request.getDeviceId().trim();
        int channel = request.getChannel();
        LocalDateTime[] range = TimeRange.validate(request.getStartTime(), request.getEndTime());
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];
        int linkMode = request.getLinkMode() == null ? properties.getLinkMode() : request.getLinkMode();

        isupService.requireReady("NET_ECMS_StartPlayBack");
        int lUserId = deviceRegistry.requireLUserId(deviceId, "回放");

        MediaSession session = sessionManager.create(SessionType.PLAYBACK, deviceId, channel);
        session.setLUserId(lUserId);
        session.setStartTime(start);
        session.setEndTime(end);

        Path dir = MediaPaths.playbackDir(properties.getMediaDir(), session.getSessionId());
        try {
            session.setOutputDir(dir.toString());
            session.setPlayUrl("/media/playback/" + session.getSessionId() + "/index.m3u8");
            session.setFfmpegProcess(ffmpegService.startHls(properties, dir, session.getSessionId()));

            HCISUPCMS cms = isupService.cms();

            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN playbackIn = buildPlaybackInfoIn(
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
            log.info("NET_ECMS_StartPlayBack 成功：deviceId={}, channel={}, startTime={}, endTime={}, sessionId={}, isupSessionId={}",
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
            log.info("NET_ECMS_StartPushPlayBack 成功：deviceId={}, channel={}, sessionId={}, isupSessionId={}",
                    deviceId, channel, session.getSessionId(), isupSessionId);

            StreamSessionResponse response = StreamSessionResponse.of(session.getSessionId(),
                    SessionType.PLAYBACK, session.getStatus(), session.getPlayUrl());
            response.setDeviceId(deviceId);
            response.setChannel(channel);
            return response;
        } catch (RuntimeException e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw IsupException.wrap("启动按时间段回放", deviceId, channel, session.getSessionId(), e);
        }
    }

    /**
     * 构造按时间回放入参（回放与下载共用）。
     *
     * <ul>
     *   <li>byPlayBackMode = 1（按时间回放）</li>
     *   <li>byStreamPackage = 0（PS 封装，交给 ffmpeg 解封装）</li>
     *   <li>byLocalOrUTC = 0（设备本地时间）</li>
     * </ul>
     */
    public static HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN buildPlaybackInfoIn(int channel,
                                                                           LocalDateTime start,
                                                                           LocalDateTime end,
                                                                           String publicIp,
                                                                           int playbackPort) {
        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN in = new HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN();
        in.read();
        in.dwSize = in.size();
        in.dwChannel = channel;
        in.byPlayBackMode = 1;
        in.byStreamPackage = 0;

        in.unionPlayBackMode = new HCISUPCMS.NET_EHOME_PLAYBACKMODE();
        in.unionPlayBackMode.setType(HCISUPCMS.NET_EHOME_PLAYBACKBYTIME.class);
        in.unionPlayBackMode.struPlayBackbyTime = new HCISUPCMS.NET_EHOME_PLAYBACKBYTIME();

        HCISUPCMS.NET_EHOME_PLAYBACKBYTIME byTime = in.unionPlayBackMode.struPlayBackbyTime;
        fillTime(byTime.struStartTime, start);
        fillTime(byTime.struStopTime, end);
        byTime.byLocalOrUTC = 0;
        byTime.byDuplicateSegment = 0;
        byTime.write();

        copyTo(in.struStreamSever.szIP, publicIp);
        in.struStreamSever.wPort = (short) playbackPort;
        in.write();
        return in;
    }

    private static void fillTime(HCISUPCMS.NET_EHOME_TIME time, LocalDateTime dateTime) {
        time.wYear = (short) dateTime.getYear();
        time.byMonth = (byte) dateTime.getMonthValue();
        time.byDay = (byte) dateTime.getDayOfMonth();
        time.byHour = (byte) dateTime.getHour();
        time.byMinute = (byte) dateTime.getMinute();
        time.bySecond = (byte) dateTime.getSecond();
        time.wMSecond = 0;
    }

    /** 包内可见，便于测试 */
    static void fillTimeForTest(HCISUPCMS.NET_EHOME_TIME time, LocalDateTime dateTime) {
        fillTime(time, dateTime);
    }
}
