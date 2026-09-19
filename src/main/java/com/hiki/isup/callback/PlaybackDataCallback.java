package com.hiki.isup.callback;

import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.service.FfmpegProcess;
import com.hiki.isup.service.SessionManager;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回放 / 下载码流数据回调。
 *
 * <ul>
 *   <li>dwType=1：码流头</li>
 *   <li>dwType=2：码流数据</li>
 *   <li>dwType=3：回放结束信令（下载任务到此收尾）</li>
 * </ul>
 */
public class PlaybackDataCallback implements HCISUPStream.PLAYBACK_DATA_CB {

    private static final Logger log = LoggerFactory.getLogger(PlaybackDataCallback.class);

    private final MediaSession session;
    private final SessionManager sessionManager;
    private volatile long totalBytes;
    private volatile boolean endHandled;

    public PlaybackDataCallback(MediaSession session, SessionManager sessionManager) {
        this.session = session;
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean invoke(int iPlayBackLinkHandle,
                          HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO pDataCBInfo,
                          Pointer pUserData) {
        try {
            int type = pDataCBInfo.dwType;

            if (type == HCISUPStream.NET_EHOME_STREAMEND || type == 3) {
                handleEnd(iPlayBackLinkHandle);
                return true;
            }
            if (session.isStopping() || session.isFinished()) {
                return true;
            }
            if (pDataCBInfo.pData == null || pDataCBInfo.dwDataLen <= 0) {
                return true;
            }

            byte[] data = pDataCBInfo.pData.getByteArray(0, pDataCBInfo.dwDataLen);
            FfmpegProcess ffmpeg = session.getFfmpegProcess();
            if (ffmpeg != null) {
                ffmpeg.write(data, 0, data.length);
            }
            totalBytes += data.length;
            session.touchData(data.length);

            if (session.getStatus() == SessionStatus.STARTING) {
                session.setStatus(SessionStatus.RUNNING);
                log.info("{} 首帧到达：deviceId={}, channel={}, sessionId={}, handle={}, bytes={}",
                        session.getType(), session.getDeviceId(), session.getChannel(),
                        session.getSessionId(), iPlayBackLinkHandle, data.length);
            }
        } catch (Exception e) {
            log.error("处理回放码流异常：operation=PlaybackCallback, deviceId={}, channel={}, sessionId={}, handle={}",
                    session.getDeviceId(), session.getChannel(), session.getSessionId(), iPlayBackLinkHandle, e);
        }
        return true;
    }

    private void handleEnd(int handle) {
        if (!endHandled) {
            endHandled = true;
            log.info("收到回放结束信令：operation=PlaybackEnd, deviceId={}, channel={}, sessionId={}, handle={}, totalBytes={}",
                    session.getDeviceId(), session.getChannel(), session.getSessionId(), handle, totalBytes);
        }
        if (session.isFinished()) {
            return;
        }
        // 注意：当前处于 SDK 回调线程，必须异步停止，不能在回调里重入 SDK
        if (session.getType() == SessionType.DOWNLOAD) {
            sessionManager.finishDownloadAsync(session, null);
        } else {
            sessionManager.stopAsync(session, SessionStatus.COMPLETED, "回放结束");
        }
    }

    public long getTotalBytes() {
        return totalBytes;
    }
}
