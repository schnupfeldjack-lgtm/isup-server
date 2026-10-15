package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真正编码、按视频 AU 封装合法 HK/PSM/PES，再经过生产的完整校验/裁切入口。 */
class RecordingTimelineIntegrationTest {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 14, 13, 37, 50);
    private static final long PTS_BASE = 90_000_000;
    @TempDir Path tempDir;
    private String ffmpeg;
    private IsupProperties properties;

    @Test void incompleteTrailingPesOutsideOrderUsesOnlyVerifiedCompletePrefix() throws Exception {
        int fps = 20;
        List<byte[]> frames = generateAccessUnits(fps);
        Path reference = writePs("complete-reference", fps, frames, Mode.NORMAL);
        Path ps = withIncompleteTail(reference, "incomplete-padding", frames.getFirst());
        long originalSize = Files.size(ps);
        // 此原流确实会在严格 FFmpeg 解码失败，不能只在 Java 解析器吞掉 EOF。
        assertThrows(IOException.class, () -> RecordingFrameProbe.read(ffmpeg, ps, 30));
        Path output = new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4));
        assertGreenSegment(output, reference, fps);
        assertEquals(originalSize, Files.size(ps), "不得修改已下载原流");
        assertNoPrefixCopies();
    }

    @Test void incompleteTailCannotHideMissingFramesInsideTheOrder() throws Exception {
        List<byte[]> frames = generateAccessUnits(20);
        Path reference = writePs("missing-with-tail-reference", 20, frames, Mode.MISSING_FRAME);
        Path ps = withIncompleteTail(reference, "missing-with-tail", frames.getFirst());
        assertThrows(IOException.class,
                () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4)));
        assertFalse(Files.exists(ExactDurationTrimmer.exactPath(Path.of(session(ps).getFilePath()))));
        assertNoPrefixCopies();
    }

    @Test void incompleteTailCannotSupplyAnOrderEndingBeyondCompleteAnchors() throws Exception {
        List<byte[]> frames = generateAccessUnits(20);
        Path reference = writePs("short-with-tail-reference", 20, frames, Mode.NORMAL);
        Path ps = withIncompleteTail(reference, "short-with-tail", frames.getFirst());
        assertThrows(RecordingCoverageException.class,
                () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(6)));
        assertNoPrefixCopies();
    }

    @Test void incompleteContinuationOutsideOrderExcludesTheWholeProvenAccessUnit() throws Exception {
        List<byte[]> frames = generateAccessUnits(20);
        Path reference = writePs("continuation-reference", 20, frames, Mode.NORMAL);
        Path ps = withIncompleteContinuation(reference, "incomplete-continuation", frames.getFirst(), 6, true);
        long originalSize = Files.size(ps);
        assertThrows(IOException.class, () -> RecordingFrameProbe.read(ffmpeg, ps, 30));
        Path output = new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4));
        assertGreenSegment(output, reference, 20);
        assertEquals(originalSize, Files.size(ps));
        assertNoPrefixCopies();
    }

    @Test void incompleteContinuationInsideOrderCannotBeUploaded() throws Exception {
        List<byte[]> frames = generateAccessUnits(20);
        Path reference = writePs("inside-continuation-reference", 20, frames.subList(0, 60), Mode.NORMAL);
        Path ps = withIncompleteContinuation(reference, "inside-continuation", frames.get(60), 3, true);
        assertThrows(RecordingCoverageException.class,
                () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(1), BASE.plusSeconds(4)));
        assertFalse(Files.exists(ExactDurationTrimmer.exactPath(Path.of(session(ps).getFilePath()))));
        assertNoPrefixCopies();
    }

    @Test void incompleteContinuationWithoutHkCannotGuessAnAccessUnitBoundary() throws Exception {
        List<byte[]> frames = generateAccessUnits(20);
        Path reference = writePs("unknown-continuation-reference", 20, frames, Mode.NORMAL);
        Path ps = withIncompleteContinuation(reference, "unknown-continuation", frames.getFirst(), 6, false);
        assertThrows(IOException.class,
                () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4)));
        assertFalse(Files.exists(ExactDurationTrimmer.exactPath(Path.of(session(ps).getFilePath()))));
        assertNoPrefixCopies();
    }

    private Path withIncompleteContinuation(Path reference, String name, byte[] unit,
                                            int seconds, boolean hasHk) throws IOException {
        byte[] complete = Files.readAllBytes(reference);
        int split = Math.max(5, unit.length - 24);
        long pts = PTS_BASE + seconds * 90_000L;
        byte[] continuation = PsRecordingTimelineTest.pes(0xe0, pts,
                Arrays.copyOfRange(unit, split, unit.length));
        return Files.write(tempDir.resolve(name + ".ps"), PsRecordingTimelineTest.join(
                Arrays.copyOf(complete, complete.length - 4), pack(pts),
                hasHk ? psm(BASE.plusSeconds(seconds), false) : new byte[0],
                PsRecordingTimelineTest.pes(0xe0, pts, Arrays.copyOf(unit, split)),
                Arrays.copyOf(continuation, continuation.length - Math.min(8, unit.length - split - 1))));
    }

    private Path withIncompleteTail(Path reference, String name, byte[] accessUnit) throws IOException {
        byte[] complete = Files.readAllBytes(reference);
        byte[] tail = PsRecordingTimelineTest.pes(0xe0, PTS_BASE + 6 * 90_000L, accessUnit);
        return Files.write(tempDir.resolve(name + ".ps"), PsRecordingTimelineTest.join(
                Arrays.copyOf(complete, complete.length - 4), pack(PTS_BASE + 6 * 90_000L),
                psm(BASE.plusSeconds(6), false), Arrays.copyOf(tail, tail.length - 8)));
    }

    private void assertNoPrefixCopies() throws IOException {
        try (var paths = Files.list(tempDir)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("recording-prefix-")),
                    "成功或失败后均须清理完整前缀副本");
        }
    }

    @BeforeEach void requireFfmpeg() {
        ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!ffmpeg.isBlank() && Files.isExecutable(Path.of(ffmpeg)), "需要 -Disup.test.ffmpeg");
        properties = new IsupProperties();
        properties.setFfmpegPath(ffmpeg);
        properties.getFfmpeg().setThreads(1);
    }

    @ParameterizedTest @ValueSource(ints = {5, 8, 10, 20, 25})
    void realPsAtFiveTo25FpsKeepsOnlyThisOrdersGreenFrames(int fps) throws Exception {
            List<byte[]> frames = generateAccessUnits(fps);
            Path ps = writePs("normal-" + fps, fps, frames, Mode.NORMAL);
            var inputFrames = RecordingFrameProbe.read(ffmpeg, ps, 30);
            assertEquals(6 * fps, inputFrames.video().size());
            assertEquals(1000d, inputFrames.video().getFirst(), .000001);
            Path output = new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4));
            assertGreenSegment(output, ps, fps);
    }

    @ParameterizedTest @ValueSource(ints = {5, 8, 10, 20, 25})
    void actualLowRatePsWithAMissingFrameCannotBeUploaded(int fps) throws Exception {
        Path ps = writePs("missing-frame-" + fps, fps, generateAccessUnits(fps), Mode.MISSING_FRAME);
        assertThrows(IOException.class,
                () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4)));
        assertFalse(Files.exists(ExactDurationTrimmer.exactPath(Path.of(session(ps).getFilePath()))));
    }

    @Test void heldFirstFrameOutsideOrderIsRemovedUsingLaterHkPtsSegment() throws Exception {
        int fps = 20;
        Path ps = writePs("held-lead", fps, generateAccessUnits(fps), Mode.HELD_LEAD);
        var before = RecordingFrameProbe.read(ffmpeg, ps, 30);
        assertEquals(11.51, before.video().get(1) - before.video().getFirst(), .000001);
        Path output = new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4));
        assertGreenSegment(output, ps, fps);
    }

    @Test void clockJumpInsideOrderFailsBeforeCreatingOutput() throws Exception {
        Path ps = writePs("wall-jump", 20, generateAccessUnits(20), Mode.WALL_JUMP);
        MediaSession session = session(ps);
        IOException error = assertThrows(IOException.class,
                () -> new ExactDurationTrimmer(properties).trim(session, BASE.plusSeconds(2), BASE.plusSeconds(13)));
        assertTrue(error.getMessage().contains("连续时间段"));
        assertFalse(Files.exists(ExactDurationTrimmer.exactPath(Path.of(session.getFilePath()))));
    }

    @Test void missingHkNeverFallsBackToGuessingFromMp4() throws Exception {
        Path ps = writePs("no-hk", 20, generateAccessUnits(20), Mode.NO_HK);
        assertThrows(IOException.class,
                () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4)));
    }

    @Test void aacAudioKeepsTheSameBusinessWindowAsVideo() throws Exception {
        Path ps = writePs("with-audio", 20, generateAccessUnits(20), Mode.NORMAL, generateAacAccessUnits());
        var source = RecordingFrameProbe.read(ffmpeg, ps, 30);
        assertFalse(source.audio().isEmpty(), "测试原始PS必须包含真实解码音频");
        assertEquals(1000, source.audio().getFirst().start(), .001);
        Path output = new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4));
        assertGreenSegment(output, ps, 20);
        var actual = RecordingFrameProbe.read(ffmpeg, output, 30);
        assertFalse(actual.audio().isEmpty(), "裁切不能丢掉原始音轨");
        assertTrue(Math.abs(actual.audio().getFirst().start()) <= .001, "音频与画面归零对齐");
        assertTrue(Math.abs(actual.audio().getLast().end() - 2) <= .15, "音频必须完整覆盖本单2秒");
    }

    @Test void g711AudioWithKnownHikPrivateRecordsKeepsSoundAndTheBusinessWindow() throws Exception {
        Path alaw = tempDir.resolve("private-record-tone.alaw");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=8000:duration=6", "-c:a", "pcm_alaw", "-f", "alaw", alaw.toString()), 30);
        byte[] pcm = Files.readAllBytes(alaw);
        List<byte[]> video = generateAccessUnits(20);
        Path ps = tempDir.resolve("g711-with-private-records.ps");
        try (var out = Files.newOutputStream(ps)) {
            int audioAt = 0;
            for (int i = 0; i < video.size(); i++) {
                long pts = PTS_BASE + i * 4500L;
                out.write(pack(pts));
                if (i % 20 == 0) {
                    byte[] map = psm(BASE.plusNanos(i * 50_000_000L), true);
                    map[32] = (byte) 0x90;
                    out.write(map);
                }
                out.write(PsRecordingTimelineTest.pes(0xe0, pts, video.get(i)));
                out.write(PsRecordingTimelineTest.hikPrivatePes(i % 20 == 0 ? 2 : 8, pts));
                while (audioAt * 320 < pcm.length && PTS_BASE + audioAt * 3600L < pts + 4500) {
                    out.write(PsRecordingTimelineTest.pes(0xc0, PTS_BASE + audioAt * 3600L,
                            Arrays.copyOfRange(pcm, audioAt * 320, ++audioAt * 320)));
                }
            }
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        assertEquals(1, PsRecordingTimeline.parse(ps).audioStreamCount());
        var source = RecordingFrameProbe.read(ffmpeg, ps, 30);
        assertFalse(source.audio().isEmpty(), "必须真实解码 C0 G711 音频，而不是仅声明音轨");
        Path output = new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4));
        assertGreenSegment(output, ps, 20);
        var actual = RecordingFrameProbe.read(ffmpeg, output, 30);
        assertFalse(actual.audio().isEmpty(), "忽略已证实私有记录后仍须保留真实音频");
        assertTrue(Math.abs(actual.audio().getFirst().start()) <= .001);
        assertTrue(Math.abs(actual.audio().getLast().end() - 2) <= .15);
    }

    @Test void realBFramesAt20And25FpsPreservePresentationOrderWithDenseAndSparseAnchors() throws Exception {
        for (int fps : new int[]{20, 25}) {
            var encoded = generateBFrames(fps);
            for (boolean dense : new boolean[]{true, false}) {
                Path ps = writeBFramePs("bframes-" + fps + "-" + dense, fps, encoded, dense, false);
                Path output = new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4));
                assertGreenSegment(output, ps, fps);
            }
        }
    }

    @Test void actualBFramesDoNotAuthorizeAResetDecodeClock() throws Exception {
        Path ps = writeBFramePs("b-reset", 20, generateBFrames(20), true, true);
        IOException error = assertThrows(IOException.class,
                () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4)));
        assertTrue(error.getMessage().contains("DTS"), error.getMessage());
    }

    @Test void generatedG711PrivateAudioMustFailWhenDemuxerTreatsItAsData() throws Exception {
        Path alaw = tempDir.resolve("tone.alaw");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=8000:duration=6", "-c:a", "pcm_alaw", "-f", "alaw", alaw.toString()), 30);
        byte[] pcm = Files.readAllBytes(alaw);
        List<byte[]> video = generateAccessUnits(20);
        for (boolean declared : new boolean[]{true, false}) {
            Path ps = tempDir.resolve("g711-" + declared + ".ps");
            try (var out = Files.newOutputStream(ps)) {
                for (int i = 0; i < video.size(); i++) {
                    long pts = PTS_BASE + i * 4500L;
                    out.write(pack(pts));
                    if (i % 20 == 0) {
                        byte[] map = psm(BASE.plusNanos(i * 50_000_000L), declared);
                        if (declared) { map[32] = (byte) 0x90; map[33] = (byte) 0xbd; }
                        out.write(map);
                    }
                    out.write(PsRecordingTimelineTest.pes(0xe0, pts, video.get(i)));
                    // 标准 A-law 载荷 + 设备常见私有 stream_type/stream_id 声明；并非设备实录验收。
                    out.write(PsRecordingTimelineTest.pes(0xbd, pts, Arrays.copyOfRange(pcm, i * 400, (i + 1) * 400)));
                }
                out.write(new byte[]{0, 0, 1, (byte) 0xb9});
            }
            var decoded = RecordingFrameProbe.read(ffmpeg, ps, 30);
            assertTrue(decoded.audio().isEmpty(), "fixture 应复现 Data/未知流而非可解码音频");
            IOException error = assertThrows(IOException.class,
                    () -> new ExactDurationTrimmer(properties).trim(session(ps), BASE.plusSeconds(2), BASE.plusSeconds(4)));
            assertTrue(error.getMessage().contains("音频") || error.getMessage().contains("音轨"), error.getMessage());
        }
    }

    private record EncodedFrames(List<byte[]> units, List<long[]> clocks) { }

    private EncodedFrames generateBFrames(int fps) throws Exception {
        Path elementary = tempDir.resolve("bframes-" + fps + ".h264");
        Path log = tempDir.resolve("bframes-" + fps + ".log");
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "verbose", "-debug_ts"));
        for (String color : new String[]{"red", "green", "blue"}) {
            command.addAll(List.of("-f", "lavfi", "-i", "color=c=" + color + ":s=160x96:r=" + fps + ":d=2"));
        }
        command.addAll(List.of("-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]", "-map", "[v]",
                "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-bf", "2", "-g", "" + fps,
                "-keyint_min", "" + fps, "-sc_threshold", "0", "-x264-params",
                "b-adapt=0:b-pyramid=none:aud=1:repeat-headers=1", "-threads", "1", "-f", "h264", elementary.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
        Pattern packet = Pattern.compile("encoder -> type:video pkt_pts:(-?\\d+).*?pkt_dts:(-?\\d+)");
        List<long[]> clocks = new ArrayList<>();
        for (String line : Files.readAllLines(log)) {
            var match = packet.matcher(line);
            if (match.find()) clocks.add(new long[]{Long.parseLong(match.group(1)), Long.parseLong(match.group(2))});
        }
        assertEquals(6 * fps, clocks.size());
        assertTrue(java.util.stream.IntStream.range(1, clocks.size())
                .anyMatch(i -> clocks.get(i)[0] < clocks.get(i - 1)[0]), "必须真实编码B帧产生PTS倒序");
        return new EncodedFrames(splitAccessUnits(Files.readAllBytes(elementary), fps), clocks);
    }

    private Path writeBFramePs(String name, int fps, EncodedFrames encoded, boolean dense, boolean reset) throws Exception {
        Path source = tempDir.resolve(name + ".ps");
        try (var out = Files.newOutputStream(source)) {
            for (int i = 0; i < encoded.units().size(); i++) {
                long frame = encoded.clocks().get(i)[0];
                long pts = PTS_BASE + frame * 90_000 / fps;
                long dts = PTS_BASE + encoded.clocks().get(i)[1] * 90_000 / fps;
                if (reset && i == 3 * fps) dts -= 90_000;
                out.write(pack(dts));
                if (dense || frame % fps == 0) out.write(psm(BASE.plusNanos(frame * 1_000_000_000L / fps), false));
                // PTS=DTS 时仅写 PTS，覆盖 MPEG PES 的省略 DTS 分支。
                out.write(pts == dts ? PsRecordingTimelineTest.pes(0xe0, pts, encoded.units().get(i))
                        : PsRecordingTimelineTest.pesDts(0xe0, pts, dts, encoded.units().get(i)));
            }
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        return source;
    }

    private void assertGreenSegment(Path output, Path source, int fps) throws Exception {
        var actual = RecordingFrameProbe.read(ffmpeg, output, 30);
        assertEquals(2 * fps, actual.video().size(), "本单2秒每帧保留，不补帧、不混入相邻单");
        assertEquals(0, actual.video().getFirst(), .000001);
        assertEquals(2 - 1d / fps, actual.video().getLast(), .000001);
        List<String> hashes = frameHashes(output);
        List<String> sourceHashes = frameHashes(source);
        assertEquals(1, hashes.stream().distinct().count(), "成片只能有绿色本单画面");
        assertEquals(sourceHashes.get(3 * fps), hashes.getFirst(), "画面须来自中间绿色订单");
        assertEquals(3, sourceHashes.stream().distinct().count(), "测试原流必须有前后红蓝订单");
    }

    private List<byte[]> generateAccessUnits(int fps) throws Exception {
        Path elementary = tempDir.resolve("three-orders-" + fps + ".h264");
        if (!Files.exists(elementary)) {
            List<String> command = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error"));
            for (String color : new String[]{"red", "green", "blue"}) {
                command.addAll(List.of("-f", "lavfi", "-i", "color=c=" + color + ":s=160x96:r=" + fps + ":d=2"));
            }
            command.addAll(List.of("-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]", "-map", "[v]",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18", "-bf", "0", "-g", "" + fps,
                    "-keyint_min", "" + fps, "-sc_threshold", "0", "-x264-params", "aud=1:repeat-headers=1",
                    "-threads", "1", "-f", "h264", elementary.toString()));
            ExactDurationTrimmer.run(command, 30);
        }
        return splitAccessUnits(Files.readAllBytes(elementary), fps);
    }

    private List<byte[]> splitAccessUnits(byte[] data, int fps) {
        List<Integer> aud = new ArrayList<>();
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            int prefix = data[i + 2] == 1 ? 3 : data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
            if (prefix > 0) {
                if ((data[i + prefix] & 0x1f) == 9) aud.add(i);
                i += prefix - 1;
            }
        }
        assertEquals(0, aud.getFirst(), "首AU应从AUD开始，SPS/PPS保留在相同AU");
        assertEquals(6 * fps, aud.size());
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < aud.size(); i++) {
            frames.add(Arrays.copyOfRange(data, aud.get(i), i + 1 < aud.size() ? aud.get(i + 1) : data.length));
        }
        return frames;
    }

    private Path writePs(String name, int fps, List<byte[]> frames, Mode mode) throws IOException {
        return writePs(name, fps, frames, mode, List.of());
    }

    private Path writePs(String name, int fps, List<byte[]> frames, Mode mode, List<byte[]> audio) throws IOException {
        Path source = tempDir.resolve(name + ".ps");
        int audioAt = 0;
        try (var out = Files.newOutputStream(source)) {
            for (int i = 0; i < frames.size(); i++) {
                if (mode == Mode.MISSING_FRAME && i == 3 * fps + 1) continue;
                long pts = PTS_BASE + i * 90_000L / fps;
                LocalDateTime time = BASE.plusNanos(i * 1_000_000_000L / fps);
                if (mode == Mode.HELD_LEAD && i > 0) pts += 11_510 * 90L - 90_000L / fps;
                if (mode == Mode.WALL_JUMP && i >= 3 * fps) time = time.plusSeconds(9);
                out.write(pack(pts));
                if (mode != Mode.NO_HK && (i % fps == 0 || mode == Mode.HELD_LEAD && i == 1)) {
                    out.write(psm(time, !audio.isEmpty()));
                }
                out.write(PsRecordingTimelineTest.pes(0xe0, pts, frames.get(i)));
                long nextVideoPts = PTS_BASE + (i + 1) * 90_000L / fps;
                while (audioAt < audio.size() && PTS_BASE + audioAt * 1024L * 90_000 / 8000 < nextVideoPts) {
                    long audioPts = PTS_BASE + audioAt * 1024L * 90_000 / 8000;
                    out.write(pack(audioPts));
                    out.write(PsRecordingTimelineTest.pes(0xc0, audioPts, audio.get(audioAt++)));
                }
            }
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        return source;
    }

    private List<byte[]> generateAacAccessUnits() throws Exception {
        Path elementary = tempDir.resolve("tone.aac");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "sine=frequency=880:sample_rate=8000:duration=6", "-c:a", "aac", "-b:a", "32k", "-f", "adts",
                elementary.toString()), 30);
        byte[] bytes = Files.readAllBytes(elementary);
        List<byte[]> frames = new ArrayList<>();
        for (int at = 0; at < bytes.length;) {
            assertTrue(at + 7 <= bytes.length && (bytes[at] & 0xff) == 0xff && (bytes[at + 1] & 0xf6) == 0xf0);
            int length = ((bytes[at + 3] & 3) << 11) | ((bytes[at + 4] & 0xff) << 3) | ((bytes[at + 5] & 0xff) >>> 5);
            assertTrue(length >= 7 && at + length <= bytes.length);
            frames.add(Arrays.copyOfRange(bytes, at, at + length));
            at += length;
        }
        return frames;
    }

    private static byte[] psm(LocalDateTime time, boolean audio) {
        byte[] videoOnly = PsRecordingTimelineTest.psm(time);
        if (!audio) return videoOnly;
        byte[] map = Arrays.copyOf(videoOnly, videoOnly.length + 4);
        map[5] += 4; map[27] = 8;
        map[32] = 0x0f; map[33] = (byte) 0xc0;
        return map;
    }

    private MediaSession session(Path source) {
        MediaSession session = new MediaSession(source.getFileName().toString(), SessionType.DOWNLOAD, "fixture", 1);
        session.setRawFilePath(source.toString());
        session.setFilePath(source.resolveSibling(source.getFileName() + ".mp4").toString());
        return session;
    }

    private List<String> frameHashes(Path file) throws Exception {
        String text = ExactDurationTrimmer.run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-threads", "1", "-i", file.toString(), "-map", "0:v:0", "-vf", "scale=2:2,format=rgb24",
                "-fps_mode", "passthrough", "-threads", "1", "-f", "framemd5", "-"), 30);
        return text.lines().filter(line -> line.matches("^\\d+,.*"))
                .map(line -> line.substring(line.lastIndexOf(',') + 1).trim()).toList();
    }

    private static byte[] pack(long scr) {
        byte[] h = PsRecordingTimelineTest.pack();
        h[4] = (byte) (0x44 | ((scr >>> 27) & 0x38) | ((scr >>> 28) & 3));
        h[5] = (byte) (scr >>> 20); h[6] = (byte) (4 | ((scr >>> 12) & 0xf8) | ((scr >>> 13) & 3));
        h[7] = (byte) (scr >>> 5); h[8] = (byte) (4 | ((scr << 3) & 0xf8));
        return h;
    }

    private enum Mode { NORMAL, HELD_LEAD, WALL_JUMP, NO_HK, MISSING_FRAME }
}
