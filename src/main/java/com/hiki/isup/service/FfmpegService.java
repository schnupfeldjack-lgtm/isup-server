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
        // 实时流：宁可丢帧也不让背压顶回 SDK 回调线程
        return start(properties, command, dir, sessionId, true);
    }

    /**
     * 启动 MP4 输出进程，输出到 file。
     */
    public FfmpegProcess startMp4(IsupProperties properties, Path file, String sessionId) throws IOException {
        RecordingStorageGuard.checkOutput(file, RecordingStorageGuard.MAX_OUTPUT_BYTES);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> command = buildMp4Command(properties, file);
        // 下载：文件必须完整，队列满时等待而不是丢包
        FfmpegProcess process = start(properties, command, parent, sessionId, false);
        process.monitorOutput(() -> RecordingStorageGuard.checkOutput(file, RecordingStorageGuard.MAX_OUTPUT_BYTES));
        return process;
    }

    /** 打包录像直接保存原始 PS，最终成片在时间验证之后只生成一次。 */
    public FfmpegProcess startPackingCapture(IsupProperties properties, Path rawFile,
                                            String sessionId) throws IOException {
        RecordingSourceCapture capture = new RecordingSourceCapture(rawFile);
        try {
            return FfmpegProcess.capture(capture, sessionId);
        } catch (RuntimeException e) {
            try { capture.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
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
        appendInputArgs(command, cfg);
        command.addAll(resolveCodecArgs(cfg, cfg.getHlsCodecArgs()));
        if (cfg.getThreads() > 0) {
            command.add("-threads");
            command.add(String.valueOf(cfg.getThreads()));
        }
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
        appendInputArgs(command, cfg);
        command.addAll(resolveCodecArgs(cfg, cfg.getMp4CodecArgs()));
        command.addAll(splitArgs(cfg.getMp4Args()));
        command.add("-fs");
        command.add(String.valueOf(RecordingStorageGuard.MAX_OUTPUT_BYTES));
        command.add(file.toString());
        return command;
    }

    /**
     * 取某类输出实际使用的编码参数：专用配置非空就用专用的，否则回退通用 codec-args。
     *
     * <p>拆开是因为 HLS 和 MP4 对编码的容忍度不同——浏览器解不了 HEVC，
     * 但 MP4 装得下 HEVC，没必要为下载付转码的 CPU。</p>
     */
    private static List<String> resolveCodecArgs(IsupProperties.Ffmpeg cfg, String dedicated) {
        String args = (dedicated != null && !dedicated.isBlank()) ? dedicated : cfg.getCodecArgs();
        return splitArgs(args);
    }

    /**
     * 输入侧参数：码流通过 stdin 管道进入，是非 seekable 输入，
     * 必须给足探测数据，否则 ffmpeg 可能把 PS 里的私有流误判成音频流导致失败。
     *
     * <p>ps-input-format 填 auto 或留空时不指定 -f，交给 ffmpeg 自动探测。</p>
     */
    private static void appendInputArgs(List<String> command, IsupProperties.Ffmpeg cfg) {
        command.add("-analyzeduration");
        command.add(String.valueOf(cfg.getAnalyzeDuration()));
        command.add("-probesize");
        command.add(String.valueOf(cfg.getProbeSize()));
        command.add("-fflags");
        command.add(cfg.getInputFlags());
        if (cfg.getAvoidNegativeTs() != null && !cfg.getAvoidNegativeTs().isBlank()) {
            command.add("-avoid_negative_ts");
            command.add(cfg.getAvoidNegativeTs().trim());
        }
        String inputFormat = cfg.getPsInputFormat();
        if (inputFormat != null && !inputFormat.isBlank() && !"auto".equalsIgnoreCase(inputFormat.trim())) {
            command.add("-f");
            command.add(inputFormat.trim());
        }
        command.add("-i");
        command.add("pipe:0");
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

    private FfmpegProcess start(IsupProperties properties, List<String> command, Path workDir, String sessionId,
                                boolean dropWhenFull)
            throws IOException {
        return start(properties, command, workDir, sessionId, dropWhenFull, null);
    }

    private FfmpegProcess start(IsupProperties properties, List<String> command, Path workDir, String sessionId,
                                boolean dropWhenFull, java.io.OutputStream sourceCapture)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        if (workDir != null) {
            // 只负责创建输出目录，不把它设为子进程工作目录：
            // 命令里的输出路径是相对当前 JVM 工作目录解析的，
            // 若同时设置 working directory，相对路径会被二次拼接导致文件打不开。
            Files.createDirectories(workDir);
        }
        String commandLine = FfmpegProcess.toCommandLine(command);
        log.info("启动 ffmpeg，sessionId={}, 命令={}", sessionId, commandLine);
        try {
            Process process = builder.start();
            FfmpegProcess wrapper = new FfmpegProcess(process, commandLine, dropWhenFull, sourceCapture);
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
