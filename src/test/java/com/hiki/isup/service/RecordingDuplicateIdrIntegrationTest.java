package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.hiki.isup.service.PsRecordingTimelineTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecordingDuplicateIdrIntegrationTest {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 15, 9, 19, 45, 641_000_000);
    private static final long PTS_BASE = 4_512_291_806L;
    @TempDir Path tempDir;
    private String ffmpeg;

    @BeforeEach void requireFfmpeg() {
        ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!ffmpeg.isBlank() && Files.isExecutable(Path.of(ffmpeg)), "需要 -Disup.test.ffmpeg");
    }

    @Test void repeatedIdrWithG711PreservesEverySelectedFrameAndOriginalFile() throws Exception {
        verifyRepeatedIdr(false);
    }

    @Test void repeatedIdrWithUntimedContinuationPreservesEverySelectedFrameAndOriginalFile() throws Exception {
        verifyRepeatedIdr(true);
    }

    private void verifyRepeatedIdr(boolean untimedContinuation) throws Exception {
        Path source = fixture(false, untimedContinuation);
        byte[] original = Files.readAllBytes(source);
        var timeline = PsRecordingTimeline.parse(source);
        assertEquals(untimedContinuation ? 7 : 6, timeline.duplicateVideoPackets().size());
        assertEquals(1, timeline.audioStreamCount());
        var window = timeline.plan(BASE.plusSeconds(2), BASE.plusSeconds(4));
        try (var copy = ExactDurationTrimmer.prepareDecodeSource(source, timeline, Long.MAX_VALUE)) {
            var decoded = RecordingFrameProbe.read(ffmpeg, copy.path(), 30);
            assertEquals(120, decoded.video().size(), "源副本必须恰好保留原始 120 帧");
            decoded.verifyAnchors(window.anchors());
            assertEquals(40, decoded.validateWindow(window.startPtsSeconds(), window.endPtsSeconds()).size());
            assertFalse(decoded.audio().isEmpty(), "重复视频旁的 G.711 音频必须保留并解码");
        }
        Path output = trimmer().trim(session(source), BASE.plusSeconds(2), BASE.plusSeconds(4));
        var result = RecordingFrameProbe.read(ffmpeg, output, 30);
        assertEquals(40, result.video().size());
        assertEquals(0, result.video().getFirst(), 1e-6);
        assertEquals(1.95, result.video().getLast(), 1e-6);
        assertFalse(result.audio().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void byteIdenticalButUndecodableIdrStillFailsStrictValidation() throws Exception {
        Path source = fixture(true);
        byte[] original = Files.readAllBytes(source);
        assertEquals(6, PsRecordingTimeline.parse(source).duplicateVideoPackets().size(), "结构相同不等于可解码");
        assertThrows(IOException.class, () -> trimmer().trim(session(source), BASE.plusSeconds(2), BASE.plusSeconds(4)));
        assertFalse(Files.exists(ExactDurationTrimmer.exactPath(Path.of(session(source).getFilePath()))));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    private Path fixture(boolean corrupt) throws Exception {
        return fixture(corrupt, false);
    }

    private Path fixture(boolean corrupt, boolean untimedContinuation) throws Exception {
        Path elementary = tempDir.resolve("synthetic.h264");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=s=160x96:r=20:d=6", "-an", "-c:v", "libx264",
                "-preset", "ultrafast", "-bf", "0", "-g", "20", "-keyint_min", "20", "-sc_threshold", "0",
                "-x264-params", "aud=1:repeat-headers=1", "-threads", "1", "-f", "h264", elementary.toString()), 30);
        List<byte[]> frames = unitsWithoutAudOrSei(Files.readAllBytes(elementary));
        assertEquals(120, frames.size());
        if (corrupt) {
            byte[] damaged = frames.get(60).clone();
            List<Integer> starts = starts(damaged);
            int start = starts.getFirst();
            int prefix = damaged[start + 2] == 1 ? 3 : 4;
            Arrays.fill(damaged, start + prefix + 1, starts.get(1), (byte) 0xff);
            frames.set(60, damaged);
        }
        Path source = tempDir.resolve("duplicate.ps");
        try (var out = Files.newOutputStream(source)) {
            for (int i = 0; i < frames.size(); i++) {
                long pts = PTS_BASE + i * 4500L;
                out.write(pack(pts));
                if (i % 20 == 0) out.write(audioPsm(BASE.plusSeconds(i / 20)));
                byte[] frame = frames.get(i);
                if (i == 60) {
                    byte[] packets = untimedContinuation ? sevenPes(pts, frame) : sixPes(pts, frame);
                    out.write(packets);
                    out.write(pack(pts));
                    out.write(audioPsm(BASE.plusSeconds(3)));
                    out.write(packets);
                    out.write(hikPrivatePes(8, pts));
                } else out.write(pes(0xe0, pts, frame));
                byte[] alaw = new byte[400];
                Arrays.fill(alaw, (byte) 0xd5);
                out.write(pes(0xc0, pts, alaw));
            }
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        return source;
    }

    private static byte[] sixPes(long pts, byte[] frame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 6; i++) out.writeBytes(pes(0xe0, pts,
                Arrays.copyOfRange(frame, i * frame.length / 6, (i + 1) * frame.length / 6)));
        return out.toByteArray();
    }

    private static byte[] sevenPes(long pts, byte[] frame) {
        List<Integer> starts = starts(frame);
        assertEquals(3, starts.size(), "fixture 必须为 SPS/PPS/单 IDR");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(pes(0xe0, pts, Arrays.copyOfRange(frame, 0, starts.get(1))));
        out.writeBytes(PsRecordingDuplicateAccessUnitTest.untimedPes(0xe0,
                Arrays.copyOfRange(frame, starts.get(1), starts.get(2))));
        int idr = starts.get(2), length = frame.length - idr;
        for (int i = 0; i < 5; i++) out.writeBytes(PsRecordingDuplicateAccessUnitTest.untimedPes(0xe0,
                Arrays.copyOfRange(frame, idr + i * length / 5, idr + (i + 1) * length / 5)));
        return out.toByteArray();
    }

    private static List<byte[]> unitsWithoutAudOrSei(byte[] data) {
        List<Integer> starts = starts(data);
        List<byte[]> frames = new ArrayList<>();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        for (int i = 0; i < starts.size(); i++) {
            int at = starts.get(i), end = i + 1 < starts.size() ? starts.get(i + 1) : data.length;
            int prefix = data[at + 2] == 1 ? 3 : 4;
            int type = data[at + prefix] & 31;
            if (type == 9 && frame.size() > 0) { frames.add(frame.toByteArray()); frame.reset(); }
            if (type != 9 && type != 6) frame.write(data, at, end - at);
        }
        if (frame.size() > 0) frames.add(frame.toByteArray());
        return frames;
    }

    private static List<Integer> starts(byte[] data) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            int prefix = data[i + 2] == 1 ? 3 : data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
            if (prefix > 0) { result.add(i); i += prefix - 1; }
        }
        return result;
    }

    private static byte[] audioPsm(LocalDateTime wall) {
        byte[] map = Arrays.copyOf(psm(wall), psm(wall).length + 4);
        map[5] += 4; map[27] = 8; map[32] = (byte) 0x90; map[33] = (byte) 0xc0;
        return map;
    }

    private static byte[] pack(long scr) {
        byte[] h = PsRecordingTimelineTest.pack();
        h[4] = (byte) (0x44 | ((scr >>> 27) & 0x38) | ((scr >>> 28) & 3));
        h[5] = (byte) (scr >>> 20); h[6] = (byte) (4 | ((scr >>> 12) & 0xf8) | ((scr >>> 13) & 3));
        h[7] = (byte) (scr >>> 5); h[8] = (byte) (4 | ((scr << 3) & 0xf8));
        return h;
    }

    private ExactDurationTrimmer trimmer() {
        IsupProperties properties = new IsupProperties();
        properties.setFfmpegPath(ffmpeg);
        return new ExactDurationTrimmer(properties);
    }

    private MediaSession session(Path source) {
        MediaSession session = new MediaSession("duplicate", SessionType.DOWNLOAD, "fixture", 1);
        session.setRawFilePath(source.toString());
        session.setFilePath(source.resolveSibling("synthetic.mp4").toString());
        return session;
    }
}
