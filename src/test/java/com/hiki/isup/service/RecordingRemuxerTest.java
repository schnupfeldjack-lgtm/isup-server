package com.hiki.isup.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecordingRemuxerTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 15, 9, 15, 5);
    private static final LocalDateTime END = START.plusSeconds(60);

    @TempDir Path directory;

    @Test void onlyActualStrictH264CodecExitQualifiesForTailProof() {
        assertTrue(RecordingRemuxer.isCodecFailure(new IOException("命令失败 exit=183 "
                + "out_time_us=123\n".repeat(100) + "[h264 @ 123] error while decoding MB 1 2 bytestream -5")));
        for (IOException failure : List.of(new IOException("Cannot run program ffmpeg"),
                new IOException("命令失败 exit=183 Permission denied"), new IOException("命令失败 exit=183 Invalid argument"),
                new IOException("命令失败 exit=0 [h264 @ 123] bytestream -5"),
                new IOException("命令失败 exit=183 [h264 @ 123] bytestream -5\nInput/output error"),
                new IOException("[h264 @ 123] bytestream -5"), new SocketTimeoutException("命令失败 exit=183 [h264 @ 123] bytestream -5"),
                new IOException("命令失败 exit=183 [h264 @ 123] bytestream -5", new InterruptedException()))) {
            assertFalse(RecordingRemuxer.isCodecFailure(failure), failure.toString());
        }
    }

    @Test void failedInitialCopyDoesNotAttemptTailProofEvenWithCodecText() throws Exception {
        MediaSession session = session();
        IOException failure = new IOException("命令失败 exit=183 [h264 @ 123] bytestream -5");
        AtomicInteger count = new AtomicInteger();
        RecordingRemuxer remuxer = new RecordingRemuxer(properties(), (command, deadline, parent) -> { count.incrementAndGet(); throw failure; });
        assertSame(failure, assertThrows(IOException.class, () -> remuxer.remux(session, START, END, Long.MAX_VALUE)));
        assertEquals(1, count.get());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void preservesOriginalSourceAndCopiesForDeliveryButFullyDecodesForValidation() throws Exception {
        MediaSession session = session();
        byte[] original = Files.readAllBytes(Path.of(session.getRawFilePath()));
        List<List<String>> commands = new ArrayList<>();
        long deadline = RecordingDeadline.afterSeconds(30);
        RecordingRemuxer remuxer = new RecordingRemuxer(properties(), (command, actualDeadline, parent) -> {
            assertEquals(deadline, actualDeadline, "两个进程必须共享整行截止时间");
            assertEquals(directory.toAbsolutePath(), parent);
            commands.add(command);
            if (commands.size() == 1) {
                assertTrue(command.contains("-an"), "首个进程应直接静音封装，不再探测首包偏移");
                Files.write(Path.of(command.getLast()), new byte[]{7, 8, 9});
                return "";
            }
            return "out_time_us=1000000\nprogress=continue\nout_time_us=75000000\nprogress=end\n";
        });

        Path output = remuxer.remux(session, START, END, deadline);

        assertEquals(directory.resolve("recording-playable.mp4").toAbsolutePath(), output);
        assertArrayEquals(original, Files.readAllBytes(Path.of(session.getRawFilePath())));
        assertEquals(2, commands.size(), "静音封装后须完整解码验收");
        List<String> remux = commands.getFirst();
        assertEquals("35000000", argument(remux, "-analyzeduration"));
        assertEquals("10000000", argument(remux, "-probesize"));
        assertTrue(remux.indexOf("-analyzeduration") < remux.indexOf("-i"));
        assertTrue(remux.indexOf("-probesize") < remux.indexOf("-i"));
        assertEquals("fixture-ffmpeg", remux.getFirst());
        assertTrue(remux.containsAll(List.of("-copyts", "-start_at_zero")));
        assertEquals("+discardcorrupt", argument(remux, "-fflags"));
        assertEquals("copy", argument(remux, "-c:v"));
        assertTrue(remux.contains("-an"), "打包成片必须关闭音频");
        assertFalse(remux.contains("-c:a"));
        assertFalse(remux.contains("-b:a"));
        assertEquals("1", argument(remux, "-threads"));
        assertEquals("+faststart", argument(remux, "-movflags"));
        assertEquals(Long.toString(RecordingStorageGuard.MAX_OUTPUT_BYTES), argument(remux, "-fs"));
        assertEquals("0:v:0", argument(remux, "-map"));
        assertEquals(1, remux.stream().filter("-map"::equals).count());
        assertFalse(remux.contains("0:a:0?"));
        assertFalse(remux.contains("-ss"), "纯视频输出自行归零，不得再按前置音频时长裁掉视频");
        List<String> probe = commands.getLast();
        assertFalse(probe.contains("-c:v"), "验收不能跳过视频解码");
        assertTrue(probe.contains("-xerror"));
        assertEquals("explode", argument(probe, "-err_detect"));
        assertEquals("error", argument(probe, "-loglevel"));
        assertTrue(probe.indexOf("-err_detect") < probe.indexOf("-i"));
        assertEquals(2, probe.stream().filter("-threads"::equals).count());
        assertTrue(probe.indexOf("-threads") < probe.indexOf("-i"));
        assertTrue(probe.lastIndexOf("-threads") > probe.indexOf("-i"));
        assertEquals("1", probe.get(probe.indexOf("-threads") + 1));
        assertEquals("1", probe.get(probe.lastIndexOf("-threads") + 1));
        assertEquals("passthrough", argument(probe, "-fps_mode"));
        assertEquals("demux", argument(probe, "-enc_time_base"));
        assertTrue(probe.contains("-an"));
        assertEquals("0:v:0", argument(probe, "-map"));
        assertEquals("pipe:1", argument(probe, "-progress"));
        assertEquals("null", argument(probe, "-f"));
        assertFalse(probe.contains("0:a:0?"));
        for (List<String> command : commands) {
            for (String forbidden : List.of("libx264", "-vf", "-af", "-r", "-t", "-ss", "-frames:v", "framehash",
                    "-shortest", "-bsf:v", "-discardcorrupt")) {
                assertFalse(command.contains(forbidden), forbidden);
            }
        }
    }

    @Test
    void recordsRemuxWarningsWithBoundedMessageSize() throws Exception {
        MediaSession session = session();
        AtomicInteger calls = new AtomicInteger();
        RecordingRemuxer remuxer = new RecordingRemuxer(properties(), (command, deadline, parent) -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                Files.write(Path.of(command.getLast()), new byte[]{9});
                return "x".repeat(5_000) + "Packet corrupt; Non-monotonic DTS";
            }
            return progress(75_000_000);
        });
        Logger logger = (Logger) LoggerFactory.getLogger(RecordingRemuxer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            remuxer.remux(session, START, END, RecordingDeadline.afterSeconds(30));
            List<ILoggingEvent> warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size());
            String message = warnings.getFirst().getFormattedMessage();
            assertTrue(message.contains("Packet corrupt; Non-monotonic DTS"));
            assertTrue(message.contains(session.getSessionId()));
            assertTrue(message.length() < 4_300, "告警载荷须有界，不能写出完整FFmpeg输出");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {58_000_000, 75_000_000, 110_000_000})
    void acceptsDeviceSegmentWithinCoarseDurationBounds(long micros) throws Exception {
        MediaSession session = session();
        Path output = successfulRemuxer(progress(micros)).remux(session, START, END,
                RecordingDeadline.afterSeconds(30));
        assertNotNull(output);
        assertTrue(Files.exists(output));
    }

    @Test
    void shortVideoHasDistinctFailureWithoutInventingCoverageTimes() throws Exception {
        MediaSession session = session();
        IOException failure = assertThrows(IOException.class, () -> successfulRemuxer(progress(57_999_999))
                .remux(session, START, END, RecordingDeadline.afterSeconds(30)));

        assertEquals("RecordingDurationShortageException", failure.getClass().getSimpleName());
        assertFalse(failure.getMessage().contains("available="));
        assertFalse(Files.exists(directory.resolve("recording-playable.mp4")));
        assertTrue(Files.exists(Path.of(session.getRawFilePath())));
    }

    @Test
    void excessiveDurationRemainsPermanentValidationFailure() throws Exception {
        MediaSession session = session();
        assertThrows(RecordingValidationException.class, () -> successfulRemuxer(progress(110_000_001))
                .remux(session, START, END, RecordingDeadline.afterSeconds(30)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "progress=end", "out_time_us=0\nprogress=end", "out_time_us=-1\nprogress=end",
            "out_time_us=57999999\nprogress=end", "out_time_us=110000001\nprogress=end",
            "out_time_us=75000000\nprogress=continue", "out_time_us=N/A\nprogress=end",
            "out_time_us=75000000\nout_time_us=N/A\nprogress=end",
            "out_time_us=75000000\nprogress=end\nprogress=continue"
    })
    void rejectsIncompleteOrUnreasonableVideoProgressAndDeletesOnlyOutput(String progress) throws Exception {
        MediaSession session = session();
        assertThrows(IOException.class, () -> successfulRemuxer(progress).remux(session, START, END,
                RecordingDeadline.afterSeconds(30)));
        assertFalse(Files.exists(directory.resolve("recording-playable.mp4")));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(Path.of(session.getRawFilePath())));
    }

    @Test
    void rejectsMissingOrNonCoveringRequestTimesBeforeStartingProcess() throws Exception {
        MediaSession session = session();
        RecordingRemuxer remuxer = noProcess();
        assertThrows(IOException.class, () -> remuxer.remux(null, START, END, Long.MAX_VALUE));
        assertThrows(IOException.class, () -> remuxer.remux(session, null, END, Long.MAX_VALUE));
        assertThrows(IOException.class, () -> remuxer.remux(session, START, START, Long.MAX_VALUE));
        session.setStartTime(null);
        assertThrows(IOException.class, () -> remuxer.remux(session, START, END, Long.MAX_VALUE));
        session.setStartTime(START.plusSeconds(1));
        assertThrows(IOException.class, () -> remuxer.remux(session, START, END, Long.MAX_VALUE));
        session.setStartTime(START.minusSeconds(5));
        session.setEndTime(END.minusSeconds(1));
        assertThrows(IOException.class, () -> remuxer.remux(session, START, END, Long.MAX_VALUE));
    }

    @Test
    void rejectsMissingEmptyOrOversizedSourceBeforeStartingProcess() throws Exception {
        MediaSession session = session();
        Path source = Path.of(session.getRawFilePath());
        session.setRawFilePath(null);
        assertThrows(IOException.class, () -> noProcess().remux(session, START, END, Long.MAX_VALUE));
        session.setRawFilePath(source.toString());
        Files.delete(source);
        assertThrows(IOException.class, () -> noProcess().remux(session, START, END, Long.MAX_VALUE));
        Files.createFile(source);
        assertThrows(IOException.class, () -> noProcess().remux(session, START, END, Long.MAX_VALUE));
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(RecordingStorageGuard.MAX_OUTPUT_BYTES);
        }
        assertThrows(IOException.class, () -> noProcess().remux(session, START, END, Long.MAX_VALUE));
    }

    @Test
    void refusesSourceThatWouldBeOverwrittenByOutputName() throws Exception {
        MediaSession session = session();
        Path source = Files.write(directory.resolve("recording-playable.mp4"), new byte[]{3, 4});
        session.setRawFilePath(source.toString());
        assertThrows(IOException.class, () -> noProcess().remux(session, START, END, Long.MAX_VALUE));
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(source));
    }

    @Test
    void rejectsExpiredDeadlineBeforeRunningAnything() throws Exception {
        MediaSession session = session();
        assertThrows(SocketTimeoutException.class, () -> noProcess().remux(session, START, END,
                System.nanoTime() - 1));
    }

    @Test
    void propagatesTimeoutAndCleansPartialOutputWithoutTouchingSource() throws Exception {
        MediaSession session = session();
        SocketTimeoutException timeout = new SocketTimeoutException("bounded process timeout");
        RecordingRemuxer remuxer = new RecordingRemuxer(properties(), (command, deadline, parent) -> {
            Files.write(Path.of(command.getLast()), new byte[]{9});
            throw timeout;
        });
        assertSame(timeout, assertThrows(SocketTimeoutException.class,
                () -> remuxer.remux(session, START, END, RecordingDeadline.afterSeconds(30))));
        assertFalse(Files.exists(directory.resolve("recording-playable.mp4")));
        assertTrue(Files.exists(Path.of(session.getRawFilePath())));
    }

    @Test
    void cleansOutputWhenPacketScanFails() throws Exception {
        MediaSession session = session();
        AtomicInteger calls = new AtomicInteger();
        IOException failure = new IOException("packet scan failed");
        RecordingRemuxer remuxer = new RecordingRemuxer(properties(), (command, deadline, parent) -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                Files.write(Path.of(command.getLast()), new byte[]{9});
                return "";
            }
            throw failure;
        });
        assertSame(failure, assertThrows(IOException.class,
                () -> remuxer.remux(session, START, END, RecordingDeadline.afterSeconds(30))));
        assertEquals(2, calls.get());
        assertFalse(Files.exists(directory.resolve("recording-playable.mp4")));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 2L * 1024 * 1024 * 1024})
    void exitZeroCannotAuthorizeEmptyOrSizeLimitedOutput(long size) throws Exception {
        MediaSession session = session();
        AtomicInteger calls = new AtomicInteger();
        RecordingRemuxer remuxer = new RecordingRemuxer(properties(), (command, deadline, parent) -> {
            int call = calls.getAndIncrement();
            assertEquals(0, call, "无效成片不应进入下一进程");
            try (RandomAccessFile file = new RandomAccessFile(command.getLast(), "rw")) {
                file.setLength(size);
            }
            return "";
        });
        assertThrows(IOException.class, () -> remuxer.remux(session, START, END, RecordingDeadline.afterSeconds(30)));
        assertEquals(1, calls.get());
        assertFalse(Files.exists(directory.resolve("recording-playable.mp4")));
    }

    @Test
    void observesInterruptionBeforeStartingProcess() throws Exception {
        MediaSession session = session();
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> noProcess().remux(session, START, END, Long.MAX_VALUE));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private RecordingRemuxer successfulRemuxer(String progress) {
        AtomicInteger calls = new AtomicInteger();
        return new RecordingRemuxer(properties(), (command, deadline, parent) -> {
            int call = calls.getAndIncrement();
            if (call == 0) {
                Files.write(Path.of(command.getLast()), new byte[]{9});
                return "";
            }
            return progress;
        });
    }

    private RecordingRemuxer noProcess() {
        return new RecordingRemuxer(properties(), (command, deadline, parent) -> {
            throw new AssertionError("非法输入不得运行 FFmpeg");
        });
    }

    private MediaSession session() throws IOException {
        MediaSession session = new MediaSession("remux-fixture", SessionType.DOWNLOAD, "device", 1);
        session.setStartTime(START.minusSeconds(5));
        session.setEndTime(END.plusSeconds(15));
        session.setRawFilePath(Files.write(directory.resolve("recording-source.ps"), new byte[]{1, 2, 3, 4}).toString());
        return session;
    }

    private static IsupProperties properties() {
        IsupProperties properties = new IsupProperties();
        properties.setFfmpegPath("fixture-ffmpeg");
        return properties;
    }

    private static String argument(List<String> command, String option) {
        assertTrue(command.contains(option), option);
        return command.get(command.indexOf(option) + 1);
    }

    private static String progress(long micros) {
        return "out_time_us=" + micros + "\nprogress=end\n";
    }

}
