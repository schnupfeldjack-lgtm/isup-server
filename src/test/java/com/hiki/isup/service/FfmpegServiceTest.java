package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FFmpeg 命令构建测试（不实际执行 ffmpeg）。
 */
class FfmpegServiceTest {

    @Test void downloadDoesNotFlushAnUnconfirmedTerminalHevcFrame(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        String executable = System.getProperty("isup.test.ffmpeg", "");
        String source = System.getProperty("isup.test.tailSource", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!executable.isBlank() && !source.isBlank());
        IsupProperties properties = properties();
        properties.setFfmpegPath(executable);
        properties.getFfmpeg().setMp4CodecArgs("-map 0:v:0 -c:v copy -an");
        Path output = temp.resolve("tail.mp4");
        FfmpegProcess writer = new FfmpegService().startMp4(properties, output, "hevc-tail");
        try {
            try (var input = java.nio.file.Files.newInputStream(Path.of(source))) {
                byte[] buffer = new byte[8191]; int n;
                while ((n = input.read(buffer)) >= 0) writer.write(buffer, 0, n);
            }
            writer.closeStdin();
            assertEquals(0, writer.waitFor(20));
            org.junit.jupiter.api.Assertions.assertFalse(writer.isWriteFailed());
            String probe = Path.of(executable).resolveSibling("ffprobe.exe").toString();
            Process count = new ProcessBuilder(probe, "-v", "error", "-select_streams", "v:0", "-count_packets",
                    "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", output.toString())
                    .redirectOutput(temp.resolve("count.txt").toFile()).redirectError(temp.resolve("probe.log").toFile()).start();
            assertTrue(count.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, count.exitValue());
            assertEquals(System.getProperty("isup.test.tailExpectedFrames", "1358"),
                    java.nio.file.Files.readString(temp.resolve("count.txt")).strip());
            String evidence = System.getProperty("isup.test.tailOutput", "");
            if (!evidence.isBlank()) java.nio.file.Files.copy(output, Path.of(evidence), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertEquals(0, writer.waitFor(2), "重复等待不能再次裁掉尾帧");
        } finally { writer.destroy(); }
    }

    @Test void packingCaptureDoesNotRunFfmpegOrCreateIntermediateMp4(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        IsupProperties properties = properties();
        properties.setFfmpegPath(temp.resolve("ffmpeg-does-not-exist").toString());
        Path raw = temp.resolve("session/recording-source.ps");
        FfmpegProcess capture = new FfmpegService().startPackingCapture(properties, raw, "capture");
        try {
            capture.write(new byte[]{0, 0, 1, (byte) 0xba}, 0, 4);
            capture.closeStdin();
            assertEquals(0, capture.waitFor(3));
            org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{0, 0, 1, (byte) 0xba}, java.nio.file.Files.readAllBytes(raw));
            try (var files = java.nio.file.Files.list(raw.getParent())) {
                assertEquals(java.util.List.of("recording-source.ps"), files.map(p -> p.getFileName().toString()).toList());
            }
        } finally { capture.destroy(); }
    }

    private IsupProperties properties() {
        IsupProperties properties = new IsupProperties();
        properties.setFfmpegPath("ffmpeg");
        return properties;
    }

    @Test
    @DisplayName("HLS 命令：PS 输入 + 不转码 + HLS 输出")
    void hlsCommand() {
        IsupProperties properties = properties();
        Path dir = Path.of("/data/media/preview/abc");

        List<String> command = FfmpegService.buildHlsCommand(properties, dir);

        assertEquals("ffmpeg", command.get(0));
        assertTrue(command.contains("-c"), "默认应包含 -c copy，不做转码");
        assertTrue(command.contains("copy"));
        assertTrue(command.contains("pipe:0"), "码流通过 stdin 输入");
        assertTrue(command.contains("mpeg"), "输入格式为 PS(mpeg)");
        assertTrue(command.contains("hls"));
        assertTrue(command.contains("-hls_time"));
        assertTrue(command.contains("2"));
        assertEquals(dir.resolve("index.m3u8").toString(), command.get(command.size() - 1));
        assertTrue(command.stream().anyMatch(a -> a.endsWith("seg_%05d.ts")), "必须指定切片文件名模板");
    }

    @Test
    @DisplayName("MP4 命令：输出为 MP4 且带 frag 参数")
    void mp4Command() {
        IsupProperties properties = properties();
        Path file = Path.of("/data/media/download/abc/video.mp4");

        List<String> command = FfmpegService.buildMp4Command(properties, file);

        assertEquals("ffmpeg", command.get(0));
        assertTrue(command.contains("-c"));
        assertTrue(command.contains("copy"));
        assertTrue(command.contains("pipe:0"));
        assertTrue(command.contains("mp4"));
        assertTrue(command.contains("+frag_keyframe+empty_moov"), "进程被中断时文件仍可播放");
        assertEquals(file.toString(), command.get(command.size() - 1));
        assertEquals(String.valueOf(RecordingStorageGuard.MAX_OUTPUT_BYTES), command.get(command.indexOf("-fs") + 1),
                "手动下载也必须带输出文件大小上限");
    }

    @Test
    @DisplayName("自定义编码参数可覆盖默认的 -c copy")
    void customCodecArgs() {
        IsupProperties properties = properties();
        properties.getFfmpeg().setCodecArgs("-c:v copy -c:a aac");

        List<String> command = FfmpegService.buildMp4Command(properties, Path.of("/tmp/a.mp4"));

        assertTrue(command.contains("-c:v"));
        assertTrue(command.contains("-c:a"));
        assertTrue(command.contains("aac"));
    }

    @Test
    @DisplayName("参数拆分：按空白切分并忽略空串")
    void splitArgs() {
        assertEquals(List.of("-c", "copy"), FfmpegService.splitArgs("-c copy"));
        assertEquals(List.of(), FfmpegService.splitArgs("   "));
        assertEquals(List.of(), FfmpegService.splitArgs(null));
        assertEquals(List.of("-f", "mp4", "-movflags", "+frag_keyframe+empty_moov"),
                FfmpegService.splitArgs("-f mp4 -movflags +frag_keyframe+empty_moov"));
    }
}
