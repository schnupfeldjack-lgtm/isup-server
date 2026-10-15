package com.hiki.isup.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 只在固定周期检查失败时核对 PS 中的 H.264 图片序列。证明设备已编码图片连续，
 * 不证明编码前的采集没有漏帧。未知模式和不完整元数据均不能产生授权。
 */
final class RecordingH264ContinuityProbe {
    private static final int MAX_LINE = 16_384;
    private static final long MAX_TRACE_CHARS = 128L * 1024 * 1024;
    private static final int MAX_PICTURES = 200_000;
    record Edge(long before, long after) { }
    record Proof(Set<Edge> edges) {
        Proof { edges = Set.copyOf(edges); }
        boolean proves(double previous, double next) {
            return edges.contains(new Edge(ticks(previous), ticks(next)));
        }
    }
    private record Picture(long pts, long poc, long modulus, long epoch, long pps, double interval, boolean reset) { }
    private record Sequence(long epoch, long pps, long modulus, double interval, long generation) { }

    static Proof readUntil(String ffmpeg, Path source, List<Double> decoded, long deadline, Path directory)
            throws IOException {
        ExactDurationTrimmer.checkProcessingBudget(deadline, directory);
        // 调用者传入原始 PS 的完整、去重副本；强制 mpeg 解复用，使包 PTS 明确使用 90 kHz。
        List<String> command = List.of(ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "info", "-xerror",
                "-copyts", "-f", "mpeg", "-i", source.toString(), "-map", "0:v:0", "-an",
                "-c:v", "copy", "-bsf:v", "trace_headers", "-f", "null", "-");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Parser parser = new Parser();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder line = new StringBuilder();
                int value;
                while ((value = input.read()) != -1) {
                    if (value == '\n') { parser.accept(line.toString()); line.setLength(0); }
                    else if (value != '\r') {
                        if (line.length() >= MAX_LINE) throw invalid("协议日志单行超限");
                        line.append((char) value);
                    }
                }
                if (!line.isEmpty()) parser.accept(line.toString());
            } catch (Exception e) { failure.set(e); process.destroyForcibly(); }
        }, "recording-h264-proof");
        reader.setDaemon(true);
        reader.start();
        try {
            ExactDurationTrimmer.waitFor(process, deadline, directory);
            reader.join(Math.min(5000, RecordingDeadline.remainingMillis(deadline)));
            if (reader.isAlive()) throw new IOException("H264 协议探针输出未结束");
            if (failure.get() instanceof IOException e) throw e;
            if (failure.get() != null) throw new IOException("H264 协议探针解析失败", failure.get());
            if (process.exitValue() != 0) throw invalid("协议探针未正常结束: exit=" + process.exitValue());
            RecordingDeadline.check(deadline);
            return parser.finish(decoded);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("H264 协议探针被中断", e);
        } finally { RecordingProcessCleanup.finish(process, reader); }
    }

    static final class Parser {
        private static final Pattern FIELD = Pattern.compile("\\]\\s+\\d+\\s+(\\S+)\\s+[01]+\\s*=\\s*(-?\\d+)\\s*$");
        private static final Pattern PTS = Pattern.compile("\\bpts\\s+(-?\\d+)");
        private static final Pattern DTS = Pattern.compile("\\bdts\\s+(-?\\d+)");
        private final Map<Long, Map<String, List<Long>>> sps = new HashMap<>();
        private final Map<Long, Map<String, List<Long>>> pps = new HashMap<>();
        private final List<Picture> pictures = new ArrayList<>();
        private Map<String, List<Long>> nal;
        private Packet packet;
        private long epoch;
        private long chars;
        private int fields;

        void accept(String line) throws IOException {
            chars += line.length() + 1L;
            if (line.length() > MAX_LINE || chars > MAX_TRACE_CHARS) throw invalid("协议日志容量超限");
            if (!line.contains("trace_headers")) return;
            if (line.contains("Packet:")) {
                finishNal(); finishPacket();
                var p = PTS.matcher(line); var d = DTS.matcher(line);
                if (!p.find() || !d.find()) throw invalid("包缺少显式 PTS/DTS");
                long pts = Long.parseLong(p.group(1)), dts = Long.parseLong(d.group(1));
                if (pts != dts) throw invalid("仅支持 PTS=DTS 的无重排图片: pts=" + pts + ", dts=" + dts);
                packet = new Packet(pts);
                return;
            }
            var field = FIELD.matcher(line);
            if (!field.find()) return;
            String name = field.group(1);
            if (name.equals("forbidden_zero_bit")) {
                finishNal(); nal = new LinkedHashMap<>(); fields = 0;
            }
            if (nal == null) throw invalid("NAL 字段缺少起始边界");
            if (++fields > 4096) throw invalid("NAL 字段数量超限");
            nal.computeIfAbsent(name, ignored -> new ArrayList<>()).add(Long.parseLong(field.group(2)));
        }

        Proof finish(List<Double> decoded) throws IOException {
            finishNal(); finishPacket();
            if (pictures.isEmpty() || pictures.size() != decoded.size()) throw invalid("协议图片与解码图片数量不同");
            Map<Edge, Sequence> candidates = new HashMap<>();
            Map<Sequence, Integer> normalEvidence = new HashMap<>();
            Set<Sequence> unknownCadence = new HashSet<>();
            long generation = 0;
            for (int i = 0; i < pictures.size(); i++) {
                Picture current = pictures.get(i);
                if (current.reset) generation++;
                if (current.pts != ticks(decoded.get(i))) throw invalid("协议图片与解码 PTS 不能逐一对应");
                if (i == 0) continue;
                Picture previous = pictures.get(i - 1);
                if (current.pts <= previous.pts) throw invalid("协议图片 PTS 重复或回退");
                if (!previous.reset && !current.reset && previous.epoch == current.epoch
                        && previous.pps == current.pps && previous.modulus == current.modulus) {
                    Sequence sequence = new Sequence(current.epoch, current.pps, current.modulus, current.interval, generation);
                    long step = Math.floorMod(current.poc - previous.poc, current.modulus);
                    if (Math.abs((current.pts - previous.pts) / 90000d - current.interval) <= .002) {
                        if (step == 2) normalEvidence.merge(sequence, 1, Integer::sum);
                        else unknownCadence.add(sequence);
                    }
                    if (step == 2) candidates.put(new Edge(previous.pts, current.pts), sequence);
                }
            }
            Set<Edge> edges = new HashSet<>();
            // type 0 本身不规定 +2。必须在同一参数版本的普通相邻图片中实际建立 +2 基线；
            // 否则 POC +1 的流丢一张后的 +2 会被误当作连续。
            candidates.forEach((edge, version) -> {
                if (!unknownCadence.contains(version) && normalEvidence.getOrDefault(version, 0) >= 2) edges.add(edge);
            });
            return new Proof(edges);
        }

        private void finishNal() throws IOException {
            if (nal == null) return;
            Map<String, List<Long>> values = nal;
            nal = null;
            if (one(values, "forbidden_zero_bit") != 0) throw invalid("NAL 禁止位非零");
            int type = (int) one(values, "nal_unit_type");
            if (type == 7 || type == 8) {
                Map<Long, Map<String, List<Long>>> table = type == 7 ? sps : pps;
                long id = one(values, type == 7 ? "seq_parameter_set_id" : "pic_parameter_set_id");
                if (id < 0 || id > (type == 7 ? 31 : 255)) throw invalid("参数集 ID 越界");
                if (type == 7) validateSps(values);
                else if (one(values, "bottom_field_pic_order_in_frame_present_flag") != 0
                        || one(values, "num_slice_groups_minus1") != 0
                        || one(values, "redundant_pic_cnt_present_flag") != 0) throw invalid("不支持该 PPS 图片模式");
                if (!values.equals(table.get(id))) { table.put(id, values); epoch++; }
            } else if (type == 1 || type == 5) {
                if (packet == null) throw invalid("图片没有所属包");
                long sliceType = one(values, "slice_type");
                if (sliceType < 0 || sliceType > 9 || (sliceType % 5 != 0 && sliceType % 5 != 2)) {
                    throw invalid("仅支持无 B 帧的 I/P 图片");
                }
                long ppsId = one(values, "pic_parameter_set_id");
                Map<String, List<Long>> pictureParameters = pps.get(ppsId);
                if (pictureParameters == null) throw invalid("图片 PPS 缺失");
                Map<String, List<Long>> sequence = sps.get(one(pictureParameters, "seq_parameter_set_id"));
                if (sequence == null) throw invalid("图片 SPS 缺失");
                long modulus = 1L << (one(sequence, "log2_max_pic_order_cnt_lsb_minus4") + 4);
                long poc = one(values, "pic_order_cnt_lsb");
                if (poc < 0 || poc >= modulus) throw invalid("POC 越界");
                one(values, "frame_num");
                boolean reset = type == 5 || values.getOrDefault("memory_management_control_operation", List.of()).contains(5L);
                double interval = 2d * one(sequence, "num_units_in_tick") / one(sequence, "time_scale");
                Picture picture = new Picture(packet.pts, poc, modulus, epoch, ppsId, interval, reset);
                long firstMb = one(values, "first_mb_in_slice");
                if (firstMb < 0 || packet.slices.size() >= 1024 || !packet.slices.add(firstMb)
                        || packet.picture != null && !packet.picture.equals(picture)) throw invalid("一个包包含不明确或重复的图片切片");
                packet.picture = picture;
            } else if (type < 6 || type > 12) throw invalid("未知 H264 NAL 模式");
        }

        private void finishPacket() throws IOException {
            if (packet == null) return;
            if (packet.picture == null || !packet.slices.contains(0L)) throw invalid("包没有完整图片起点");
            if (pictures.size() >= MAX_PICTURES) throw invalid("协议图片数量超限");
            pictures.add(packet.picture);
            packet = null;
        }

        private static void validateSps(Map<String, List<Long>> values) throws IOException {
            long bits = one(values, "log2_max_pic_order_cnt_lsb_minus4");
            if (one(values, "pic_order_cnt_type") != 0 || bits < 0 || bits > 12
                    || one(values, "frame_mbs_only_flag") != 1 || one(values, "timing_info_present_flag") != 1
                    || one(values, "fixed_frame_rate_flag") != 0 || one(values, "num_units_in_tick") <= 0
                    || one(values, "time_scale") <= 0 || one(values, "nal_hrd_parameters_present_flag") != 0
                    || one(values, "vcl_hrd_parameters_present_flag") != 0) {
                throw invalid("仅支持明确声明可变帧率的逐行 H264 POC type 0、无 HRD 模式");
            }
        }
        private static long one(Map<String, List<Long>> values, String name) throws IOException {
            List<Long> found = values.get(name);
            if (found == null || found.size() != 1) throw invalid("字段缺失或不唯一: " + name);
            return found.getFirst();
        }
        private static final class Packet {
            final long pts;
            final Set<Long> slices = new HashSet<>();
            Picture picture;
            Packet(long pts) { this.pts = pts; }
        }
    }

    private static long ticks(double value) {
        return Double.isFinite(value) && Math.abs(value) < Long.MAX_VALUE / 90000d ? Math.round(value * 90000) : Long.MIN_VALUE;
    }
    private static RecordingValidationException invalid(String reason) {
        return new RecordingValidationException("无法证明 H264 图片连续: " + reason);
    }
}
