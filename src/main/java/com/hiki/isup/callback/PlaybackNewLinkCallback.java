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
 * 回放 / 下载新连接回调（NET_ESTREAM_StartListenPlayBack 传入）。
 *
 * <p>设备回连 8004 端口后触发。回放与下载共用这一个监听端口与回调，
 * 通过会话类型区分后续处理方式。</p>
 */
@Component
public class PlaybackNewLinkCallback implements HCISUPStream.PLAYBACK_NEWLINK_CB {

    private static final Logger log = LoggerFactory.getLogger(PlaybackNewLinkCallback.class);

    private final SessionManager sessionManager;
    private final NativeSdkLoader sdkLoader;

    /** handle -> 数据回调（额外强引用，双保险防止 JNA callback 被 GC） */
    private final ConcurrentHashMap<Integer, PlaybackDataCallback> handlers = new ConcurrentHashMap<>();

    public PlaybackNewLinkCallback(SessionManager sessionManager, NativeSdkLoader sdkLoader) {
        this.sessionManager = sessionManager;
        this.sdkLoader = sdkLoader;
    }

    @Override
    public boolean invoke(int lPlayBackLinkHandle,
                          HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO pNewLinkCBMsg,
                          Pointer pUserData) {
        try {
            int isupSessionId = pNewLinkCBMsg.lSessionID;
            String deviceId = readString(pNewLinkCBMsg.szDeviceID);
            int channel = pNewLinkCBMsg.dwChannelNo;

            MediaSession session = resolveSession(isupSessionId, deviceId, channel);
            if (session == null) {
                log.error("回放回调找不到对应会话：operation=PlaybackNewLink, deviceId={}, channel={}, isupSessionId={}, handle={}",
                        deviceId, channel, isupSessionId, lPlayBackLinkHandle);
                return false;
            }

            session.setLinkHandle(lPlayBackLinkHandle);

            PlaybackDataCallback dataCallback = new PlaybackDataCallback(session, sessionManager);
            session.setDataCallback(dataCallback);
            handlers.put(lPlayBackLinkHandle, dataCallback);
            session.setReleaseHook(() -> handlers.remove(lPlayBackLinkHandle));

            HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_PARAM param = new HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_PARAM();
            param.fnPlayBackDataCB = dataCallback;
            param.byStreamFormat = 0;   // 0-PS 封装，交给 ffmpeg 解封装
            param.write();

            if (!sdkLoader.stream().NET_ESTREAM_SetPlayBackDataCB(lPlayBackLinkHandle, param)) {
                log.error("NET_ESTREAM_SetPlayBackDataCB 失败：operation=PlaybackNewLink, deviceId={}, channel={}, sessionId={}, handle={}, errorCode={}",
                        session.getDeviceId(), session.getChannel(), session.getSessionId(), lPlayBackLinkHandle,
                        sdkLoader.stream().NET_ESTREAM_GetLastError());
                return false;
            }

            if (session.getStatus() == SessionStatus.STARTING) {
                session.setStatus(SessionStatus.RUNNING);
            }
            log.info("回放链路已建立：type={}, deviceId={}, channel={}, sessionId={}, handle={}, isupSessionId={}",
                    session.getType(), session.getDeviceId(), session.getChannel(),
                    session.getSessionId(), lPlayBackLinkHandle, isupSessionId);
            return true;
        } catch (Exception e) {
            log.error("处理回放新连接回调异常：handle={}", lPlayBackLinkHandle, e);
            return false;
        }
    }

    /**
     * 优先用 ISUP 会话 ID 精确匹配；SDK 返回的 lSessionID 无效时，
     * 按 deviceId + channel 匹配唯一的待接流会话。
     */
    private MediaSession resolveSession(int isupSessionId, String deviceId, int channel) {
        MediaSession session = sessionManager.findByIsupSessionId(SessionType.PLAYBACK, isupSessionId);
        if (session == null) {
            session = sessionManager.findByIsupSessionId(SessionType.DOWNLOAD, isupSessionId);
        }
        if (session != null) {
            return session;
        }
        session = sessionManager.findPendingByDeviceChannel(SessionType.PLAYBACK, deviceId, channel);
        if (session == null) {
            session = sessionManager.findPendingByDeviceChannel(SessionType.DOWNLOAD, deviceId, channel);
        }
        if (session != null && isupSessionId >= 0) {
            sessionManager.registerIsupSession(session, isupSessionId);
            log.info("回放链路改用设备实际会话 ID 关联：sessionId={}, isupSessionId={}",
                    session.getSessionId(), isupSessionId);
        }
        return session;
    }

    void release(int handle) {
        handlers.remove(handle);
    }
}
