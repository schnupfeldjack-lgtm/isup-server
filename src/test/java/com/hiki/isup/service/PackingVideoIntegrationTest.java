package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 三种颜色表示上一单、本单、下一单，验证真正编码后的每帧归属。 */
class PackingVideoIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void realEncoderRemovesNeighbouringOrdersAt20And25Fps() throws Exception {
        String ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!ffmpeg.isBlank() && Files.isExecutable(Path.of(ffmpeg)), "需要 -Disup.test.ffmpeg");
        IsupProperties properties = new IsupProperties();
        properties.setFfmpegPath(ffmpeg);
        ExactDurationTrimmer trimmer = new ExactDurationTrimmer(properties);
        for (int fps : new int[]{20, 25}) {
            Path source = tempDir.resolve("orders-" + fps + ".mp4");
            List<String> generate = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error"));
            for (String color : new String[]{"red", "green", "blue"}) {
                generate.addAll(List.of("-f", "lavfi", "-i", "color=c=" + color + ":s=160x96:r=" + fps + ":d=2"));
            }
            generate.addAll(List.of("-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]",
                    "-map", "[v]", "-c:v", "libx264", "-threads", "1", source.toString()));
            ExactDurationTrimmer.run(generate, 30);
            List<String> rawFrames = frameHashes(ffmpeg, source);
            assertEquals(3, rawFrames.stream().distinct().count(), "输入必须真实包含三单画面");

            Path output = trimmer.trim(source, 2.0, 2.0);

            List<String> frames = frameHashes(ffmpeg, output);
            assertEquals(2 * fps, frames.size(), "不追加一帧，也不能少截本单");
            assertEquals(1, frames.stream().distinct().count(), "输出不能混入相邻单画面");
            assertEquals(rawFrames.get(3 * fps), frames.get(0), "唯一画面必须属于中间本单");
        }
    }

    private static List<String> frameHashes(String ffmpeg, Path file) throws Exception {
        String text = ExactDurationTrimmer.run(List.of(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-threads", "1", "-i", file.toString(), "-map", "0:v:0", "-vf", "scale=2:2,format=rgb24",
                "-threads", "1", "-f", "framemd5", "-"), 30);
        return text.lines().filter(line -> line.matches("^\\d+,.*"))
                .map(line -> line.substring(line.lastIndexOf(',') + 1).trim()).toList();
    }
}
