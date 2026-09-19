package com.hiki.isup.callback;

import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.hiki.isup.service.SessionManager;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

import static com.hiki.isup.sdk.NativeSdkLoader.readString;

/**
 * 预览新连接回调（NET_ESTREAM_StartListenPreview 传入）。
 *
 * <p>设备回连 8003 端口后触发，在这里把链路句柄与会话绑定，并挂上数据回调。</p>
 */
@Component
public class PreviewNewLinkCallback implements HCISUPStream.PREVIEW_NEWLINK_CB {

    private static final Logger log = LoggerFactory.getLogger(PreviewNewLinkCallback.class);

    private final SessionManager sessionManager;
    private final NativeSdkLoader sdkLoader;

    /** handle -> 数据回调（额外强引用，双保险防止 JNA callback 被 GC） */
    private final ConcurrentHashMap<Integer, PreviewDataCallback> handlers = new ConcurrentHashMap<>();

    public PreviewNewLinkCallback(SessionManager sessionManager, NativeSdkLoader sdkLoader) {
        this.sessionManager = sessionManager;
        this.sdkLoader = sdkLoader;
    }

    @Override
    public boolean invoke(int lPreviewHandle, HCISUPStream.NET_EHOME_NEWLINK_CB_MSG pNewLinkCBMsg, Pointer pUserData) {
        try {
            int isupSessionId = pNewLinkCBMsg.iSessionID;
            String deviceId = readString(pNewLinkCBMsg.szDeviceID);
            int channel = pNewLinkCBMsg.dwChannelNo;

            MediaSession session = sessionManager.findByIsupSessionId(SessionType.PREVIEW, isupSessionId);
            if (session == null) {
                // 兜底：按设备 + 通道匹配唯一待接流会话
                session = sessionManager.findPendingByDeviceChannel(SessionType.PREVIEW, deviceId, channel);
                if (session != null) {
                    sessionManager.registerIsupSession(session, isupSessionId);
                }
            }
            if (session == null) {
                log.error("预览回调找不到对应会话：operation=PreviewNewLink, deviceId={}, channel={}, isupSessionId={}, handle={}",
                        deviceId, channel, isupSessionId, lPreviewHandle);
                return false;
            }

            session.setLinkHandle(lPreviewHandle);

            PreviewDataCallback dataCallback = new PreviewDataCallback(session);
            session.setDataCallback(dataCallback);
            handlers.put(lPreviewHandle, dataCallback);
            session.setReleaseHook(() -> handlers.remove(lPreviewHandle));

            HCISUPStream.NET_EHOME_PREVIEW_DATA_CB_PARAM param = new HCISUPStream.NET_EHOME_PREVIEW_DATA_CB_PARAM();
            param.fnPreviewDataCB = dataCallback;
            param.write();

            if (!sdkLoader.stream().NET_ESTREAM_SetPreviewDataCB(lPreviewHandle, param)) {
                log.error("NET_ESTREAM_SetPreviewDataCB 失败：operation=PreviewNewLink, deviceId={}, channel={}, sessionId={}, handle={}, errorCode={}",
                        session.getDeviceId(), session.getChannel(), session.getSessionId(), lPreviewHandle,
                        sdkLoader.stream().NET_ESTREAM_GetLastError());
                return false;
            }

            if (session.getStatus() == SessionStatus.STARTING) {
                session.setStatus(SessionStatus.RUNNING);
            }
            log.info("预览链路已建立：deviceId={}, channel={}, sessionId={}, handle={}, isupSessionId={}",
                    session.getDeviceId(), session.getChannel(), session.getSessionId(), lPreviewHandle, isupSessionId);
            return true;
        } catch (Exception e) {
            log.error("处理预览新连接回调异常：handle={}", lPreviewHandle, e);
            return false;
        }
    }
}
