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
