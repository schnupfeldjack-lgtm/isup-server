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

/** Full decoding is acceptance testing only; the production processor copies packets. */
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
