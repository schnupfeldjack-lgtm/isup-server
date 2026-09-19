package com.hiki.isup.callback;

import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.service.FfmpegProcess;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 预览码流数据回调：把设备推来的 PS 码流直接写进 ffmpeg stdin。
 *
 * <p>每个会话一个实例，并由 MediaSession 保持强引用，防止被 GC 后 native 回调崩溃。</p>
 */
public class PreviewDataCallback implements HCISUPStream.PREVIEW_DATA_CB {

    private static final Logger log = LoggerFactory.getLogger(PreviewDataCallback.class);

    private final MediaSession session;
    private volatile long totalBytes;
    private volatile boolean firstDataLogged;

    public PreviewDataCallback(MediaSession session) {
        this.session = session;
    }

    @Override
    public void invoke(int iPreviewHandle, HCISUPStream.NET_EHOME_PREVIEW_CB_MSG pPreviewCBMsg, Pointer pUserData) {
        try {
            if (session.isStopping() || session.isFinished()) {
                return;
            }
            byte dataType = pPreviewCBMsg.byDataType;
            if (dataType != HCISUPStream.NET_EHOME_SYSHEAD && dataType != HCISUPStream.NET_EHOME_STREAMDATA) {
                return;
            }
            if (pPreviewCBMsg.pRecvdata == null || pPreviewCBMsg.dwDataLen <= 0) {
                return;
            }
            byte[] data = pPreviewCBMsg.pRecvdata.getByteArray(0, pPreviewCBMsg.dwDataLen);

            FfmpegProcess ffmpeg = session.getFfmpegProcess();
            if (ffmpeg != null) {
                ffmpeg.write(data, 0, data.length);
            }
            totalBytes += data.length;
            session.touchData(data.length);

            if (!firstDataLogged) {
                firstDataLogged = true;
                log.info("预览首帧到达：operation=Preview, deviceId={}, channel={}, sessionId={}, handle={}, bytes={}",
                        session.getDeviceId(), session.getChannel(), session.getSessionId(), iPreviewHandle, data.length);
            }
            if (session.getStatus() == SessionStatus.STARTING) {
                session.setStatus(SessionStatus.RUNNING);
            }
        } catch (Exception e) {
            log.error("处理预览码流异常：operation=PreviewCallback, deviceId={}, channel={}, sessionId={}, handle={}",
                    session.getDeviceId(), session.getChannel(), session.getSessionId(), iPreviewHandle, e);
        }
    }

    public long getTotalBytes() {
        return totalBytes;
    }
}
