package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static com.hiki.isup.service.PsRecordingTimelineTest.pes;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真正 H264 POC type 0、I/P 无重排、可变 PTS 的 PS；也可在生产同版 FFmpeg 离线执行。 */
class RecordingVfrIntegrationTest {
    private static final long ORIGIN = 5_302_200_000L;
    private static final LocalDateTime WALL = LocalDateTime.of(2026, 10, 15, 11, 46, 0);
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temp;
    private String ffmpeg;

    @BeforeEach void ffmpeg() {
        ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!ffmpeg.isBlank() && Files.isExecutable(Path.of(ffmpeg)), "需要 -Disup.test.ffmpeg");
    }

    @Test void realVariablePsClipsBothBoundaryPicturesWithoutDroppingOrDuplicatingThem() throws Exception {
        Path source = source(false, false);
        System.out.println("VFR_FIXTURE=" + source);
        byte[] original = Files.readAllBytes(source);
        var timeline = PsRecordingTimeline.parse(source);
        var window = timeline.plan(WALL.plusNanos(1_010_000_000), WALL.plusNanos(2_490_000_000L));
        var decoded = RecordingFrameProbe.read(ffmpeg, source, 30);
        assertThrows(IOException.class, () -> decoded.validateWindow(window.startPtsSeconds(), window.endPtsSeconds()));
        var proof = RecordingH264ContinuityProbe.readUntil(ffmpeg, source, decoded.video(), RecordingDeadline.afterSeconds(30), temp);
        var selected = decoded.selectWindow(window.startPtsSeconds(), window.endPtsSeconds(), () -> proof);
        assertTrue(selected.variable());
        assertEquals(ORIGIN / 90000d + .96, selected.source().getFirst(), 1e-8);
        assertEquals(ORIGIN / 90000d + 2.48, selected.source().getLast(), 1e-8);
        Path output = trimmer().trim(session(source), WALL.plusNanos(1_010_000_000), WALL.plusNanos(2_490_000_000L));
        var actual = RecordingFrameProbe.read(ffmpeg, output, 30);
        actual.validateOutput(selected, false);
        assertEquals(0, actual.video().getFirst(), 1e-8);
        assertEquals(1.47, actual.video().getLast(), 1e-5);
        assertEquals(.01, actual.durations().getLast(), 1e-5);
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void continuousAudioUsesTheBusinessOriginAlongsideVariableVideo() throws Exception {
        Path source = source(false, true);
        Path output = trimmer().trim(session(source), WALL.plusNanos(1_010_000_000), WALL.plusNanos(2_490_000_000L));
        var actual = RecordingFrameProbe.read(ffmpeg, output, 30);
        assertFalse(actual.audio().isEmpty());
        assertEquals(0, actual.audio().getFirst().start(), .15);
        assertTrue(actual.audio().getLast().end() >= 1.48 - .15);
        assertTrue(actual.audio().getLast().end() <= 1.48 + .15, "AAC padding 不能延长业务音轨超过既有边界容差");
        System.out.printf(java.util.Locale.ROOT, "VFR_AV videoEnd=%.9f audioStart=%.9f audioEnd=%.9f%n",
                actual.video().getLast() + actual.durations().getLast(), actual.audio().getFirst().start(), actual.audio().getLast().end());
        assertEquals(1.48, actual.video().getLast() + actual.durations().getLast(), .0001);
    }

    @Test void removingAnAlreadyEncodedPictureCannotMasqueradeAsVfr() throws Exception {
        Path source = source(true, false);
        assertThrows(IOException.class, () -> trimmer().trim(session(source), WALL.plusNanos(1_010_000_000), WALL.plusNanos(2_490_000_000L)));
        assertFalse(Files.exists(ExactDurationTrimmer.exactPath(Path.of(session(source).getFilePath()))));
    }

    private Path source(boolean missingPicture, boolean audio) throws Exception {
        Path elementary = temp.resolve("type0.h264");
        // -bf 1 保留 POC type 0；强烈负 B bias 让这个受控样本实际只产生 I/P 图片。
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                "testsrc2=size=64x64:rate=25:duration=4", "-c:v", "libx264", "-preset", "medium", "-bf", "1",
                "-b-bias", "-100", "-b_strategy", "2", "-g", "100", "-sc_threshold", "0", "-threads", "1",
                "-x264-params", "aud=1:repeat-headers=1", "-f", "h264", elementary.toString()), 30);
        // x264 -bf 1 保留 type 0，也声明一个并未使用的重排槽；将该测试声明精确改为0。
        // 不改任何编码图片。否则 FFmpeg 会仅凭该槽合成首包 DTS=PTS-一帧。
        String trace = ExactDurationTrimmer.run(List.of(ffmpeg, "-hide_banner", "-loglevel", "info", "-i", elementary.toString(),
                "-c:v", "copy", "-bsf:v", "filter_units=remove_types=6,trace_headers", "-frames:v", "1", "-f", "null", "-"), 30);
        var location = Pattern.compile("\\]\\s+(\\d+)\\s+max_num_reorder_frames\\s+010\\s*=\\s*1").matcher(trace);
        assertTrue(location.find());
        Files.write(elementary, removeUnusedReorderSlot(Files.readAllBytes(elementary), Integer.parseInt(location.group(1))));
        List<byte[]> units = accessUnits(Files.readAllBytes(elementary));
        assertEquals(100, units.size());
        Path source = temp.resolve("variable.ps");
        try (var out = Files.newOutputStream(source)) {
            long audioIndex = 0;
            for (int i = 0; i < units.size(); i++) {
                long offset = i * 3600L + (i >= 25 ? 3600 : 0) + (i >= 60 ? 3600 : 0);
                long pts = ORIGIN + offset;
                out.write(pack(pts));
                if (i % 25 == 0) out.write(psm(WALL.plusNanos(offset * 1_000_000_000L / 90000), audio));
                if (!missingPicture || i != 40) out.write(PsRecordingTimelineTest.pesDts(0xe0, pts, pts, units.get(i)));
                if (audio) while (audioIndex * 1800 < offset + 3600) {
                    byte[] samples = new byte[160]; Arrays.fill(samples, (byte) 0xd5);
                    out.write(pes(0xc0, ORIGIN + audioIndex++ * 1800, samples));
                }
            }
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        return source;
    }

    private ExactDurationTrimmer trimmer() {
        IsupProperties properties = new IsupProperties();
        properties.setFfmpegPath(ffmpeg);
        return new ExactDurationTrimmer(properties);
    }
    private MediaSession session(Path source) {
        MediaSession session = new MediaSession("vfr", SessionType.DOWNLOAD, "fixture", 1);
        session.setRawFilePath(source.toString()); session.setFilePath(temp.resolve("result.mp4").toString());
        return session;
    }
    private static byte[] psm(LocalDateTime wall, boolean audio) {
        byte[] map = PsRecordingTimelineTest.psm(wall);
        if (!audio) return map;
        byte[] result = Arrays.copyOf(map, map.length + 4);
        result[5] += 4; result[27] = 8; result[32] = (byte) 0x90; result[33] = (byte) 0xc0;
        return result;
    }
    private static byte[] pack(long pts) {
        return new byte[]{0, 0, 1, (byte) 0xba, (byte) (0x44 | ((pts >>> 27) & 0x38) | ((pts >>> 28) & 3)),
                (byte) (pts >>> 20), (byte) (((pts >>> 12) & 0xf8) | 4 | ((pts >>> 13) & 3)),
                (byte) (pts >>> 5), (byte) (((pts & 31) << 3) | 4), 1, 0, 0, 3, (byte) 0xf8};
    }
    private static List<byte[]> accessUnits(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            int prefix = data[i + 2] == 1 ? 3 : data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
            if (prefix > 0) {
                if ((data[i + prefix] & 31) == 9) starts.add(i);
                i += prefix - 1;
            }
        }
        List<byte[]> result = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) result.add(Arrays.copyOfRange(data, starts.get(i),
                i + 1 < starts.size() ? starts.get(i + 1) : data.length));
        return result;
    }

    private static byte[] removeUnusedReorderSlot(byte[] input, int bitOffset) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < input.length; i++) {
            if (input[i] == 0 && input[i + 1] == 0 && (input[i + 2] == 1 || input[i + 2] == 0 && input[i + 3] == 1)) {
                starts.add(i); i += input[i + 2] == 1 ? 2 : 3;
            }
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i), end = i + 1 < starts.size() ? starts.get(i + 1) : input.length;
            int prefix = input[from + 2] == 1 ? 3 : 4;
            if ((input[from + prefix] & 31) != 7) { out.write(input, from, end - from); continue; }
            StringBuilder bits = new StringBuilder();
            int zeros = 0;
            for (int at = from + prefix; at < end; at++) {
                int value = input[at] & 255;
                if (zeros == 2 && value == 3) { zeros = 0; continue; }
                bits.append(String.format("%8s", Integer.toBinaryString(value)).replace(' ', '0'));
                zeros = value == 0 ? zeros + 1 : 0;
            }
            assertEquals("010", bits.substring(bitOffset, bitOffset + 3));
            bits.replace(bitOffset, bitOffset + 3, "1");
            while (bits.charAt(bits.length() - 1) == '0') bits.setLength(bits.length() - 1);
            while (bits.length() % 8 != 0) bits.append('0');
            out.write(input, from, prefix); zeros = 0;
            for (int at = 0; at < bits.length(); at += 8) {
                int value = Integer.parseInt(bits.substring(at, at + 8), 2);
                if (zeros == 2 && value <= 3) { out.write(3); zeros = 0; }
                out.write(value); zeros = value == 0 ? zeros + 1 : 0;
            }
        }
        return out.toByteArray();
    }
}
