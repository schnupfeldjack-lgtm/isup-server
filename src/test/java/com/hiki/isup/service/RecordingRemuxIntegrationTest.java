package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Delivery copies encoded pictures; acceptance fully decodes without re-encoding the artifact. */
class RecordingRemuxIntegrationTest {
    private static final LocalDateTime WALL = LocalDateTime.of(2026, 10, 15, 9, 0);
    private static final long ORIGIN = 5_300_000_000L;
    @TempDir Path temp;
    private String ffmpeg;

    @BeforeEach void requireFfmpeg() {
        ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!ffmpeg.isBlank() && Files.isExecutable(Path.of(ffmpeg)), "requires isup.test.ffmpeg");
    }

    @Test void invalidPrivateClockAndVariableFramesDoNotPreventSourceFaithfulPackaging() throws Exception {
        Path source = ps(false, true, false);
        byte[] before = Files.readAllBytes(source);
        assertThrows(java.io.IOException.class, () -> PsRecordingTimeline.parse(source));
        Path result = remux(source);
        assertEquals(decodedHashes(source), decodedHashes(result), "Every encoded source image must survive without recompression");
        assertArrayEquals(before, Files.readAllBytes(source));
        assertTrue(Files.size(result) > 1000);
        assertVideoOnly(result);
    }

    @Test void g711AudioIsOmittedWithoutLeavingElevenSecondsOfBlackAtTheStart() throws Exception {
        Path source = ps(true, false, false);
        byte[] before = Files.readAllBytes(source);
        Path result = remux(source);
        String probe = firstPacket(result);
        assertTrue(probe.contains("#codec_id 0: h264"), probe);
        long firstPts = packetRows(probe).getFirst()[2];
        assertTrue(firstPts >= 0 && firstPts <= 9000, "first video must begin within 100ms: " + probe);
        assertEquals(decodedHashes(source), decodedHashes(result));
        assertVideoOnly(result);
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test void truncatedTransportTailIsExcludedWithoutChangingTheCompletePictures() throws Exception {
        Path source = ps(false, false, true);
        Path reference = temp.resolve("complete.ps");
        Path result = remux(source);
        assertEquals(decodedHashes(reference), decodedHashes(result));
        String decode = run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-xerror", "-i", result.toString(),
                "-map", "0:v:0", "-an", "-f", "null", "-"));
        assertTrue(decode.isBlank(), decode);
        assertVideoOnly(result);
    }

    @Test void completePesWithTruncatedH264TailCannotPassPacketDurationAsPlayableVideo() throws Exception {
        Path complete = ps(false, false, false);
        byte[] original = Files.readAllBytes(complete);
        List<byte[]> frames = units(Files.readAllBytes(temp.resolve("source.h264")));
        byte[] lastFrame = frames.getLast();
        byte[] damagedFrame = Arrays.copyOf(lastFrame, Math.max(12, lastFrame.length / 2));
        Path damaged = temp.resolve("complete-pes-bad-h264.ps");
        try (var out = Files.newOutputStream(damaged)) {
            out.write(original, 0, original.length - 4);
            // PES length matches the bytes present: only the encoded H264 picture is incomplete.
            out.write(PsRecordingTimelineTest.pesDts(0xe0, ORIGIN + 150 * 3600L,
                    ORIGIN + 150 * 3600L, damagedFrame));
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        byte[] before = Files.readAllBytes(damaged);
        Path oldOutput = temp.resolve("old-packet-accepted.mp4");
        run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-copyts", "-start_at_zero",
                "-fflags", "+discardcorrupt", "-i", damaged.toString(), "-map", "0:v:0", "-c:v", "copy",
                "-an", "-movflags", "+faststart", oldOutput.toString()));
        String packetProgress = run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-i",
                oldOutput.toString(), "-map", "0:v:0", "-c:v", "copy", "-progress", "pipe:1", "-f", "null", "-"));
        long micros = packetProgress.lines().filter(line -> line.startsWith("out_time_us="))
                .mapToLong(line -> Long.parseLong(line.substring("out_time_us=".length()))).max().orElseThrow();
        assertTrue(packetProgress.contains("progress=end"));
        assertTrue(micros >= 5_000_000 && micros <= 55_000_000,
                "旧 copy 包扫描时长满足当前业务门槛：" + packetProgress);
        var decodeFailure = assertThrows(java.io.IOException.class, () -> run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-xerror", "-err_detect", "explode", "-threads", "1", "-i", oldOutput.toString(),
                "-map", "0:v:0", "-an", "-threads", "1", "-fps_mode", "passthrough", "-enc_time_base", "demux", "-f", "null", "-")),
                "样本必须真实包含可复现的 H264 解码错误");
        assertTrue(decodeFailure.getMessage().contains("Error while decoding")
                        || decodeFailure.getMessage().contains("error while decoding")
                        || decodeFailure.getMessage().contains("error decoding")
                        || decodeFailure.getMessage().contains("decode_slice_header error")
                        || decodeFailure.getMessage().contains("bytestream"),
                "必须是媒体解码失败而非命令或环境错误：" + decodeFailure.getMessage());

        assertThrows(java.io.IOException.class, () -> remux(damaged));

        assertArrayEquals(before, Files.readAllBytes(damaged), "拒绝成片不能修改原始证据");
        assertFalse(Files.exists(temp.resolve("recording-playable.mp4")), "坏成片须清理");
    }

    @Test void compatibleAacIsAlsoOmittedWhileH264PicturesRemainUnchanged() throws Exception {
        Path source = temp.resolve("aac.mp4");
        run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc2=size=64x64:rate=25:duration=6", "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
                "-c:v", "libx264", "-threads", "1", "-bf", "0", "-c:a", "aac", "-shortest", source.toString()));
        byte[] before = Files.readAllBytes(source);
        Path result = remux(source);
        assertEquals(decodedHashes(source), decodedHashes(result));
        assertVideoOnly(result);
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    private Path repairableSource() throws Exception {
        ps(false, false, false);
        List<byte[]> frames = units(Files.readAllBytes(temp.resolve("source.h264")));
        Path reference = temp.resolve("anchored-reference.ps");
        try (var out = Files.newOutputStream(reference)) {
            out.write(PsRecordingTimelineTest.pack());
            for (int i = 0; i < frames.size() - 1; i++) {
                if (i % 25 == 0) out.write(PsRecordingTimelineTest.psm(WALL.plusSeconds(i / 25)));
                out.write(PsRecordingTimelineTest.pesDts(0xe0, ORIGIN + i * 3600L, ORIGIN + i * 3600L, frames.get(i)));
            }
        }
        byte[] frame = frames.getLast();
        int slice = -1;
        for (int i = 0; i + 4 < frame.length; i++) {
            if (frame[i] == 0 && frame[i + 1] == 0 && frame[i + 2] == 1 && (frame[i + 3] & 31) == 1) {
                slice = i; break;
            }
        }
        assertTrue(slice >= 0);
        byte[] nal = Arrays.copyOfRange(frame, slice, frame.length);
        assertTrue(nal.length > 40);
        Path source = temp.resolve("truncated-continuation.ps");
        try (var out = Files.newOutputStream(source)) {
            out.write(Files.readAllBytes(reference));
            out.write(PsRecordingTimelineTest.pesDts(0xe0, ORIGIN + 149 * 3600L,
                    ORIGIN + 149 * 3600L, Arrays.copyOf(nal, nal.length / 2)));
            byte[] continuation = PsRecordingDuplicateAccessUnitTest.untimedPes(0xe0,
                    Arrays.copyOfRange(nal, nal.length / 2, nal.length));
            out.write(continuation, 0, 10); // Valid PES header plus partial slice; actual EOF omits remaining bytes.
        }
        return source;
    }

    @Test void provenBusinessExternalWholeAuRepairPreservesEveryCompleteSourcePicture() throws Exception {
        Path source = repairableSource();
        byte[] before = Files.readAllBytes(source);
        var rawError = assertThrows(java.io.IOException.class, () -> run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-xerror", "-err_detect", "explode", "-threads", "1", "-i", source.toString(), "-an", "-f", "null", "-")));
        assertTrue(rawError.getMessage().contains("bytestream") || rawError.getMessage().contains("error while decoding"), rawError.getMessage());
        Path result = remux(source);
        assertEquals(decodedHashes(temp.resolve("anchored-reference.ps")), decodedHashes(result), "Only the proven business-external incomplete AU may be removed");
        assertArrayEquals(before, Files.readAllBytes(source));
        try (var files = Files.list(temp)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("recording-tail-"))); }
    }

    @Test void secondDecodeFailureHasNoFurtherRepairAndCleansOutputAndPrefix() throws Exception {
        Path source = repairableSource();
        byte[] original = Files.readAllBytes(source);
        java.io.IOException second = new java.io.IOException("命令失败 exit=183 [h264 @ test] bytestream -5");
        assertOneShotFailure(source, second, false);
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void secondDecodeTimeoutKeepsOriginalAndCleansArtifacts() throws Exception {
        Path source = repairableSource();
        byte[] original = Files.readAllBytes(source);
        assertOneShotFailure(source, new java.net.SocketTimeoutException("shared processing deadline"), false);
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void sameSizeOriginalMutationAfterRepairRemuxPreventsSuccess() throws Exception {
        assertOneShotFailure(repairableSource(), null, true);
    }

    private void assertOneShotFailure(Path source, java.io.IOException second, boolean mutate) throws Exception {
        IsupProperties properties = new IsupProperties(); properties.setFfmpegPath(ffmpeg);
        MediaSession session = new MediaSession("one-shot-failure", SessionType.DOWNLOAD, "fixture", 1);
        session.setRawFilePath(source.toString()); session.setStartTime(WALL.minusSeconds(5)); session.setEndTime(WALL.plusSeconds(20));
        long deadline = RecordingDeadline.afterSeconds(30);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        RecordingRemuxer remuxer = new RecordingRemuxer(properties, (command, actualDeadline, parent) -> {
            assertEquals(deadline, actualDeadline);
            int call = calls.incrementAndGet();
            assertTrue(call <= 4, "No third attempt");
            if (call == 4 && second != null) throw second;
            String result = ExactDurationTrimmer.runUntil(command, actualDeadline, parent);
            if (call == 3 && mutate) {
                byte[] bytes = Files.readAllBytes(source); bytes[bytes.length - 1] ^= 1; Files.write(source, bytes);
            }
            return result;
        });
        var failure = assertThrows(java.io.IOException.class, () -> remuxer.remux(session, WALL, WALL.plusSeconds(5), deadline));
        if (second != null) assertSame(second, failure);
        assertEquals(4, calls.get());
        assertEquals(1, failure.getSuppressed().length, "The initial strict decode failure remains available");
        assertTrue(RecordingRemuxer.isCodecFailure((java.io.IOException) failure.getSuppressed()[0]));
        assertFalse(Files.exists(temp.resolve("recording-playable.mp4")));
        try (var files = Files.list(temp)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("recording-tail-"))); }
    }

    private Path remux(Path source) throws Exception {
        IsupProperties properties = new IsupProperties(); properties.setFfmpegPath(ffmpeg);
        MediaSession session = new MediaSession("remux-it", SessionType.DOWNLOAD, "fixture", 1);
        session.setRawFilePath(source.toString()); session.setFilePath(temp.resolve("download.mp4").toString());
        session.setStartTime(WALL.minusSeconds(5)); session.setEndTime(WALL.plusSeconds(20));
        Path result = new RecordingRemuxer(properties).remux(session, WALL, WALL.plusSeconds(5), RecordingDeadline.afterSeconds(30));
        assertNotNull(result, "Remux must produce a playable artifact");
        return result;
    }

    private Path ps(boolean audioLead, boolean badHk, boolean tail) throws Exception {
        Path elementary = temp.resolve("source.h264");
        run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc2=size=64x64:rate=25:duration=6", "-c:v", "libx264", "-threads", "1", "-bf", "0",
                "-x264-params", "aud=1:keyint=25:min-keyint=25:scenecut=0:repeat-headers=1", "-f", "h264", elementary.toString()));
        List<byte[]> frames = units(Files.readAllBytes(elementary));
        assertEquals(150, frames.size());
        byte[] psm = PsRecordingTimelineTest.psm(WALL);
        if (badHk) psm[17] = 0; // Invalid HK month; MPEG stream map and video remain valid.
        if (audioLead) {
            psm = Arrays.copyOf(psm, psm.length + 4);
            psm[5] += 4; psm[27] = 8; psm[32] = (byte) 0x90; psm[33] = (byte) 0xc0;
        }
        Path source = temp.resolve("complete.ps");
        try (var out = Files.newOutputStream(source)) {
            out.write(PsRecordingTimelineTest.pack()); out.write(psm);
            long audioPts = ORIGIN - 11 * 90_000L;
            for (int i = 0; i < frames.size(); i++) {
                long pts = ORIGIN + i * 3600L + (badHk && i >= 30 ? 3600 : 0) + (badHk && i >= 90 ? 3600 : 0);
                if (audioLead) while (audioPts < pts) {
                    byte[] pcm = new byte[320]; Arrays.fill(pcm, (byte) 0xd5);
                    out.write(PsRecordingTimelineTest.pes(0xc0, audioPts, pcm)); audioPts += 3600;
                }
                out.write(PsRecordingTimelineTest.pesDts(0xe0, pts, pts, frames.get(i)));
            }
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        if (!tail) return source;
        byte[] partial = PsRecordingTimelineTest.pesDts(0xe0, ORIGIN + 150 * 3600L, ORIGIN + 150 * 3600L, frames.getLast());
        Path damaged = temp.resolve("partial.ps");
        try (var out = Files.newOutputStream(damaged)) {
            byte[] complete = Files.readAllBytes(source); out.write(complete, 0, complete.length - 4);
            out.write(partial, 0, partial.length - 20);
        }
        return damaged;
    }

    private static List<byte[]> units(byte[] bytes) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < bytes.length; i++) {
            if (bytes[i] != 0 || bytes[i + 1] != 0) continue;
            int prefix = bytes[i + 2] == 1 ? 3 : bytes[i + 2] == 0 && bytes[i + 3] == 1 ? 4 : 0;
            if (prefix > 0) { if ((bytes[i + prefix] & 31) == 9) starts.add(i); i += prefix - 1; }
        }
        List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) result.add(Arrays.copyOfRange(bytes, starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : bytes.length));
        return result;
    }

    private List<String> decodedHashes(Path file) throws Exception {
        return hashes(run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-threads", "1", "-i", file.toString(),
                "-map", "0:v:0", "-an", "-fps_mode", "passthrough", "-f", "framemd5", "pipe:1")));
    }
    private void assertVideoOnly(Path file) throws Exception {
        String streams = run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-i", file.toString(),
                "-map", "0", "-c", "copy", "-t", "0.1", "-f", "framehash", "pipe:1"));
        List<String> types = streams.lines().filter(line -> line.startsWith("#media_type")).toList();
        assertEquals(1, types.size(), "Output must contain only the video stream: " + streams);
        assertTrue(types.getFirst().endsWith(": video"), streams);
    }
    private String firstPacket(Path file) throws Exception {
        return run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-copyts", "-i", file.toString(),
                "-map", "0:v:0", "-c:v", "copy", "-frames:v", "1", "-f", "framehash", "pipe:1"));
    }
    private static List<String> hashes(String output) {
        return output.lines().filter(line -> line.matches("\\d+,.*")).map(line -> line.substring(line.lastIndexOf(',') + 1).trim()).toList();
    }
    private static List<long[]> packetRows(String output) {
        return output.lines().filter(line -> line.matches("\\d+,.*")).map(line -> Arrays.stream(line.split(",")).limit(5).map(String::trim).mapToLong(Long::parseLong).toArray()).toList();
    }
    private String run(List<String> command) throws Exception { return ExactDurationTrimmer.run(command, 30); }
}
