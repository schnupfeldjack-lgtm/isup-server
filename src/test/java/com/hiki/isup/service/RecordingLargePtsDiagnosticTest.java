package com.hiki.isup.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 仅合成画面：隔离大绝对 PTS、非整秒钟点和 G.711 音频对裁切帧数的影响。 */
class RecordingLargePtsDiagnosticTest {
    private static final long PTS_BASE = 4_572_041_546L; // 50800.461622222... seconds
    private static final int FPS = 20;
    private static final LocalDateTime WALL = LocalDateTime.parse("2026-10-15T09:30:49.565");
    @TempDir Path tempDir;
    private String ffmpeg;

    @BeforeEach void requireFfmpeg() {
        ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!ffmpeg.isBlank() && Files.isExecutable(Path.of(ffmpeg)), "需要 -Disup.test.ffmpeg");
    }

    @Test void largeFractionalPtsWithG711MustKeepAllSevenHundredSixtySelectedFrames() throws Exception {
        runFixture(PTS_BASE, WALL, 40, 800, false);
    }

    @Test void exactProductionOriginWithG711MustKeepAllSevenHundredSixtySelectedFrames() throws Exception {
        runFixture(4_571_601_986L, LocalDateTime.parse("2026-10-15T09:30:44.681"), 56, 1119, false);
    }

    @Test void zeroDurationAndUnknownFrameRateMustNotHideTheLastSelectedFrame() throws Exception {
        runFixture(4_571_601_986L, LocalDateTime.parse("2026-10-15T09:30:44.681"), 56, 1119, true);
    }

    @ParameterizedTest
    @CsvSource({"5,1", "8,1", "10,1", "20,1", "25,1", "30000,1001"})
    void zeroDurationBFramesKeepTheirOwnPtsAcrossSupportedCadences(int numerator, int denominator) throws Exception {
        String rate = numerator + "/" + denominator;
        Path source = tempDir.resolve("bframes-" + numerator + "-" + denominator + ".mp4");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=s=160x96:r=" + rate + ":d=4", "-an",
                "-c:v", "libx264", "-preset", "veryfast", "-bf", "2", "-x264-params", "b-adapt=0:b-pyramid=none",
                "-threads", "1", "-video_track_timescale", "90000", source.toString()), 30);
        String pictures = ExactDurationTrimmer.run(List.of(ffmpeg, "-hide_banner", "-loglevel", "info",
                "-threads", "1", "-i", source.toString(), "-vf", "showinfo=checksum=0", "-an",
                "-fps_mode:v", "passthrough", "-f", "null", "-"), 30);
        assertTrue(pictures.contains("type:B"), "测试输入必须真正含有B帧");
        var decoded = RecordingFrameProbe.read(ffmpeg, source, 30);
        List<Double> selected = decoded.validateWindow(1, 3);
        Path output = tempDir.resolve("bframes-output.mp4");
        List<String> command = new ArrayList<>(ExactDurationTrimmer.timelineTrimCommand(ffmpeg, 1, source, output,
                1, 3, selected.getFirst(), decoded.declaredInterval()));
        int vf = command.indexOf("-vf") + 1;
        command.set(vf, command.get(vf) + ":strip_fps=1");
        ExactDurationTrimmer.run(command, 30);
        var actual = RecordingFrameProbe.read(ffmpeg, output, 30);
        assertEquals(selected.size(), actual.video().size(), "零duration不能隐藏最后一帧: rate=" + rate);
        actual.validateOutput(selected, false);
    }

    private void runFixture(long ptsBase, LocalDateTime wall, int seconds, int frameCount, boolean emulateFfmpeg7) throws Exception {
        Path elementary = tempDir.resolve("large-pts.h264");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "color=c=green:s=1920x1080:r=20:d=" + seconds, "-an", "-c:v", "libx264",
                "-preset", "ultrafast", "-crf", "23", "-bf", "0", "-g", "80", "-keyint_min", "80",
                "-sc_threshold", "0", "-x264-params", "aud=1:repeat-headers=1", "-threads", "1",
                "-f", "h264", elementary.toString()), 90);
        List<byte[]> video = accessUnits(Files.readAllBytes(elementary));
        assertEquals(FPS * seconds, video.size());
        video = video.subList(0, frameCount);
        Path alaw = tempDir.resolve("large-pts.alaw");
        ExactDurationTrimmer.run(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=8000:duration=" + seconds,
                "-c:a", "pcm_alaw", "-f", "alaw", alaw.toString()), 30);
        byte[] audio = Files.readAllBytes(alaw);
        Path source = tempDir.resolve("large-pts-g711.ps");
        try (var out = Files.newOutputStream(source)) {
            int audioAt = 0;
            for (int i = 0; i < video.size(); i++) {
                long pts = ptsBase + i * 4500L;
                out.write(pack(pts));
                if (i % 20 == 0) out.write(psm(wall.plusSeconds(i / 20)));
                out.write(PsRecordingTimelineTest.pes(0xe0, pts, video.get(i)));
                while (audioAt * 320 < audio.length && ptsBase + audioAt * 3600L < pts + 4500) {
                    out.write(PsRecordingTimelineTest.pes(0xc0, ptsBase + audioAt * 3600L,
                            Arrays.copyOfRange(audio, audioAt * 320, ++audioAt * 320)));
                }
            }
            out.write(new byte[]{0, 0, 1, (byte) 0xb9});
        }
        var timeline = PsRecordingTimeline.parse(source);
        var window = timeline.plan(LocalDateTime.parse("2026-10-15T09:30:50"), LocalDateTime.parse("2026-10-15T09:31:28"));
        assertEquals(50800.89662222222, window.startPtsSeconds(), 1e-9);
        assertEquals(50838.89662222222, window.endPtsSeconds(), 1e-9);
        var decoded = RecordingFrameProbe.read(ffmpeg, source, 90);
        decoded.verifyAnchors(window.anchors());
        List<Double> selected = decoded.validateWindow(window.startPtsSeconds(), window.endPtsSeconds());
        assertEquals(760, selected.size());
        Path output = tempDir.resolve("large-pts-output.mp4");
        List<String> command = new ArrayList<>(ExactDurationTrimmer.timelineTrimCommand(ffmpeg, 1, source, output,
                window.startPtsSeconds(), window.endPtsSeconds(), selected.getFirst(), decoded.declaredInterval()));
        if (emulateFfmpeg7) {
            // FFmpeg 7 setpts 无条件清掉帧率和 duration；9 的 strip_fps=1 等效模拟该元数据行为。
            int vf = command.indexOf("-vf") + 1;
            command.set(vf, command.get(vf) + ":strip_fps=1");
        }
        ExactDurationTrimmer.run(command, 180);
        var actual = RecordingFrameProbe.read(ffmpeg, output, 90);
        System.out.printf(java.util.Locale.ROOT,
                "LARGE_PTS_DIAG sourceFirst=%.12f sourceLast=%.12f start=%.12f end=%.12f selected=%d firstSelected=%.12f lastSelected=%.12f actual=%d firstActual=%.12f lastActual=%.12f audio=%d%n",
                decoded.video().getFirst(), decoded.video().getLast(), window.startPtsSeconds(), window.endPtsSeconds(),
                selected.size(), selected.getFirst(), selected.getLast(), actual.video().size(), actual.video().getFirst(),
                actual.video().getLast(), actual.audio().size());
        assertDoesNotThrow(() -> actual.validateOutput(selected, true));
    }

    private static List<byte[]> accessUnits(byte[] data) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            int prefix = data[i + 2] == 1 ? 3 : data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
            if (prefix > 0) {
                if ((data[i + prefix] & 0x1f) == 9) starts.add(i);
                i += prefix - 1;
            }
        }
        assertEquals(0, starts.getFirst());
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++)
            frames.add(Arrays.copyOfRange(data, starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : data.length));
        return frames;
    }
    private static byte[] psm(LocalDateTime wall) {
        byte[] video = PsRecordingTimelineTest.psm(wall);
        byte[] map = Arrays.copyOf(video, video.length + 4);
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
}
