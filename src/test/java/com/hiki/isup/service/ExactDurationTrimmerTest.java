package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionType;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExactDurationTrimmerTest {

    @TempDir
    Path tempDir;

    @Test void timelineEncoderHasAHardOutputFileLimit() {
        List<String> command = ExactDurationTrimmer.timelineTrimCommand("ffmpeg", 1,
                Path.of("raw.ps"), Path.of("out.mp4"), 1000, 1002, 1000, .05);
        assertTrue(command.contains("-fs"), "仅监视空间水位不能限制单个FFmpeg输出");
        assertEquals(Long.toString(2L * 1024 * 1024 * 1024), command.get(command.indexOf("-fs") + 1));
    }

    @Test void sharedDeadlineIncludesWaitingForTheCpuSlot() throws Exception {
        ExactDurationTrimmer trimmer = new ExactDurationTrimmer(new IsupProperties());
        var field = ExactDurationTrimmer.class.getDeclaredField("cutSlot");
        field.setAccessible(true);
        Semaphore slot = (Semaphore) field.get(trimmer);
        slot.acquire();
        MediaSession session = new MediaSession("waiting", SessionType.DOWNLOAD, "fixture", 1);
        session.setRawFilePath(tempDir.resolve("waiting.ps").toString());
        session.setFilePath(tempDir.resolve("waiting.mp4").toString());
        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 13, 37, 50);
        try {
            var method = ExactDurationTrimmer.class.getMethod("trim", MediaSession.class,
                    LocalDateTime.class, LocalDateTime.class, long.class);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> method.invoke(trimmer, session, start, start.plusSeconds(2),
                                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
                assertTrue(error.getCause() instanceof java.net.SocketTimeoutException, error.getCause().toString());
            });
        } finally { slot.release(); }
    }

    @Test void processUsesTheRemainingBudgetInsteadOfRestartingIt() throws Exception {
        var method = ExactDurationTrimmer.class.getDeclaredMethod("runUntil", List.class, long.class, Path.class);
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
        Thread.sleep(200);
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(null, List.of(javaCommand, "-version"), deadline, tempDir));
        assertTrue(error.getCause() instanceof java.net.SocketTimeoutException, error.getCause().toString());
    }

    @Test
    void durationValidationRejectsMissingOrTruncatedVideo() {
        assertThrows(IOException.class, () -> ExactDurationTrimmer.validateVideoProgress("progress=end", 57));
        assertThrows(IOException.class, () -> ExactDurationTrimmer.validateVideoProgress(
                "out_time_us=55000000\nprogress=end", 57));
        assertThrows(IOException.class, () -> ExactDurationTrimmer.validateVideoProgress(
                "out_time_us=57000000\nprogress=continue", 57));
    }

    @Test
    void commandTimeoutCoversBlockedOutputRead() {
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(6), () ->
                assertThrows(IOException.class, () -> ExactDurationTrimmer.run(List.of(javaCommand, "-cp",
                        System.getProperty("java.class.path"), SleepingCommand.class.getName()), 1)));
    }

    public static class SleepingCommand {
        public static void main(String[] args) throws Exception {
            System.out.println("started");
            Thread.sleep(30_000);
        }
    }

    @Test
    void firstAndLastWallClocksAloneCannotAuthorizeEitherChannel() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 9, 58, 9);
        for (int channel : new int[]{1, 2}) {
            int leadingSeconds = channel == 1 ? 13 : 14;
            MediaSession session = recording(channel, start.minusSeconds(leadingSeconds), start.plusSeconds(62));
            AtomicReference<Double> offset = new AtomicReference<>();
            AtomicReference<Double> duration = new AtomicReference<>();
            ExactDurationTrimmer trimmer = new ExactDurationTrimmer(new IsupProperties(), (in, out, off, len) -> {
                offset.set(off); duration.set(len); Files.write(out, new byte[]{9});
            });
            assertThrows(IOException.class, () -> trimmer.trim(session, start, start.plusSeconds(57)));
            org.junit.jupiter.api.Assertions.assertNull(offset.get());
            org.junit.jupiter.api.Assertions.assertNull(duration.get());
        }
    }

    @Test
    void rejectsUnknownClockAndShortTailBeforeCutting() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 9, 58, 9);
        ExactDurationTrimmer trimmer = new ExactDurationTrimmer(new IsupProperties(),
                (in, out, off, len) -> { throw new AssertionError("缺少时间覆盖不能裁切"); });
        MediaSession missing = new MediaSession("missing", SessionType.DOWNLOAD, "DLWH", 1);
        assertThrows(IOException.class, () -> trimmer.trim(missing, start, start.plusSeconds(57)));
        MediaSession shortTail = recording(1, start.minusSeconds(5), start.plusSeconds(55));
        assertThrows(IOException.class, () -> trimmer.trim(shortTail, start, start.plusSeconds(57)));
    }

    @Test
    void mustNotTreatLaterInWindowDescriptorAsFileZero() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 10, 14, 9, 58, 9);
        MediaSession session = new MediaSession("early", SessionType.DOWNLOAD, "DLWH", 2);
        session.setFilePath(Files.write(tempDir.resolve("early.mp4"), new byte[]{1}).toString());
        for (LocalDateTime time : List.of(start.minusSeconds(14), start.minusSeconds(13), start.plusSeconds(62))) {
            byte[] packet = descriptor(time);
            session.streamClock().accept(packet, packet.length, start.minusSeconds(13), start.plusSeconds(64));
        }
        ExactDurationTrimmer trimmer = new ExactDurationTrimmer(new IsupProperties(),
                (in, out, off, len) -> { throw new AssertionError("不能把第2帧时间冒充文件零点"); });
        assertThrows(IOException.class, () -> trimmer.trim(session, start, start.plusSeconds(57)));
    }

    @Test
    void rejectsNonFiniteCutParameters() {
        ExactDurationTrimmer trimmer = new ExactDurationTrimmer(new IsupProperties(),
                (in, out, off, len) -> { throw new AssertionError("非法参数不得启动 FFmpeg"); });
        assertThrows(IOException.class, () -> trimmer.trim(tempDir.resolve("input.mp4"), Double.NaN, 10));
        assertThrows(IOException.class, () -> trimmer.trim(tempDir.resolve("input.mp4"), 0, Double.POSITIVE_INFINITY));
    }

    private MediaSession recording(int channel, LocalDateTime first, LocalDateTime last) throws Exception {
        MediaSession session = new MediaSession("task-" + channel, SessionType.DOWNLOAD, "DLWH", channel);
        session.setFilePath(Files.write(tempDir.resolve("source-" + channel + ".mp4"), new byte[]{1}).toString());
        session.streamClock().note(first);
        byte[] packet = descriptor(last);
        session.streamClock().accept(packet, packet.length, first.minusSeconds(1), last.plusSeconds(1));
        return session;
    }

    private static byte[] descriptor(LocalDateTime last) {
        byte[] packet = new byte[12];
        packet[2] = 0x48; packet[3] = 0x4b;
        packet[6] = (byte) (last.getYear() - 2000);
        packet[7] = (byte) ((last.getMonthValue() << 4) | (last.getDayOfMonth() >> 1));
        packet[8] = (byte) (((last.getDayOfMonth() & 1) << 7) | (last.getHour() << 2) | (last.getMinute() >> 4));
        packet[9] = (byte) ((last.getMinute() << 4) | (last.getSecond() >> 2));
        packet[10] = (byte) ((last.getSecond() & 3) << 6);
        return packet;
    }

    @Test
    @DisplayName("按首帧偏移裁切到目标时长")
    void cutsAtClockOffset() throws Exception {
        Path source = Files.write(tempDir.resolve("ch1.mp4"), new byte[]{1, 2, 3});
        AtomicReference<Double> offset = new AtomicReference<>();
        AtomicReference<Double> duration = new AtomicReference<>();
        ExactDurationTrimmer trimmer = new ExactDurationTrimmer(new IsupProperties(),
                (input, output, off, seconds) -> {
                    offset.set(off);
                    duration.set(seconds);
                    Files.write(output, new byte[]{9});
                });

        Path result = trimmer.trim(source, 2.8, 21.0);

        assertEquals(2.8, offset.get(), 0.0001);
        assertEquals(21.0, duration.get(), 0.0001);
        assertEquals(tempDir.resolve("ch1.exact.mp4"), result);
        assertTrue(Files.size(result) > 0);
    }

    @Test
    @DisplayName("裁切命令在输入之后做精确 seek，视频转 H.264")
    void trimCommandSeeksAfterInput() {
        List<String> command = ExactDurationTrimmer.trimCommand("ffmpeg", 1,
                Path.of("in.mp4"), Path.of("out.mp4"), 2.8, 21.0);

        assertEquals("ffmpeg", command.get(0));
        assertEquals("-i", command.get(command.indexOf("-ss") - 2));
        assertEquals("in.mp4", command.get(command.indexOf("-i") + 1));
        assertEquals("2.800", command.get(command.indexOf("-ss") + 1));
        assertEquals("21.000", command.get(command.indexOf("-t") + 1), "不能在结束点额外保留下一单画面");
        assertEquals("libx264", command.get(command.indexOf("-c:v") + 1));
        assertEquals("out.mp4", command.get(command.size() - 1));
        assertTrue(command.contains("0:a?"));
    }

}
