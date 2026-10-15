package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/**
 * 从原始 PS 的 HK/PTS 连续段定位本单区间，解码核验后裁切，再逐帧验证成片。
 */
@Service
public class ExactDurationTrimmer {

    private static final Logger log = LoggerFactory.getLogger(ExactDurationTrimmer.class);
    private final DurationCutter cutter;
    private final IsupProperties properties;
    /** 4K 裁切会重编码，独立于设备下载并发限制 CPU 峰值。 */
    private final Semaphore cutSlot = new Semaphore(1, true);

    @Autowired
    public ExactDurationTrimmer(IsupProperties properties) {
        this(properties, (input, output, offset, duration) -> cut(properties, input, output, offset, duration));
    }

    ExactDurationTrimmer(IsupProperties properties, DurationCutter cutter) {
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为空");
        }
        this.cutter = cutter;
        this.properties = properties;
    }

    public Path trim(MediaSession session, LocalDateTime start, LocalDateTime end) throws IOException {
        return trim(session, start, end, processingDeadline(start, end));
    }

    /** 行级调用者可共享下载、裁切及上传预算；每个子进程不能重新获得完整超时。 */
    public Path trim(MediaSession session, LocalDateTime start, LocalDateTime end, long deadlineNanos) throws IOException {
        deadlineNanos = Math.min(deadlineNanos, processingDeadline(start, end));
        if (session.getRawFilePath() == null || session.getFilePath() == null) {
            throw new RecordingValidationException("缺少原始 PS，不能用首帧时间猜测 MP4 零点，拒绝上传");
        }
        Path source = Path.of(session.getRawFilePath()).toAbsolutePath();
        Path output = exactPath(Path.of(session.getFilePath()).toAbsolutePath());
        boolean acquired = false;
        boolean writingOutput = false;
        try {
            do {
                checkProcessingBudget(deadlineNanos, source.getParent());
                acquired = cutSlot.tryAcquire(Math.min(1000, RecordingDeadline.remainingMillis(deadlineNanos)), TimeUnit.MILLISECONDS);
            } while (!acquired);
            PsRecordingTimeline timeline = PsRecordingTimeline.parse(source, deadlineNanos);
            PsRecordingTimeline.Window window = timeline.plan(start, end);
            try (DecodeSource input = prepareDecodeSource(source, timeline, deadlineNanos)) {
                RecordingFrameProbe.Frames frames = RecordingFrameProbe.readUntil(properties.getFfmpegPath(), input.path(),
                        deadlineNanos, timeline.audioStreamCount(), source.getParent());
                frames.verifyAnchors(window.anchors());
                long proofDeadline = deadlineNanos;
                RecordingFrameProbe.WindowSelection selection = frames.selectWindow(window.startPtsSeconds(), window.endPtsSeconds(),
                        () -> RecordingH264ContinuityProbe.readUntil(properties.getFfmpegPath(), input.path(),
                                frames.video(), proofDeadline, source.getParent()));
                List<Double> selected = selection.source();
                log.info("本单原始录像对齐：sessionId={}, channel={}, start={}, end={}, sourcePts={}..{}, frames={}, anchors={}",
                        session.getSessionId(), session.getChannel(), start, end,
                        window.startPtsSeconds(), window.endPtsSeconds(), selected.size(), window.anchors().size());
                writingOutput = true;
                runUntil(selection.variable()
                                ? variableTimelineTrimCommand(properties.getFfmpegPath(), properties.getFfmpeg().getThreads(), input.path(), output,
                                selection, frames.declaredInterval())
                                : timelineTrimCommand(properties.getFfmpegPath(), properties.getFfmpeg().getThreads(), input.path(), output,
                                window.startPtsSeconds(), window.endPtsSeconds(), selected.getFirst(), frames.declaredInterval()),
                        deadlineNanos, source.getParent());
                if (!Files.isRegularFile(output) || Files.size(output) == 0 || Files.size(output) >= RecordingStorageGuard.MAX_OUTPUT_BYTES) {
                    throw new IOException("裁切输出为空或达到文件容量上限，拒绝上传截短成片");
                }
                RecordingFrameProbe.Frames result = RecordingFrameProbe.readUntil(properties.getFfmpegPath(), output,
                        deadlineNanos, frames.audio().isEmpty() ? 0 : 1, source.getParent());
                result.validateOutput(selection, !frames.audio().isEmpty());
                checkProcessingBudget(deadlineNanos, source.getParent());
                return output;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待原始录像校验被中断", e);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RecordingValidationException("本单录像时间区间无效：" + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            if (writingOutput) {
                try { Files.deleteIfExists(output); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        } finally {
            if (acquired) cutSlot.release();
        }
    }

    /** 排除不完整尾部及已证完整重发的视频 PES；保留其它包，原始下载始终不修改。 */
    static DecodeSource prepareDecodeSource(Path source, PsRecordingTimeline timeline,
                                                    long deadlineNanos) throws IOException {
        if (!timeline.hasIncompleteTail() && timeline.duplicateVideoPackets().isEmpty()) return new DecodeSource(source, false);
        long size = Files.size(source);
        long length = timeline.completePrefixBytes();
        if (size != timeline.sourceBytes() || length <= 0 || length > size
                || timeline.hasIncompleteTail() && length >= size
                || size >= RecordingSourceCapture.MAX_BYTES) {
            throw new RecordingValidationException("原始 PS 在解析后变化或完整前缀大小不合法，拒绝裁切");
        }
        long previousEnd = 0;
        for (var packet : timeline.duplicateVideoPackets()) {
            if (packet.start() < previousEnd || packet.end() <= packet.start() || packet.end() > length) {
                throw new RecordingValidationException("重复视频 PES 排除范围不合法，拒绝裁切");
            }
            previousEnd = packet.end();
        }
        checkProcessingBudget(deadlineNanos, source.getParent());
        Path prefix = Files.createTempFile(source.getParent(), "recording-prefix-", ".ps");
        try {
            var hash = PsDuplicateAccessUnits.sha256();
            try (InputStream input = new DigestInputStream(Files.newInputStream(source), hash);
                 var output = Files.newOutputStream(prefix)) {
                byte[] buffer = new byte[65_536];
                long position = 0;
                int packetIndex = 0;
                long nextSpaceCheck = System.nanoTime();
                while (position < size) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("复制录像完整前缀被中断");
                    RecordingDeadline.check(deadlineNanos);
                    if (System.nanoTime() - nextSpaceCheck >= 0) {
                        checkProcessingBudget(deadlineNanos, source.getParent());
                        nextSpaceCheck = System.nanoTime() + 1_000_000_000L;
                    }
                    while (packetIndex < timeline.duplicateVideoPackets().size()
                            && position >= timeline.duplicateVideoPackets().get(packetIndex).end()) packetIndex++;
                    var packet = packetIndex < timeline.duplicateVideoPackets().size()
                            ? timeline.duplicateVideoPackets().get(packetIndex) : null;
                    boolean skip = packet != null && position >= packet.start();
                    long boundary = position >= length ? size : length;
                    if (packet != null) boundary = Math.min(boundary, skip ? packet.end() : packet.start());
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, boundary - position));
                    if (count < 0) throw new IOException("复制录像完整前缀时原流缩短");
                    if (position < length && !skip) output.write(buffer, 0, count);
                    position += count;
                }
                if (input.read() != -1) throw new IOException("复制录像副本时原流增长");
            }
            if (Files.size(source) != size || !timeline.matchesSourceHash(hash.digest())) {
                throw new IOException("复制录像副本时原始 PS 内容变化");
            }
            checkProcessingBudget(deadlineNanos, source.getParent());
            log.info("原始 PS 使用有界副本继续严格校验：file={}, sourceBytes={}, completePrefixBytes={}, excludedTailBytes={}, duplicateVideoPes={}",
                    source, size, length, size - length, timeline.duplicateVideoPackets().size());
            return new DecodeSource(prefix, true);
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(prefix); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    record DecodeSource(Path path, boolean temporary) implements AutoCloseable {
        @Override public void close() throws IOException {
            if (temporary) Files.deleteIfExists(path);
        }
    }

    private static long processingDeadline(LocalDateTime start, LocalDateTime end) throws IOException {
        if (start == null || end == null || !end.isAfter(start)) throw new RecordingValidationException("本单录像时间区间无效");
        long seconds = java.time.Duration.between(start, end).toSeconds();
        return RecordingDeadline.afterSeconds((int) Math.min(1800, Math.max(60, Math.min(225, seconds) * 8)));
    }

    static List<String> timelineTrimCommand(String ffmpeg, int threads, Path source, Path output,
                                            double startPts, double endPts, double firstFramePts, double declaredInterval) {
        double durationTicks = declaredInterval * 90_000d;
        if (!Double.isFinite(durationTicks) || durationTicks < 1 || durationTicks >= Long.MAX_VALUE) {
            throw new IllegalArgumentException("缺少可信视频帧周期，不能补全封装时长");
        }
        String start = String.format(Locale.ROOT, "%.9f", startPts);
        String end = String.format(Locale.ROOT, "%.9f", endPts);
        String first = String.format(Locale.ROOT, "%.9f", firstFramePts);
        // FFmpeg 7 的 setpts 会清掉 duration；只补零值，避免 MP4 隐藏末帧，PTS/DTS 必须原样保留。
        String packetDuration = "setts=pts=PTS:dts=DTS:duration=if(eq(DURATION\\,0)\\,"
                + Math.round(durationTicks) + "\\,DURATION)";
        return List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-copyts",
                "-threads", String.valueOf(limitedThreads(threads)), "-filter_threads", "1",
                "-err_detect", "explode", "-i", source.toString(), "-map", "0:v:0", "-map", "0:a:0?",
                "-vf", "trim=start=" + start + ":end=" + end + ",setpts=PTS-" + first + "/TB",
                "-af", "atrim=start=" + start + ":end=" + end + ",asetpts=PTS-" + first + "/TB",
                "-fps_mode:v", "passthrough", "-enc_time_base:v", "1:90000", "-c:v", "libx264",
                "-preset", "veryfast", "-crf", "23", "-threads", String.valueOf(limitedThreads(threads)),
                "-c:a", "aac", "-b:a", "64k", "-video_track_timescale", "90000",
                "-bsf:v", packetDuration,
                "-fs", Long.toString(RecordingStorageGuard.MAX_OUTPUT_BYTES), "-movflags", "+faststart", output.toString());
    }

    static List<String> variableTimelineTrimCommand(String ffmpeg, int threads, Path source, Path output,
                                                     RecordingFrameProbe.WindowSelection selection, double interval) {
        List<String> command = new ArrayList<>(timelineTrimCommand(ffmpeg, threads, source, output,
                selection.source().getFirst(), selection.origin() + selection.duration(), selection.origin(), interval));
        String origin = String.format(Locale.ROOT, "%.9f", selection.origin());
        String start = String.format(Locale.ROOT, "%.9f", selection.source().getFirst());
        String end = String.format(Locale.ROOT, "%.9f", selection.origin() + selection.duration());
        command.set(command.indexOf("-vf") + 1, "trim=start=" + start + ":end=" + end + ",setpts=max(PTS-" + origin + "/TB\\,0)");
        command.set(command.indexOf("-af") + 1, "atrim=start=" + origin + ":end=" + end + ",asetpts=PTS-" + origin + "/TB");
        long lastPts = Math.round(selection.presentation().getLast() * 90_000d);
        long lastDuration = Math.max(1, Math.round(selection.lastDuration() * 90_000d));
        String duration = "setts=pts=PTS:dts=DTS:duration=if(eq(PTS\\," + lastPts + ")\\," + lastDuration
                + "\\,if(eq(DURATION\\,0)\\," + Math.round(interval * 90_000d) + "\\,DURATION))";
        command.set(command.indexOf("-bsf:v") + 1, duration);
        // 此分支的源图片无重排；避免重编码再引入 DTS 重排影响可变展示区间封装。
        command.add(command.size() - 1, "-bf");
        command.add(command.size() - 1, "0");
        return List.copyOf(command);
    }

    /**
     * @param offsetSeconds   从文件开头跳过的秒数，对应首帧钟点到目标起点
     * @param durationSeconds 目标窗口的秒数，不含帧补偿
     */
    public Path trim(Path source, double offsetSeconds, double durationSeconds) throws IOException {
        if (!Double.isFinite(offsetSeconds) || !Double.isFinite(durationSeconds)
                || offsetSeconds < 0 || durationSeconds <= 0) {
            throw new IOException("裁切参数无效: offset=" + offsetSeconds + ", duration=" + durationSeconds);
        }
        Path output = exactPath(source);
        log.info("按钟点裁切出库录像：file={}, offset={}s, duration={}s", source, offsetSeconds, durationSeconds);
        boolean acquired = false;
        try {
            cutSlot.acquire();
            acquired = true;
            cutter.cut(source, output, offsetSeconds, durationSeconds);
            if (!Files.isRegularFile(output) || Files.size(output) <= 0) {
                throw new IOException("裁切后的 MP4 为空: " + output);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待录像裁切被中断", e);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(output);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        } finally {
            if (acquired) {
                cutSlot.release();
            }
        }
    }

    static Path exactPath(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return source.resolveSibling(stem + ".exact.mp4");
    }

    static List<String> trimCommand(String ffmpeg, int threads, Path input, Path output,
                                    double offsetSeconds, double targetSeconds) {
        List<String> command = new ArrayList<>();
        command.add(ffmpeg);
        command.add("-y");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        command.add("-threads");
        command.add(String.valueOf(limitedThreads(threads)));
        command.add("-i");
        command.add(input.toString());
        command.add("-ss");
        command.add(formatSeconds(offsetSeconds));
        command.add("-t");
        command.add(formatSeconds(targetSeconds));
        command.add("-map");
        command.add("0:v:0");
        command.add("-map");
        command.add("0:a?");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-threads");
        command.add(String.valueOf(limitedThreads(threads)));
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("64k");
        command.add("-movflags");
        command.add("+faststart");
        command.add(output.toString());
        return command;
    }

    private static void cut(IsupProperties properties, Path input, Path output,
                            double offsetSeconds, double targetSeconds) throws IOException {
        List<String> command = trimCommand(properties.getFfmpegPath(), properties.getFfmpeg().getThreads(),
                input, output, offsetSeconds, targetSeconds);
        long timeoutSec = Math.min(1800, Math.max(60, (long) Math.ceil(targetSeconds) * 8));
        run(command, timeoutSec);
        String progress = run(List.of(properties.getFfmpegPath(), "-hide_banner", "-loglevel", "error",
                "-nostdin", "-nostats", "-i", output.toString(), "-map", "0:v:0", "-c:v", "copy",
                "-progress", "pipe:1", "-f", "null", "-"), 60);
        validateVideoProgress(progress, targetSeconds);
    }

    static void validateVideoProgress(String progress, double targetSeconds) throws IOException {
        long micros = -1;
        for (String line : progress.split("\\R")) {
            if (line.startsWith("out_time_us=")) {
                try {
                    micros = Long.parseLong(line.substring("out_time_us=".length()).trim());
                } catch (NumberFormatException ignored) {
                    // N/A 不作为有效视频时长。
                }
            }
        }
        // 编码器 B 帧/帧边界可能使最后包 DTS 比播放结束点早几帧。
        if (!progress.contains("progress=end") || micros <= 0
                || Math.abs(micros / 1_000_000d - targetSeconds) > 0.20) {
            throw new IOException("裁切视频时长不符合本单区间：actual=" + micros / 1_000_000d
                    + "s, required=" + targetSeconds + "s");
        }
    }

    static String run(List<String> command, long timeoutSeconds) throws IOException {
        return runUntil(command, System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds), null);
    }

    static String runUntil(List<String> command, long deadlineNanos, Path sessionDirectory) throws IOException {
        checkProcessingBudget(deadlineNanos, sessionDirectory);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                int length;
                while ((length = input.read(chunk)) != -1) {
                    synchronized (capture) {
                        if (capture.size() + length > 65536) {
                            capture.reset();
                        }
                        capture.write(chunk, 0, length);
                    }
                }
            } catch (IOException ignored) {
                // 超时/中断销毁进程会关闭管道。
            }
        }, "ffmpeg-trim-output");
        reader.setDaemon(true);
        reader.start();
        try {
            waitFor(process, deadlineNanos, sessionDirectory);
            reader.join(Math.min(1000, RecordingDeadline.remainingMillis(deadlineNanos)));
            if (reader.isAlive()) throw new IOException("录像处理命令输出未结束");
            String output;
            synchronized (capture) {
                output = capture.toString(StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0) {
                throw new IOException("命令失败 exit=" + process.exitValue() + " " + output);
            }
            RecordingDeadline.check(deadlineNanos);
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断: " + command, e);
        } finally {
            RecordingProcessCleanup.finish(process, reader);
        }
    }

    static void waitFor(Process process, long deadlineNanos, Path sessionDirectory) throws IOException, InterruptedException {
        while (true) {
            checkProcessingBudget(deadlineNanos, sessionDirectory);
            if (process.waitFor(Math.min(1000, RecordingDeadline.remainingMillis(deadlineNanos)), TimeUnit.MILLISECONDS)) return;
        }
    }

    static void checkProcessingBudget(long deadlineNanos, Path sessionDirectory) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("录像处理被中断");
        RecordingDeadline.check(deadlineNanos);
        if (sessionDirectory != null) RecordingStorageGuard.check(sessionDirectory);
        RecordingDeadline.check(deadlineNanos);
    }

    private static int limitedThreads(int configured) {
        return configured > 0 ? Math.min(configured, 2) : 2;
    }

    private static String formatSeconds(double seconds) {
        return String.format(Locale.US, "%.3f", seconds);
    }

    @FunctionalInterface
    interface DurationCutter {
        void cut(Path input, Path output, double offsetSeconds, double targetSeconds) throws IOException;
    }
}
