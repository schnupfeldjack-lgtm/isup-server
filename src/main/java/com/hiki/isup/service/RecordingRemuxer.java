package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 保留设备视频编码，只转换 MP4 封装，再完整解码验证交付成片。 */
@Service
public class RecordingRemuxer {
    private static final Logger log = LoggerFactory.getLogger(RecordingRemuxer.class);
    private final IsupProperties properties;
    private final Runner runner;

    @Autowired
    public RecordingRemuxer(IsupProperties properties) {
        this(properties, ExactDurationTrimmer::runUntil);
    }

    RecordingRemuxer(IsupProperties properties, Runner runner) {
        if (properties == null || runner == null) throw new IllegalArgumentException("封装配置和执行器不能为空");
        this.properties = properties;
        this.runner = runner;
    }

    /** 所有探测和封装共享调用者的整行截止时间，不重新获得独立处理预算。 */
    public Path remux(MediaSession session, LocalDateTime start, LocalDateTime end, long deadlineNanos)
            throws IOException {
        if (session == null || start == null || end == null || !end.isAfter(start)
                || session.getStartTime() == null || session.getEndTime() == null
                || session.getStartTime().isAfter(start) || session.getEndTime().isBefore(end)) {
            throw new RecordingValidationException("下载请求区间未覆盖本单录像时间");
        }
        if (session.getRawFilePath() == null || session.getRawFilePath().isBlank()) {
            throw new RecordingValidationException("缺少原始录像文件");
        }
        Path source = Path.of(session.getRawFilePath()).toAbsolutePath().normalize();
        Path output = source.resolveSibling("recording-playable.mp4");
        if (source.equals(output)) throw new RecordingValidationException("封装输出不能覆盖原始录像");
        requireUsableFile(source, "原始录像");
        ExactDurationTrimmer.checkProcessingBudget(deadlineNanos, source.getParent());
        RecordingStorageGuard.checkOutput(output, RecordingStorageGuard.MAX_OUTPUT_BYTES);
        boolean writingOutput = false;
        try {
            ExactDurationTrimmer.checkProcessingBudget(deadlineNanos, source.getParent());
            writingOutput = true;
            String warnings = runner.run(remuxCommand(source, output), deadlineNanos, source.getParent());
            if (!warnings.isBlank()) {
                log.warn("录像封装提示：sessionId={}, channel={}, detail={}", session.getSessionId(), session.getChannel(),
                        warnings.substring(Math.max(0, warnings.length() - 4096)).strip());
            }
            requireUsableFile(output, "封装成片");
            ExactDurationTrimmer.checkProcessingBudget(deadlineNanos, source.getParent());
            String progress;
            try {
                progress = decode(output, deadlineNanos);
            } catch (IOException firstDecodeFailure) {
                if (!isCodecFailure(firstDecodeFailure)) throw firstDecodeFailure;
                // Exactly one conditional retry, solely after failure of the delivered MP4's strict decode.
                // Ordinary clean video never requires HK metadata or source-side repair.
                try (RecordingTailRepair.Prepared candidate = RecordingTailRepair.prepare(
                        properties.getFfmpegPath(), source, start, end, deadlineNanos)) {
                    Files.deleteIfExists(output);
                    runner.run(remuxCommand(candidate.path(), output), deadlineNanos, source.getParent());
                    requireUsableFile(output, "末 AU 补救成片");
                    progress = decode(output, deadlineNanos);
                    candidate.verifySource();
                    log.info("经业务覆盖证明排除不完整末 AU：sessionId={}, channel={}", session.getSessionId(), session.getChannel());
                } catch (IOException | RuntimeException repairFailure) {
                    repairFailure.addSuppressed(firstDecodeFailure);
                    throw repairFailure;
                }
            }
            double videoSeconds = videoDuration(progress);
            double businessSeconds = seconds(start, end);
            double requestedSeconds = seconds(session.getStartTime(), session.getEndTime());
            if (videoSeconds < businessSeconds - 2) {
                throw new RecordingDurationShortageException("原始录像片段时长明显不足：video="
                        + videoSeconds + "s, business=" + businessSeconds + "s, requested=" + requestedSeconds + "s");
            }
            if (videoSeconds > requestedSeconds + 30) {
                throw new RecordingValidationException("原始录像片段时长超出下载范围：video="
                        + videoSeconds + "s, business=" + businessSeconds + "s, requested=" + requestedSeconds + "s");
            }
            ExactDurationTrimmer.checkProcessingBudget(deadlineNanos, source.getParent());
            log.info("原始录像 MP4 封装完成：sessionId={}, channel={}, start={}, end={}, videoSeconds={}",
                    session.getSessionId(), session.getChannel(), start, end, videoSeconds);
            return output;
        } catch (IOException | RuntimeException e) {
            if (writingOutput) {
                try { Files.deleteIfExists(output); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
    }

    private List<String> remuxCommand(Path source, Path output) {
        return List.of(properties.getFfmpegPath(), "-y", "-hide_banner",
                "-loglevel", "warning", "-nostdin", "-copyts", "-start_at_zero", "-fflags", "+discardcorrupt",
                "-analyzeduration", "35000000", "-probesize", "10000000",
                "-i", source.toString(), "-map", "0:v:0", "-c:v", "copy", "-an",
                "-threads", "1", "-fs", Long.toString(RecordingStorageGuard.MAX_OUTPUT_BYTES),
                "-movflags", "+faststart", output.toString());
    }

    private String decode(Path output, long deadline) throws IOException {
        return runner.run(List.of(properties.getFfmpegPath(), "-hide_banner", "-loglevel", "error",
                "-nostdin", "-nostats", "-xerror", "-err_detect", "explode", "-threads", "1",
                "-i", output.toString(), "-map", "0:v:0", "-an", "-threads", "1",
                "-fps_mode", "passthrough", "-enc_time_base", "demux",
                "-progress", "pipe:1", "-f", "null", "-"), deadline, output.getParent());
    }

    static boolean isCodecFailure(IOException failure) {
        if (Thread.currentThread().isInterrupted() || failure instanceof java.io.InterruptedIOException
                || failure.getCause() != null || failure.getMessage() == null) return false;
        String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (List.of("input/output error", "no space left", "permission denied", "cannot allocate memory",
                "error opening", "out of memory", "broken pipe").stream().anyMatch(message::contains)) return false;
        // runUntil's full bounded capture is required: progress lines can precede the codec error.
        return message.matches("(?s)^命令失败 exit=-?[1-9][0-9]* .*" ) && message.contains("h264 @")
                && (message.contains("error while decoding") || message.contains("error decoding")
                || message.contains("decode_slice_header error") || message.contains("bytestream"));
    }

    private static double videoDuration(String progress) throws IOException {
        long micros = -1;
        boolean ended = false;
        for (String line : progress.split("\\R")) {
            if (line.startsWith("out_time_us=")) {
                try { micros = Long.parseLong(line.substring("out_time_us=".length()).trim()); }
                catch (NumberFormatException ignored) { micros = -1; }
            } else if (line.startsWith("progress=")) {
                ended = "progress=end".equals(line.trim());
            }
        }
        if (!ended || micros <= 0) throw new RecordingValidationException("封装成片没有完整的视频解码时长");
        return micros / 1_000_000d;
    }

    private static void requireUsableFile(Path file, String description) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(description + "文件不存在或类型无效");
        long bytes = Files.size(file);
        if (bytes <= 0 || bytes >= RecordingStorageGuard.MAX_OUTPUT_BYTES) {
            throw new IOException(description + "为空或达到文件容量上限");
        }
    }

    private static double seconds(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000d;
    }

    @FunctionalInterface
    interface Runner {
        String run(List<String> command, long deadlineNanos, Path sessionDirectory) throws IOException;
    }
}
