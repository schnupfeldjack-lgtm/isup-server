package com.hiki.isup.callback;

import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.service.FfmpegProcess;
import com.hiki.isup.service.HikStreamClock;
import com.hiki.isup.service.SessionManager;
import com.sun.jna.Memory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PlaybackDataCallbackTest {

    @Test void manualDownloadOversizedPointerIsRejectedBeforeAnyNativeRead(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)
            throws Exception {
        MediaSession session = session(SessionType.DOWNLOAD);
        FfmpegProcess writer = new com.hiki.isup.service.FfmpegService().startPackingCapture(
                new com.hiki.isup.config.IsupProperties(), temp.resolve("session/source.ps"), "manual-pointer");
        session.setFfmpegProcess(writer);
        com.sun.jna.Pointer pointer = mock(com.sun.jna.Pointer.class);
        HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO data = new HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO();
        data.dwType = 2; data.pData = pointer; data.dwDataLen = 1024 * 1024 + 1;
        try {
            new PlaybackDataCallback(session, mock(SessionManager.class)).invoke(7, data, null);
            assertTrue(writer.isWriteFailed());
            verifyNoInteractions(pointer);
        } finally { writer.destroy(); }
    }

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 13, 15, 0, 0);

    @Test
    void rawPackingCaptureUsesPooledPointerCopyWithoutUnusedRealtimeClockScan() {
        MediaSession session = session(SessionType.DOWNLOAD);
        session.setRawFilePath("recording-source.ps");
        FfmpegProcess writer = mock(FfmpegProcess.class);
        session.setFfmpegProcess(writer);
        PlaybackDataCallback callback = new PlaybackDataCallback(session, mock(SessionManager.class));
        send(callback, descriptor(START));
        assertNull(session.streamClock().firstFrame(), "打包录像统一在原始 PS 中解析时间轴，不重复扫描回调包");
        verify(writer).write(any(com.sun.jna.Pointer.class), eq(12));
        org.mockito.Mockito.verify(writer, org.mockito.Mockito.never()).write(any(byte[].class), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        assertEquals(12, session.getBytesReceived());
    }

    @Test
    @DisplayName("手动下载也使用有界指针路径，不做没有消费者的回调时钟扫描")
    void manualDownloadUsesBoundedPointerPathWithoutUnusedClockScan() {
        MediaSession session = session(SessionType.DOWNLOAD);
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        session.setFfmpegProcess(ffmpeg);
        SessionManager manager = mock(SessionManager.class);
        PlaybackDataCallback callback = new PlaybackDataCallback(session, manager);

        send(callback, descriptor(START));
        send(callback, descriptor(START.plusSeconds(53)));

        assertNull(session.streamClock().firstFrame());
        assertNull(lastFrame(session.streamClock()));
        assertEquals(24, callback.getTotalBytes());
        assertEquals(24, session.getBytesReceived());
        assertEquals(SessionStatus.RUNNING, session.getStatus());
        verify(ffmpeg, times(2)).write(any(com.sun.jna.Pointer.class), eq(12));
        org.mockito.Mockito.verify(ffmpeg, org.mockito.Mockito.never()).write(any(byte[].class), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(manager);
    }

    @Test
    @DisplayName("停止中的下载忽略迟到数据，不推进已接收字节数")
    void stoppingDownloadIgnoresLatePackets() {
        MediaSession session = session(SessionType.DOWNLOAD);
        PlaybackDataCallback callback = new PlaybackDataCallback(session, mock(SessionManager.class));
        send(callback, descriptor(START));
        assertTrue(session.markStopping());
        send(callback, descriptor(START.plusSeconds(53)));

        assertNull(session.streamClock().firstFrame());
        assertNull(lastFrame(session.streamClock()));
        assertEquals(12, session.getBytesReceived());
    }

    @Test
    @DisplayName("普通回放仍按原路径传送码流，不参与下载时钟收集")
    void playbackDoesNotCollectDownloadClock() {
        MediaSession session = session(SessionType.PLAYBACK);
        PlaybackDataCallback callback = new PlaybackDataCallback(session, mock(SessionManager.class));
        send(callback, descriptor(START));

        assertNull(session.streamClock().firstFrame());
        assertEquals(12, session.getBytesReceived());
        assertEquals(SessionStatus.RUNNING, session.getStatus());
    }

    private static MediaSession session(SessionType type) {
        MediaSession session = new MediaSession("download-clock", type, "camera-1", 2);
        session.setStartTime(START);
        session.setEndTime(START.plusSeconds(53));
        return session;
    }

    private static void send(PlaybackDataCallback callback, byte[] packet) {
        try (Memory memory = new Memory(packet.length)) {
            memory.write(0, packet, 0, packet.length);
            HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO data =
                    new HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO();
            data.dwType = 2;
            data.pData = memory;
            data.dwDataLen = packet.length;
            assertTrue(callback.invoke(7, data, null));
        }
    }

    private static LocalDateTime lastFrame(HikStreamClock clock) {
        return (LocalDateTime) assertDoesNotThrow(
                () -> HikStreamClock.class.getMethod("lastFrame").invoke(clock),
                "下载时钟必须公开末帧时间");
    }

    private static byte[] descriptor(LocalDateTime time) {
        byte[] data = new byte[12];
        int minute = time.getMinute();
        int second = time.getSecond();
        data[2] = 0x48;
        data[3] = 0x4B;
        data[6] = (byte) (time.getYear() - 2000);
        data[7] = (byte) ((time.getMonthValue() << 4) | ((time.getDayOfMonth() >> 1) & 0x0f));
        data[8] = (byte) (((time.getDayOfMonth() & 1) << 7) | (time.getHour() << 2) | (minute >> 4));
        data[9] = (byte) (((minute & 0x0f) << 4) | (second >> 2));
        data[10] = (byte) ((second & 0x03) << 6);
        return data;
    }
}
