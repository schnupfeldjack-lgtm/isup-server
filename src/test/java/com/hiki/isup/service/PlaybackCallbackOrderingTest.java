package com.hiki.isup.service;

import com.hiki.isup.callback.PlaybackDataCallback;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Data/EOS ordering regressions: preserve every admitted byte without runtime media validation. */
class PlaybackCallbackOrderingTest {
    private static final LocalDateTime WALL = LocalDateTime.of(2026, 10, 21, 9, 0);
    private static final long ORIGIN = 5_300_000_000L;
    @TempDir Path temp;

    @Test
    void endWaitsForAnEnteredDataCallbackBeforeClosingWriter() throws Exception {
        Captured result = endBeforeAdmission(new byte[]{1, 2, 3}, new byte[]{4, 5}, temp.resolve("race"));

        assertEquals(SessionStatus.COMPLETED, result.session().getStatus());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Files.readAllBytes(result.source()));
        assertEquals(1, result.tailReads(), "已进入回调的尾包必须读完并写盘");
        assertFalse(result.writeFailed(), "正常收尾不应产生写入失败");
        assertEquals(5, result.session().getBytesReceived(), "接收计数应与落盘字节一致");
        System.out.println("callback-order fixed: intendedBytes=5, writtenBytes=5, tailPointerReads=1");
    }

    @Test
    void concurrentDataCallbacksKeepArrivalOrder() throws Exception {
        IsupProperties properties = properties();
        SessionManager manager = new SessionManager(new NativeSdkLoader(), properties);
        MediaSession session = manager.create(SessionType.DOWNLOAD, "local-fixture", 2);
        Path raw = temp.resolve("order/source.ps");
        FfmpegProcess writer = new FfmpegService().startPackingCapture(properties, raw, session.getSessionId());
        session.setRawFilePath(raw.toString());
        session.setFfmpegProcess(writer);
        PlaybackDataCallback callback = new PlaybackDataCallback(session, manager);
        BlockingPointer first = new BlockingPointer(new byte[]{1, 2});
        FutureTask<Void> firstCall = launch(() -> send(callback, first, first.bytes.length), "first-data-copy");
        try {
            await(first.entered);
            CountDownLatch secondEntered = new CountDownLatch(1);
            FutureTask<Void> secondCall = launch(() -> {
                secondEntered.countDown();
                send(callback, new BytesPointer(new byte[]{3, 4}), 2);
            }, "second-data-copy");
            try {
                await(secondEntered);
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> secondCall.get(100, TimeUnit.MILLISECONDS),
                        "后到的数据不能越过正在复制的前包");
            } finally {
                first.release.countDown();
                firstCall.get(5, TimeUnit.SECONDS);
                secondCall.get(5, TimeUnit.SECONDS);
            }
            end(callback);
            assertTrue(session.awaitTerminal(5000));

            assertEquals(SessionStatus.COMPLETED, session.getStatus());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(raw),
                    "各 DATA 必须按回调入口顺序写入 FIFO");
            assertFalse(writer.isWriteFailed());
            System.out.println("callback-order evidence: entered=[1,2] then [3,4], written=[1,2,3,4], completed=true");
        } finally {
            first.release.countDown();
            firstCall.get(5, TimeUnit.SECONDS);
            manager.shutdown();
        }
    }

    @Test
    void orderedCallbacksKeepTheH264TailDecodable() throws Exception {
        String ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!ffmpeg.isBlank() && Files.isExecutable(Path.of(ffmpeg)), "requires isup.test.ffmpeg");
        Path h264 = temp.resolve("fixture.h264");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc2=size=64x64:rate=25:duration=6", "-c:v", "libx264", "-threads", "1", "-bf", "0",
                "-x264-params", "aud=1:keyint=25:min-keyint=25:scenecut=0:repeat-headers=1", "-f", "h264", h264.toString()), 30);
        List<byte[]> frames = accessUnits(Files.readAllBytes(h264));
        assertEquals(150, frames.size());
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        prefix.writeBytes(PsRecordingTimelineTest.pack());
        prefix.writeBytes(PsRecordingTimelineTest.psm(WALL));
        for (int i = 0; i < frames.size() - 1; i++) {
            prefix.writeBytes(PsRecordingTimelineTest.pesDts(0xe0, ORIGIN + i * 3600L,
                    ORIGIN + i * 3600L, frames.get(i)));
        }
        byte[] last = frames.getLast();
        int split = last.length / 2;
        long lastPts = ORIGIN + 149 * 3600L;
        // Both transport fragments are complete PES packets; losing the second leaves an incomplete H264 AU.
        prefix.writeBytes(PsRecordingTimelineTest.pesDts(0xe0, lastPts, lastPts, Arrays.copyOf(last, split)));
        byte[] tail = PsRecordingTimelineTest.join(PsRecordingTimelineTest.pesDts(0xe0, lastPts, lastPts,
                Arrays.copyOfRange(last, split, last.length)), new byte[]{0, 0, 1, (byte) 0xb9});
        byte[] intended = PsRecordingTimelineTest.join(prefix.toByteArray(), tail);

        Path control = temp.resolve("control/source.ps");
        Files.createDirectories(control.getParent());
        Files.write(control, intended);
        IsupProperties properties = properties();
        properties.setFfmpegPath(ffmpeg);
        MediaSession clean = mediaSession("clean", control);
        assertNotNull(new RecordingRemuxer(properties).remux(clean, WALL, WALL.plusSeconds(5), RecordingDeadline.afterSeconds(30)),
                "所有 DATA 按序接纳时同一原始 payload 必须可以完整解码");

        Captured raced = endBeforeAdmission(prefix.toByteArray(), tail, temp.resolve("media-race"));
        assertEquals(SessionStatus.COMPLETED, raced.session().getStatus(), "复现的是下载层已完成，不是业务 SUCCESS");
        byte[] before = Files.readAllBytes(raced.source());
        assertArrayEquals(intended, before, "回调排序修复后保留原始码流的全部字节");
        assertFalse(raced.writeFailed());
        assertNotNull(new RecordingRemuxer(properties)
                .remux(raced.session(), WALL, WALL.plusSeconds(5), RecordingDeadline.afterSeconds(30)));
        assertArrayEquals(before, Files.readAllBytes(raced.source()));

    }


    @Test
    void orderedCallbacksPreserveRealHevcPsTailWithoutTranscoding() throws Exception {
        String source = System.getProperty("isup.test.hevcSource", "");
        String ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!source.isBlank() && !ffmpeg.isBlank(), "requires real HEVC fixture and ffmpeg");
        byte[] intended = Files.readAllBytes(Path.of(source));
        int lastVideo = -1;
        for (int i = 0; i + 6 < intended.length; i++) {
            if (intended[i] == 0 && intended[i + 1] == 0 && intended[i + 2] == 1
                    && (intended[i + 3] & 255) == 0xe0) lastVideo = i;
        }
        assertTrue(lastVideo >= 0, "实际 H265 样本须包含 PS 视频 PES");
        int pesLength = ((intended[lastVideo + 4] & 255) << 8) | (intended[lastVideo + 5] & 255);
        int split = lastVideo + 6 + pesLength / 2;
        assertTrue(pesLength > 0 && split < intended.length);
        Captured captured = endBeforeAdmission(Arrays.copyOf(intended, split),
                Arrays.copyOfRange(intended, split, intended.length), temp.resolve("hevc-race"));
        assertArrayEquals(intended, Files.readAllBytes(captured.source()), "H265 尾包不能被结束信令截掉");
        assertFalse(captured.writeFailed());
        Path output = temp.resolve("hevc-fixed.mp4");
        Process mux = new ProcessBuilder(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-f", "mpeg", "-i", captured.source().toString(), "-map", "0:v:0", "-c:v", "copy", "-an",
                "-movflags", "+frag_keyframe+empty_moov", output.toString())
                .redirectError(temp.resolve("hevc-remux.log").toFile()).start();
        try {
            assertTrue(mux.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, mux.exitValue(), Files.readString(temp.resolve("hevc-remux.log")));
            assertTrue(Files.size(output) > 0);
            String evidence = System.getProperty("isup.test.tailEvidenceDir", "");
            if (!evidence.isBlank()) {
                Path directory = Path.of(evidence); Files.createDirectories(directory);
                Files.copy(captured.source(), directory.resolve("ordered-hevc-source.ps"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.copy(output, directory.resolve("ordered-hevc.mp4"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { mux.destroyForcibly(); }
        System.out.println("real-HEVC callback replay: " + intended.length + " bytes preserved; -c:v copy");
    }

    @Test
    void aBlockedCallbackDoesNotBlockAnotherRecording() throws Exception {
        IsupProperties properties = properties();
        SessionManager manager = new SessionManager(new NativeSdkLoader(), properties);
        MediaSession first = manager.create(SessionType.DOWNLOAD, "camera-one", 1);
        MediaSession second = manager.create(SessionType.DOWNLOAD, "camera-two", 1);
        Path firstRaw = temp.resolve("independent/first.ps");
        Path secondRaw = temp.resolve("independent/second.ps");
        first.setRawFilePath(firstRaw.toString());
        second.setRawFilePath(secondRaw.toString());
        first.setFfmpegProcess(new FfmpegService().startPackingCapture(properties, firstRaw, first.getSessionId()));
        second.setFfmpegProcess(new FfmpegService().startPackingCapture(properties, secondRaw, second.getSessionId()));
        PlaybackDataCallback firstCallback = new PlaybackDataCallback(first, manager);
        PlaybackDataCallback secondCallback = new PlaybackDataCallback(second, manager);
        BlockingPointer blocked = new BlockingPointer(new byte[]{1, 2});
        FutureTask<Void> firstCall = launch(() -> send(firstCallback, blocked, 2), "blocked-camera-one");
        try {
            await(blocked.entered);
            FutureTask<Void> secondCall = launch(() -> {
                send(secondCallback, new BytesPointer(new byte[]{3, 4}), 2);
                end(secondCallback);
            }, "independent-camera-two");
            secondCall.get(1, TimeUnit.SECONDS);
            assertTrue(second.awaitTerminal(2000));
            assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(secondRaw));
            assertFalse(first.isFinished(), "另一条录像完成时第一路仍在复制");
        } finally {
            blocked.release.countDown();
            firstCall.get(5, TimeUnit.SECONDS);
            end(firstCallback);
            manager.shutdown();
        }
    }

    private Captured endBeforeAdmission(byte[] prefix, byte[] tail, Path directory) throws Exception {
        IsupProperties properties = properties();
        SessionManager manager = new SessionManager(new NativeSdkLoader(), properties);
        MediaSession registered = manager.create(SessionType.DOWNLOAD, "local-fixture", 2);
        GatedSession session = new GatedSession(registered.getSessionId());
        @SuppressWarnings("unchecked")
        Map<String, MediaSession> sessions = (Map<String, MediaSession>) ReflectionTestUtils.getField(manager, "sessions");
        assertNotNull(sessions);
        // Keep the real lifecycle manager and its reserved slot; only the test's getter adds a deterministic barrier.
        sessions.put(session.getSessionId(), session);
        Path raw = directory.resolve("source.ps");
        FfmpegProcess writer = new FfmpegService().startPackingCapture(properties, raw, session.getSessionId());
        session.setRawFilePath(raw.toString());
        session.setFfmpegProcess(writer);
        session.setStartTime(WALL.minusSeconds(5));
        session.setEndTime(WALL.plusSeconds(20));
        PlaybackDataCallback callback = new PlaybackDataCallback(session, manager);
        for (int offset = 0; offset < prefix.length; offset += 65_536) {
            byte[] part = Arrays.copyOfRange(prefix, offset, Math.min(prefix.length, offset + 65_536));
            send(callback, new BytesPointer(part), part.length);
        }
        BytesPointer late = new BytesPointer(tail);
        FutureTask<Void> lateCall = launch(() -> {
            session.gatedThread = Thread.currentThread();
            send(callback, late, tail.length);
        }, "entered-data-before-admission");
        try {
            await(session.entered);
            assertFalse(session.isStopping(), "DATA 已通过 stopping 检查，尚未获得 writer admission");
            CountDownLatch endEntered = new CountDownLatch(1);
            FutureTask<Void> endCall = launch(() -> {
                endEntered.countDown();
                end(callback);
            }, "end-after-entered-data");
            try {
                await(endEntered);
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> endCall.get(100, TimeUnit.MILLISECONDS),
                        "结束信令必须等已经进入的 DATA 写入队列");
            } finally {
                session.release.countDown();
                lateCall.get(5, TimeUnit.SECONDS);
                endCall.get(5, TimeUnit.SECONDS);
            }
            assertTrue(session.awaitTerminal(5000));
            assertEquals(SessionStatus.COMPLETED, session.getStatus());
            return new Captured(session, raw, late.reads.get(), writer.isWriteFailed());
        } finally {
            session.release.countDown();
            lateCall.get(5, TimeUnit.SECONDS);
            manager.shutdown();
        }
    }

    private static MediaSession mediaSession(String id, Path raw) {
        MediaSession session = new MediaSession(id, SessionType.DOWNLOAD, "local-fixture", 2);
        session.setRawFilePath(raw.toString());
        session.setStartTime(WALL.minusSeconds(5));
        session.setEndTime(WALL.plusSeconds(20));
        return session;
    }

    private static IsupProperties properties() {
        IsupProperties properties = new IsupProperties();
        properties.getSession().setStartTimeoutSec(3600);
        properties.getSession().setIdleTimeoutSec(3600);
        properties.getSession().setDownloadMaxSec(3600);
        return properties;
    }

    private static void send(PlaybackDataCallback callback, Pointer pointer, int length) {
        HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO info = new HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO();
        info.dwType = 2; info.pData = pointer; info.dwDataLen = length;
        assertTrue(callback.invoke(7, info, null));
    }

    private static void end(PlaybackDataCallback callback) {
        HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO info = new HCISUPStream.NET_EHOME_PLAYBACK_DATA_CB_INFO();
        info.dwType = HCISUPStream.NET_EHOME_STREAMEND;
        assertTrue(callback.invoke(7, info, null));
    }

    private static FutureTask<Void> launch(Runnable action, String name) {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start();
        return task;
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "测试屏障必须有界释放"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static List<byte[]> accessUnits(byte[] bytes) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < bytes.length; i++) {
            if (bytes[i] != 0 || bytes[i + 1] != 0) continue;
            int prefix = bytes[i + 2] == 1 ? 3 : bytes[i + 2] == 0 && bytes[i + 3] == 1 ? 4 : 0;
            if (prefix > 0) { if ((bytes[i + prefix] & 31) == 9) starts.add(i); i += prefix - 1; }
        }
        List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            result.add(Arrays.copyOfRange(bytes, starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : bytes.length));
        }
        return result;
    }

    private record Captured(MediaSession session, Path source, int tailReads, boolean writeFailed) { }

    private static final class GatedSession extends MediaSession {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread gatedThread;
        GatedSession(String id) { super(id, SessionType.DOWNLOAD, "local-fixture", 2); }
        @Override public FfmpegProcess getFfmpegProcess() {
            FfmpegProcess captured = super.getFfmpegProcess();
            if (Thread.currentThread() == gatedThread) { entered.countDown(); await(release); }
            return captured;
        }
    }

    private static class BytesPointer extends Pointer {
        final byte[] bytes;
        final AtomicInteger reads = new AtomicInteger();
        BytesPointer(byte[] bytes) { super(0); this.bytes = bytes; }
        @Override public void read(long offset, byte[] target, int index, int length) {
            reads.incrementAndGet(); System.arraycopy(bytes, (int) offset, target, index, length);
        }
    }

    private static final class BlockingPointer extends BytesPointer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        BlockingPointer(byte[] bytes) { super(bytes); }
        @Override public void read(long offset, byte[] target, int index, int length) {
            entered.countDown(); await(release); super.read(offset, target, index, length);
        }
    }
}
