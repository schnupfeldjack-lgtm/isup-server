package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.PreviewRequest;
import com.hiki.isup.model.response.StreamSessionResponse;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.util.MediaPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

import static com.hiki.isup.sdk.NativeSdkLoader.copyTo;

/**
 * 实时预览：ERP -&gt; StartGetRealStreamV11 -&gt; FFmpeg -&gt; StartPushRealStream -&gt; 设备回连 8003 -&gt; HLS。
 */
@Service
public class PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);

    private final IsupProperties properties;
    private final IsupService isupService;
    private final DeviceRegistry deviceRegistry;
    private final SessionManager sessionManager;
    private final FfmpegService ffmpegService;

    public PreviewService(IsupProperties properties,
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

    public StreamSessionResponse startPreview(PreviewRequest request) {
        String deviceId = request.getDeviceId().trim();
        int channel = request.getChannel();
        int streamType = request.getStreamType() == null ? 1 : request.getStreamType();
        if (streamType < 0 || streamType > 2) {
            throw new BadRequestException("streamType 取值必须是 0（主码流）/1（子码流）/2（第三码流），实际值：" + streamType);
        }
        int linkMode = request.getLinkMode() == null ? properties.getLinkMode() : request.getLinkMode();

        isupService.requirePreviewListen("NET_ECMS_StartGetRealStreamV11");
        DeviceInfo connection = deviceRegistry.requireConnection(deviceId, "预览");
        int lUserId = connection.getLUserId();

        MediaSession session = sessionManager.create(SessionType.PREVIEW, connection, channel);
        if (!session.tryBeginNativeOperation()) {
            throw new IllegalStateException("预览会话已取消，不能继续启动");
        }
        try {
            session.setLUserId(lUserId);

            Path dir = MediaPaths.previewDir(properties.getMediaDir(), session.getSessionId());
            session.setOutputDir(dir.toString());
            session.setPlayUrl("/media/preview/" + session.getSessionId() + "/index.m3u8");

            HCISUPCMS cms = isupService.cms();

            HCISUPCMS.NET_EHOME_PREVIEWINFO_IN_V11 previewIn = new HCISUPCMS.NET_EHOME_PREVIEWINFO_IN_V11();
            previewIn.iChannel = channel;
            previewIn.dwStreamType = streamType;
            previewIn.dwLinkMode = linkMode;
            copyTo(previewIn.struStreamSever.szIP, properties.getPublicIp());
            previewIn.struStreamSever.wPort = (short) properties.getStream().getPreviewPort();
            previewIn.write();

            HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT previewOut = new HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT();
            previewOut.write();

            if (!cms.NET_ECMS_StartGetRealStreamV11(lUserId, previewIn, previewOut)) {
                throw IsupException.sdkFailed("NET_ECMS_StartGetRealStreamV11", deviceId, channel,
                        session.getSessionId(), cms.NET_ECMS_GetLastError());
            }
            previewOut.read();
            int isupSessionId = previewOut.lSessionID;
            sessionManager.registerIsupSession(session, isupSessionId);
            checkStarting(session);
            log.info("NET_ECMS_StartGetRealStreamV11 成功：deviceId={}, channel={}, streamType={}, sessionId={}, isupSessionId={}",
                    deviceId, channel, streamType, session.getSessionId(), isupSessionId);

            session.setFfmpegProcess(ffmpegService.startHls(properties, dir, session.getSessionId()));
            checkStarting(session);

            HCISUPCMS.NET_EHOME_PUSHSTREAM_IN pushIn = new HCISUPCMS.NET_EHOME_PUSHSTREAM_IN();
            pushIn.read();
            pushIn.dwSize = pushIn.size();
            pushIn.lSessionID = isupSessionId;
            pushIn.write();

            HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT pushOut = new HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT();
            pushOut.read();
            pushOut.dwSize = pushOut.size();
            pushOut.write();

            if (!cms.NET_ECMS_StartPushRealStream(lUserId, pushIn, pushOut)) {
                throw IsupException.sdkFailed("NET_ECMS_StartPushRealStream", deviceId, channel,
                        session.getSessionId(), cms.NET_ECMS_GetLastError());
            }
            checkStarting(session);
            log.info("NET_ECMS_StartPushRealStream 成功：deviceId={}, channel={}, sessionId={}, isupSessionId={}",
                    deviceId, channel, session.getSessionId(), isupSessionId);

            StreamSessionResponse response = StreamSessionResponse.of(session.getSessionId(),
                    SessionType.PREVIEW, session.getStatus(), session.getPlayUrl());
            response.setDeviceId(deviceId);
            response.setChannel(channel);
            return response;
        } catch (RuntimeException e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            sessionManager.stop(session, SessionStatus.FAILED, e.getMessage());
            throw IsupException.wrap("启动实时预览", deviceId, channel, session.getSessionId(), e);
        } finally {
            sessionManager.endNativeOperation(session);
        }
    }

    private static void checkStarting(MediaSession session) {
        if (session.isStopping() || session.isFinished()) {
            throw new IllegalStateException("预览会话已取消，不能继续启动");
        }
    }
}
