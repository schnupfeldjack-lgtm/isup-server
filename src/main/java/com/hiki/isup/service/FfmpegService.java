package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * FFmpeg 封装：ISUP PS 码流 -&gt; HLS（预览/回放）或 MP4（下载）。
 *
 * <p>默认不转码（-c copy），只做封装转换，服务器 CPU 占用极低。</p>
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    /**
     * 启动 HLS 输出进程，切片写入 dir/index.m3u8。
     */
    public FfmpegProcess startHls(IsupProperties properties, Path dir, String sessionId) throws IOException {
        Files.createDirectories(dir);
        List<String> command = buildHlsCommand(properties, dir);
        return start(properties, command, dir, sessionId);
    }

    /**
     * 启动 MP4 输出进程，输出到 file。
     */
    public FfmpegProcess startMp4(IsupProperties properties, Path file, String sessionId) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> command = buildMp4Command(properties, file);
        return start(properties, command, parent, sessionId);
    }

    /**
     * 构建 HLS 命令（HLS 用于浏览器直接播放）。
     */
    public static List<String> buildHlsCommand(IsupProperties properties, Path dir) {
        IsupProperties.Ffmpeg cfg = properties.getFfmpeg();
        List<String> command = new ArrayList<>();
        command.add(properties.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(cfg.getLogLevel());
        // ISUP PS 流常缺少 PTS，让 ffmpeg 自动生成
        command.add("-fflags");
        command.add("+genpts");
        command.add("-f");
        command.add(cfg.getPsInputFormat());
        command.add("-i");
        command.add("pipe:0");
        command.addAll(splitArgs(cfg.getCodecArgs()));
        command.add("-f");
        command.add("hls");
        command.add("-hls_time");
        command.add(String.valueOf(cfg.getHlsTime()));
        command.add("-hls_list_size");
        command.add(String.valueOf(cfg.getHlsListSize()));
        command.add("-hls_flags");
        command.add(cfg.getHlsFlags());
        command.add("-hls_segment_filename");
        command.add(dir.resolve("seg_%05d.ts").toString());
        command.add(dir.resolve("index.m3u8").toString());
        return command;
    }

    /**
     * 构建 MP4 命令（录像下载）。
     */
    public static List<String> buildMp4Command(IsupProperties properties, Path file) {
        IsupProperties.Ffmpeg cfg = properties.getFfmpeg();
        List<String> command = new ArrayList<>();
        command.add(properties.getFfmpegPath());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(cfg.getLogLevel());
        command.add("-fflags");
        command.add("+genpts");
        command.add("-f");
        command.add(cfg.getPsInputFormat());
        command.add("-i");
        command.add("pipe:0");
        command.addAll(splitArgs(cfg.getCodecArgs()));
        command.addAll(splitArgs(cfg.getMp4Args()));
        command.add(file.toString());
        return command;
    }

    /**
     * 简单参数拆分：按空白字符切分，忽略空串。
     */
    public static List<String> splitArgs(String args) {
        List<String> result = new ArrayList<>();
        if (args == null || args.isBlank()) {
            return result;
        }
        String[] parts = args.trim().split("\\s+");
        result.addAll(Arrays.asList(parts));
        return result;
    }

    private FfmpegProcess start(IsupProperties properties, List<String> command, Path workDir, String sessionId)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        if (workDir != null) {
            Files.createDirectories(workDir);
            builder.directory(workDir.toFile());
        }
        String commandLine = FfmpegProcess.toCommandLine(command);
        log.info("启动 ffmpeg，sessionId={}, 命令={}", sessionId, commandLine);
        try {
            Process process = builder.start();
            FfmpegProcess wrapper = new FfmpegProcess(process, commandLine);
            wrapper.startStderrPump(sessionId);
            return wrapper;
        } catch (IOException e) {
            throw new IOException("启动 ffmpeg 失败，请检查 isup.ffmpeg-path 配置（当前值："
                    + properties.getFfmpegPath() + "），命令=" + commandLine, e);
        }
    }

    /**
     * 校验 ffmpeg 可执行文件是否可用（启动时做一次轻量检查）。
     */
    public boolean checkAvailable(IsupProperties properties) {
        try {
            ProcessBuilder builder = new ProcessBuilder(properties.getFfmpegPath(), "-version");
            Process process = builder.start();
            boolean ok = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String osName() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT);
    }
}
