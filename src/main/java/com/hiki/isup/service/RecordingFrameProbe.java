package com.hiki.isup.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** 使用实际解码帧验证时轴；与裁切共用 FFmpeg，生产无需另装 ffprobe。 */
final class RecordingFrameProbe {
    private static final double EDGE = .080;
    private static final int MAX_FRAMES = 200_000;

    record AudioFrame(double start, double end) { }

    @FunctionalInterface interface ProofSupplier { RecordingH264ContinuityProbe.Proof get() throws IOException; }
    record WindowSelection(List<Double> source, List<Double> presentation, double origin, double duration,
                           double lastDuration, boolean variable) {
        WindowSelection { source = List.copyOf(source); presentation = List.copyOf(presentation); }
    }

    record Frames(List<Double> video, List<AudioFrame> audio, double declaredInterval, List<Double> durations) {
        Frames(List<Double> video, List<AudioFrame> audio, double declaredInterval) { this(video, audio, declaredInterval, List.of()); }
        Frames { video = List.copyOf(video); audio = List.copyOf(audio); durations = List.copyOf(durations); }

        WindowSelection selectWindow(double start, double end, ProofSupplier supplier) throws IOException {
            try {
                List<Double> selected = validateWindow(start, end);
                if (boundaryCrossesVariableInterval(start, false) || boundaryCrossesVariableInterval(end, true)) {
                    throw new RecordingValidationException("业务边界跨越可变展示周期，必须核对覆盖边界的源图片");
                }
                double first = selected.getFirst();
                return new WindowSelection(selected, selected.stream().map(t -> t - first).toList(), first,
                        selected.getLast() - first + declaredInterval, declaredInterval, false);
            } catch (RecordingValidationException ordinaryFailure) {
                if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start || video.size() < 2) throw ordinaryFailure;
                // 仍须有可信的基本周期；不把大面积缺帧重新解释为更低帧率。
                double interval = trustedInterval(video);
                for (int i = 1; i < video.size(); i++) {
                    if (!Double.isFinite(video.get(i)) || video.get(i) <= video.get(i - 1)) throw ordinaryFailure;
                }
                int first = -1, last = -1;
                for (int i = 0; i < video.size(); i++) {
                    if (video.get(i) <= start + .000001) first = i;
                    if (video.get(i) < end - .000001) last = i;
                }
                if (first < 0 || last < first || last + 1 >= video.size()) throw ordinaryFailure;
                RecordingH264ContinuityProbe.Proof proof = supplier.get();
                // 边界必须由相邻的真实图片夹住；不能仅放大首尾容差或猜末帧持续时长。
                if (start - video.get(first) > .000001) requireProvenPair(proof, video.get(first), video.get(first + 1), interval);
                requireProvenPair(proof, video.get(last), video.get(last + 1), interval);
                List<Double> selected = video.subList(first, last + 1);
                for (int i = 1; i < selected.size(); i++) {
                    double before = selected.get(i - 1), after = selected.get(i);
                    if (after - before > interval * 1.5 + .001) requireProvenPair(proof, before, after, interval);
                }
                if (!audio.isEmpty()) validateAudio(start, end);
                List<Double> presentation = selected.stream().map(t -> Math.max(0d, t - start)).toList();
                return new WindowSelection(selected, presentation, start, end - start,
                        end - Math.max(start, selected.getLast()), true);
            }
        }

        private boolean boundaryCrossesVariableInterval(double boundary, boolean ending) {
            for (int i = 1; i < video.size(); i++) {
                double before = video.get(i - 1), after = video.get(i);
                if (before < boundary - .000001 && (ending ? after >= boundary - .000001 : after > boundary + .000001)) {
                    return after - before > declaredInterval * 1.5 + .001;
                }
            }
            return false;
        }

        void validateOutput(WindowSelection selection, boolean expectsAudio) throws IOException {
            if (!selection.variable()) { validateOutput(selection.source(), expectsAudio); return; }
            // 源协议已证明这些可变周期；输出编码器的帧率标签不能重新否认源时间。
            // 仍逐帧核对，任何丢帧、补帧、变速和边界延长均拒绝。
            if (video.size() != selection.presentation().size() || video.isEmpty()) throw new RecordingValidationException("裁切前后帧数不一致");
            for (int i = 0; i < video.size(); i++) {
                if (!Double.isFinite(video.get(i)) || Math.abs(video.get(i) - selection.presentation().get(i)) > .002) {
                    throw new RecordingValidationException("VFR 成片发生补帧、变速或帧时间偏移");
                }
            }
            if (durations.size() != video.size() || !Double.isFinite(durations.getLast()) || durations.getLast() <= 0
                    || Math.abs(durations.getLast() - selection.lastDuration()) > .002
                    || Math.abs(video.getLast() + durations.getLast() - selection.duration()) > .002) {
                throw new RecordingValidationException("VFR 成片末帧展示时长未精确裁到业务终点");
            }
            if (expectsAudio && audio.isEmpty()) throw new RecordingValidationException("成片丢失音轨");
            if (expectsAudio) validateAudio(0, selection.duration());
        }

        private static void requireProvenPair(RecordingH264ContinuityProbe.Proof proof, double before,
                                              double after, double interval) throws IOException {
            // 已证实设备样本只多一个基本周期；协议证明不是长时间停帧的通行证。
            if (after <= before || after - before > 2 * interval + .002 || !proof.proves(before, after)) {
                throw new RecordingValidationException("本单可变帧间隔缺少相邻 H264 图片证明: gap=" + (after - before));
            }
        }

        List<Double> validateWindow(double start, double end) throws IOException {
            if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start) throw new RecordingValidationException("裁切时间无效");
            List<Double> selected = video.stream().filter(t -> t >= start - .000001 && t < end - .000001).toList();
            double interval = trustedInterval(selected);
            // [start,end) 内第一帧可以在起点之后，但不能整整迟一帧；末帧包含其自身播放周期。
            // 固定80ms会误拒绝5/8/10fps，直接放大容差又会掩盖真正的首帧缺失。
            if (selected.isEmpty() || selected.getFirst() - start >= interval - .000001
                    || end - selected.getLast() > interval + .002) throw new RecordingValidationException("解码画面未完整覆盖本单首尾");
            requireContinuous(selected, interval);
            if (!audio.isEmpty()) validateAudio(start, end);
            return selected;
        }

        void verifyAnchors(List<PsRecordingTimeline.Anchor> anchors) throws IOException {
            for (var anchor : anchors) {
                double pts = anchor.pts90k() / 90000d;
                // 命中实际解码帧，而不是仅相信某一个 PES 包的时间。
                int at = java.util.Collections.binarySearch(video, pts);
                if (at < 0) {
                    at = -at - 1;
                    boolean near = at < video.size() && Math.abs(video.get(at) - pts) <= 1d / 90000;
                    near |= at > 0 && Math.abs(video.get(at - 1) - pts) <= 1d / 90000;
                    if (!near) throw new RecordingValidationException("HK/PES 时间未命中解码帧，拒绝猜测偏移: " + pts);
                }
            }
        }

        void validateOutput(List<Double> sourceFrames, boolean expectsAudio) throws IOException {
            if (video.size() != sourceFrames.size() || video.isEmpty()) throw new RecordingValidationException("裁切前后帧数不一致");
            if (Math.abs(video.getFirst()) > EDGE) throw new RecordingValidationException("成片起始时间未归零");
            requireContinuous(video, trustedInterval(video));
            for (int i = 0; i < video.size(); i++) {
                double expected = sourceFrames.get(i) - sourceFrames.getFirst();
                double actual = video.get(i) - video.getFirst();
                if (Math.abs(expected - actual) > .002) throw new RecordingValidationException("成片发生补帧、变速或帧时间偏移");
            }
            if (expectsAudio && audio.isEmpty()) throw new RecordingValidationException("成片丢失音轨");
            if (expectsAudio) validateAudio(video.getFirst(), video.getLast());
        }

        private static void requireContinuous(List<Double> pts, double interval) throws IOException {
            double maximum = interval * 1.5 + .001;
            for (int i = 1; i < pts.size(); i++) {
                double gap = pts.get(i) - pts.get(i - 1);
                if (gap <= 0 || gap > maximum) throw new RecordingValidationException("本单画面时间戳中断或重复: gap=" + gap);
            }
        }

        private double trustedInterval(List<Double> pts) throws IOException {
            double measured = nominalInterval(pts);
            if (!Double.isFinite(declaredInterval) || declaredInterval <= 0) {
                throw new RecordingValidationException("解码视频缺少可信帧率");
            }
            if (pts.size() >= 2 && Math.abs(measured - declaredInterval) > .002) {
                throw new RecordingValidationException("解码帧周期与声明帧率不一致，不能将丢帧误判为低帧率");
            }
            return declaredInterval;
        }

        private static double nominalInterval(List<Double> pts) {
            if (pts.size() < 2) return EDGE;
            double[] gaps = new double[pts.size() - 1];
            for (int i = 1; i < pts.size(); i++) gaps[i - 1] = pts.get(i) - pts.get(i - 1);
            java.util.Arrays.sort(gaps);
            return gaps[gaps.length / 2];
        }

        private void validateAudio(double start, double end) throws IOException {
            List<AudioFrame> selected = audio.stream().filter(a -> a.end() > start && a.start() < end).toList();
            if (selected.isEmpty() || selected.getFirst().start() > start + .15
                    || selected.getLast().end() < end - .15) throw new RecordingValidationException("音轨未覆盖本单时间区间");
            double tail = selected.getFirst().end();
            for (int i = 1; i < selected.size(); i++) {
                var frame = selected.get(i);
                if (Math.abs(frame.start() - tail) > .005) throw new RecordingValidationException("音轨时间戳不连续");
                tail = frame.end();
            }
        }
    }

    static final class Parser {
        private static final Pattern BASE = Pattern.compile("config in time_base: (\\d+)/(\\d+)");
        private static final Pattern FRAME_RATE = Pattern.compile("frame_rate: (\\d+)/(\\d+)");
        private static final Pattern PTS = Pattern.compile("\\bpts:\\s*(-?\\d+)");
        private static final Pattern DURATION = Pattern.compile("\\bduration:\\s*(\\d+)");
        private static final Pattern RATE = Pattern.compile("\\brate:(\\d+)");
        private static final Pattern SAMPLES = Pattern.compile("\\bnb_samples:(\\d+)");
        private final List<Double> video = new ArrayList<>();
        private final List<Double> durations = new ArrayList<>();
        private final List<AudioFrame> audio = new ArrayList<>();
        private double timeBase;
        private double frameInterval;
        private boolean audioDeclared;
        private int audioStreams;

        void accept(String line) throws IOException {
            if (line.matches(".*Stream #0:[0-9]+.*Audio:.*")) { audioDeclared = true; audioStreams++; }
            if (line.contains("Parsed_showinfo_")) {
                var base = BASE.matcher(line);
                if (base.find()) {
                    double next = Double.parseDouble(base.group(1)) / Double.parseDouble(base.group(2));
                    if (!Double.isFinite(next) || next <= 0 || timeBase != 0 && timeBase != next) throw new RecordingValidationException("解码时间基不稳定");
                    timeBase = next;
                    var rate = FRAME_RATE.matcher(line);
                    if (!rate.find()) throw new RecordingValidationException("解码视频未提供可信帧率");
                    double interval = Double.parseDouble(rate.group(2)) / Double.parseDouble(rate.group(1));
                    if (!Double.isFinite(interval) || interval <= 0
                            || frameInterval != 0 && Math.abs(frameInterval - interval) > 1e-9) {
                        throw new RecordingValidationException("解码视频帧率未知或发生变化");
                    }
                    frameInterval = interval;
                }
                var pts = PTS.matcher(line);
                if (pts.find()) {
                    if (timeBase == 0 || video.size() >= MAX_FRAMES) throw new RecordingValidationException("解码时间基缺失或帧数超限");
                    double value = Long.parseLong(pts.group(1)) * timeBase;
                    if (!video.isEmpty() && value <= video.getLast()) throw new RecordingValidationException("解码帧 PTS 回退或重复");
                    video.add(value);
                    var duration = DURATION.matcher(line);
                    durations.add(duration.find() ? Long.parseLong(duration.group(1)) * timeBase : 0d);
                }
            } else if (line.contains("Parsed_ashowinfo_")) {
                var pts = PTS.matcher(line); var rate = RATE.matcher(line); var samples = SAMPLES.matcher(line);
                if (pts.find() && rate.find() && samples.find()) {
                    if (audio.size() >= MAX_FRAMES) throw new RecordingValidationException("音频帧数超限");
                    double hz = Double.parseDouble(rate.group(1));
                    double start = Long.parseLong(pts.group(1)) / hz;
                    double end = start + Integer.parseInt(samples.group(1)) / hz;
                    if (!Double.isFinite(end) || end <= start) throw new RecordingValidationException("音频帧时间无效");
                    audio.add(new AudioFrame(start, end));
                }
            }
        }

        Frames finish() throws IOException {
            if (video.isEmpty()) throw new RecordingValidationException("未读到可验证的解码画面");
            if (frameInterval == 0) throw new RecordingValidationException("解码视频缺少可信帧率");
            return new Frames(video, audio, frameInterval, durations);
        }
    }

    static Frames read(String ffmpeg, Path source, long timeoutSeconds) throws IOException {
        return readUntil(ffmpeg, source, System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds), 0, null);
    }

    static Frames readUntil(String ffmpeg, Path source, long deadlineNanos, int expectedAudioStreams,
                            Path sessionDirectory) throws IOException {
        Parser video = readTrack(ffmpeg, source, deadlineNanos, false, sessionDirectory);
        Frames decoded = video.finish();
        if (expectedAudioStreams > 1 || video.audioStreams > 1) {
            throw new RecordingValidationException("录像包含多个音轨，当前不能证明全部音频均已保留");
        }
        if (expectedAudioStreams > 0 && !video.audioDeclared) {
            throw new RecordingValidationException("原始 PS 声明音频或含私有音频 PES，但 FFmpeg 未识别可解码音轨，拒绝无声成片");
        }
        if (!video.audioDeclared) return decoded;
        Parser audio = readTrack(ffmpeg, source, deadlineNanos, true, sessionDirectory);
        if (audio.audio.isEmpty()) throw new RecordingValidationException("输入声明音轨但未解码到音频帧");
        return new Frames(decoded.video(), audio.audio, decoded.declaredInterval(), decoded.durations());
    }

    private static Parser readTrack(String ffmpeg, Path source, long deadlineNanos, boolean audioOnly,
                                    Path sessionDirectory) throws IOException {
        // 两种滤镜的 av_log 由不同线程分段输出，合并采集可能把音频 PTS 误当视频。
        // 顺序独立采集；音频这一遍不解码视频，不增加第二次 4K 视频解码。
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "info", "-nostdin", "-xerror",
                "-copyts", "-threads", "1", "-filter_threads", "1", "-err_detect", "explode", "-i", source.toString(),
                "-map", audioOnly ? "0:a:0" : "0:v:0"));
        if (audioOnly) command.addAll(List.of("-vn", "-af", "asettb=1/sr,ashowinfo"));
        else command.addAll(List.of("-an", "-vf", "showinfo=checksum=0", "-fps_mode:v", "passthrough"));
        command.addAll(List.of("-f", "null", "-"));
        ExactDurationTrimmer.checkProcessingBudget(deadlineNanos, sessionDirectory);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Parser parser = new Parser();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder tail = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (tail.length() > 8192) tail.delete(0, tail.length() - 4096);
                    tail.append(line.length() > 4096 ? line.substring(0, 4096) : line).append('\n');
                    parser.accept(line);
                }
            } catch (Exception e) { failure.set(e); process.destroyForcibly(); }
        }, "recording-frame-probe");
        reader.setDaemon(true); reader.start();
        try {
            ExactDurationTrimmer.waitFor(process, deadlineNanos, sessionDirectory);
            reader.join(Math.min(5000, RecordingDeadline.remainingMillis(deadlineNanos)));
            if (reader.isAlive()) throw new IOException("录像解码校验输出未结束");
            if (failure.get() instanceof RecordingValidationException invalid) throw invalid;
            if (failure.get() != null) throw new IOException("录像解码校验失败", failure.get());
            if (process.exitValue() != 0) throw new IOException("录像解码失败: " + tail);
            RecordingDeadline.check(deadlineNanos);
            return parser;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("录像解码校验被中断", e);
        } finally {
            RecordingProcessCleanup.finish(process, reader);
        }
    }
}
