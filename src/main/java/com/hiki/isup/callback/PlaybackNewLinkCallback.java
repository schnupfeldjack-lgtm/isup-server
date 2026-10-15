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
 * <p>设备回连 8661 端口后触发。回放与下载共用这一个监听端口与回调，
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

            PlaybackDataCallback dataCallback = new PlaybackDataCallback(session, sessionManager);
            if (!session.tryBeginLinkRegistration(lPlayBackLinkHandle, dataCallback,
                    () -> handlers.remove(lPlayBackLinkHandle, dataCallback))) {
                return false;
            }
            try {
                handlers.put(lPlayBackLinkHandle, dataCallback);
                if (isupSessionId >= 0) {
                    sessionManager.registerIsupSession(session, isupSessionId);
                }

                HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_PARAM param = new HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_PARAM();
                param.fnPlayBackDataCB = dataCallback;
                param.byStreamFormat = 0;   // 0-PS 封装，交给 ffmpeg 解封装
                param.write();

                if (!sdkLoader.stream().NET_ESTREAM_SetPlayBackDataCB(lPlayBackLinkHandle, param)) {
                    log.error("NET_ESTREAM_SetPlayBackDataCB 失败：operation=PlaybackNewLink, deviceId={}, channel={}, sessionId={}, handle={}, errorCode={}",
                            session.getDeviceId(), session.getChannel(), session.getSessionId(), lPlayBackLinkHandle,
                            sdkLoader.stream().NET_ESTREAM_GetLastError());
                    sessionManager.stopAsync(session, SessionStatus.FAILED, "注册回放数据回调失败");
                    return false;
                }

                if (session.isStopping() || session.isFinished()) {
                    return false;
                }

                if (session.getStatus() == SessionStatus.STARTING) {
                    session.setStatus(SessionStatus.RUNNING);
                }
                log.info("回放链路已建立：type={}, deviceId={}, channel={}, sessionId={}, handle={}, isupSessionId={}",
                        session.getType(), session.getDeviceId(), session.getChannel(),
                        session.getSessionId(), lPlayBackLinkHandle, isupSessionId);
                return true;
            } finally {
                sessionManager.endNativeOperation(session);
            }
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
        MediaSession playback = sessionManager.findByIsupSessionId(SessionType.PLAYBACK, isupSessionId);
        MediaSession download = sessionManager.findByIsupSessionId(SessionType.DOWNLOAD, isupSessionId);
        boolean playbackActive = playback != null && !playback.isStopping() && !playback.isFinished();
        boolean downloadActive = download != null && !download.isStopping() && !download.isFinished();
        if (playbackActive || downloadActive) {
            // SDK 可重新使用已结束会话的 ID；另一类型的终态别名不能遮蔽当前 exact。
            return playbackActive == downloadActive ? null : (playbackActive ? playback : download);
        }
        if (playback != null || download != null) {
            // 已知旧 ID 交给 invoke 拒绝，绝不能兜底到下一单。
            return playback != null ? playback : download;
        }
        MediaSession session = sessionManager.findPendingPlaybackByDeviceChannel(deviceId, channel);
        if (session != null && isupSessionId >= 0 && session.getIsupSessionId() >= 0
                && session.getIsupSessionId() != isupSessionId) {
            // 已有可靠 ID 的新任务，不能被另一条迟到连接覆盖。
            return null;
        }
        return session;
    }

    void release(int handle) {
        handlers.remove(handle);
    }
}
