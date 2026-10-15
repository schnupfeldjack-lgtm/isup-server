package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordingH264ContinuityProbeTest {
    static final List<Double> SOURCE = List.of(0d, .04, .08, .16, .20, .24, .28, .32);

    @Test void continuousPocAuthorizesOnlyTheExactSourcePair() throws Exception {
        var proof = parse(trace(), SOURCE);
        assertTrue(proof.proves(.08, .16));
        assertFalse(proof.proves(.08, .20));
        assertFalse(proof.proves(1.08, 1.16));
    }

    @Test void packetAndDecodedPicturesMustMatchOneForOne() throws Exception {
        var changed = new ArrayList<>(SOURCE);
        changed.set(3, .12);
        assertThrows(IOException.class, () -> parse(trace(), changed));
        assertThrows(IOException.class, () -> parse(trace(), SOURCE.subList(1, SOURCE.size())));
    }

    @Test void unknownOneStepPocCadenceCannotCallADroppedPictureContinuous() throws Exception {
        List<String> oneStep = trace();
        for (int i = 0; i < SOURCE.size(); i++) oneStep = replacePacketField(oneStep, i, "pic_order_cnt_lsb", String.valueOf(i < 3 ? i : i + 1));
        assertFalse(parse(oneStep, SOURCE).proves(.08, .16), "POC +1 的源在丢帧后 +2，不是已证明的 VFR");
    }

    @Test void anotherPpsCannotBorrowTheEstablishedPocCadence() throws Exception {
        List<String> lines = new ArrayList<>(trace());
        List<String> secondPps = replaceField(new ArrayList<>(lines.subList(sps(50000).size(), indexOfPacket(lines, 0))), "pic_parameter_set_id", "1");
        lines.addAll(indexOfPacket(lines, 0), secondPps);
        lines = replacePacketField(lines, 2, "pic_parameter_set_id", "1");
        lines = replacePacketField(lines, 3, "pic_parameter_set_id", "1");
        assertFalse(parse(lines, SOURCE).proves(.08, .16));
    }

    @Test void aNewIdrSequenceMustEstablishItsOwnPocCadence() throws Exception {
        List<Double> pts = List.of(0d, .04, .08, .12, .16, .20, .24, .32);
        List<String> lines = new ArrayList<>(trace());
        for (int i = 0; i < pts.size(); i++) {
            long tick = Math.round(pts.get(i) * 90000);
            lines.set(indexOfPacket(lines, i), "[trace_headers @ 0] Packet: 100 bytes, pts " + tick + ", dts " + tick);
            lines = replacePacketField(lines, i, "pic_order_cnt_lsb", String.valueOf(i < 4 ? i * 2 : (i - 4) * 2));
        }
        lines = replacePacketField(lines, 4, "nal_unit_type", "5");
        assertFalse(parse(lines, pts).proves(.24, .32));
    }

    @ParameterizedTest @ValueSource(strings = {
            "fixed_frame_rate_flag=1", "pic_order_cnt_type=2", "frame_mbs_only_flag=0",
            "timing_info_present_flag=0", "nal_hrd_parameters_present_flag=1",
            "bottom_field_pic_order_in_frame_present_flag=1", "num_slice_groups_minus1=1"})
    void unknownOrUnsupportedTimingNeverAuthorizesGap(String change) {
        String[] pair = change.split("=");
        assertThrows(IOException.class, () -> parse(replaceField(trace(), pair[0], pair[1]), SOURCE));
    }

    @Test void skippedPocIdrMmcoAndChangedParametersDoNotProveAGap() throws Exception {
        List<String> base = trace();
        assertFalse(parse(replacePacketField(base, 3, "pic_order_cnt_lsb", "8"), SOURCE).proves(.08, .16));
        assertFalse(parse(replacePacketField(base, 3, "nal_unit_type", "5"), SOURCE).proves(.08, .16));
        assertFalse(parse(insertPacketField(base, 2, "memory_management_control_operation", "5"), SOURCE).proves(.08, .16));
        List<String> changed = new ArrayList<>(base);
        int packet = indexOfPacket(changed, 3);
        changed.addAll(packet + 1, sps(50001));
        assertFalse(parse(changed, SOURCE).proves(.08, .16));
    }

    @Test void repeatedIdenticalParametersDoNotBreakProof() throws Exception {
        List<String> repeated = new ArrayList<>(trace());
        repeated.addAll(indexOfPacket(repeated, 3) + 1, sps(50000));
        assertTrue(parse(repeated, SOURCE).proves(.08, .16));
    }

    @Test void bPicturesDtsReorderingAndMultiplePicturesPerPacketFailClosed() {
        assertThrows(IOException.class, () -> parse(replacePacketField(trace(), 3, "slice_type", "6"), SOURCE));
        List<String> dts = new ArrayList<>(trace());
        dts.set(indexOfPacket(dts, 3), "[trace_headers @ 0] Packet: 100 bytes, pts 14400, dts 10800, duration 3600.");
        assertThrows(IOException.class, () -> parse(dts, SOURCE));
        List<String> twoPictures = new ArrayList<>(trace());
        twoPictures.addAll(indexOfPacket(twoPictures, 4), slice(1, 8, 4));
        assertThrows(IOException.class, () -> parse(twoPictures, SOURCE));
    }

    @Test void truncatedOrOversizedTraceFailsClosed() {
        assertThrows(IOException.class, () -> parse(List.of("[trace_headers @ 0] Packet: 100 bytes, pts 0, dts 0"), List.of(0d)));
        assertThrows(IOException.class, () -> new RecordingH264ContinuityProbe.Parser().accept("x".repeat(16385)));
    }

    static RecordingH264ContinuityProbe.Proof parse(List<String> lines, List<Double> decoded) throws IOException {
        var parser = new RecordingH264ContinuityProbe.Parser();
        for (String line : lines) parser.accept(line);
        return parser.finish(decoded);
    }

    static List<String> trace() {
        List<String> lines = new ArrayList<>(sps(50000));
        lines.addAll(List.of(field("forbidden_zero_bit", 0), field("nal_ref_idc", 3), field("nal_unit_type", 8),
                field("pic_parameter_set_id", 0), field("seq_parameter_set_id", 0),
                field("bottom_field_pic_order_in_frame_present_flag", 0), field("num_slice_groups_minus1", 0),
                field("redundant_pic_cnt_present_flag", 0)));
        for (int i = 0; i < SOURCE.size(); i++) {
            long pts = Math.round(SOURCE.get(i) * 90000);
            lines.add("[trace_headers @ 0] Packet: 100 bytes, pts " + pts + ", dts " + pts + ", duration 3600.");
            lines.addAll(slice(i == 0 ? 5 : 1, i * 2, i / 2));
        }
        return lines;
    }

    static List<String> sps(int scale) {
        return List.of(field("forbidden_zero_bit", 0), field("nal_ref_idc", 3), field("nal_unit_type", 7),
                field("seq_parameter_set_id", 0), field("pic_order_cnt_type", 0),
                field("log2_max_pic_order_cnt_lsb_minus4", 9), field("frame_mbs_only_flag", 1),
                field("timing_info_present_flag", 1), field("num_units_in_tick", 1000), field("time_scale", scale),
                field("fixed_frame_rate_flag", 0), field("nal_hrd_parameters_present_flag", 0),
                field("vcl_hrd_parameters_present_flag", 0));
    }

    static List<String> slice(int type, int poc, int frame) {
        return List.of(field("forbidden_zero_bit", 0), field("nal_ref_idc", frame % 2 == 0 ? 2 : 0),
                field("nal_unit_type", type), field("first_mb_in_slice", 0), field("slice_type", type == 5 ? 7 : 5),
                field("pic_parameter_set_id", 0), field("frame_num", frame), field("pic_order_cnt_lsb", poc),
                field("adaptive_ref_pic_marking_mode_flag", 0));
    }

    static String field(String name, long value) { return "[trace_headers @ 0] 0 " + name + " 1 = " + value; }

    private static List<String> replaceField(List<String> lines, String name, String value) {
        return lines.stream().map(line -> line.contains(" " + name + " ") ? field(name, Long.parseLong(value)) : line).toList();
    }
    private static int indexOfPacket(List<String> lines, int index) {
        int count = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("Packet:") && ++count == index) return i;
        return lines.size();
    }
    private static List<String> replacePacketField(List<String> lines, int packet, String name, String value) {
        List<String> result = new ArrayList<>(lines);
        for (int i = indexOfPacket(result, packet) + 1; i < indexOfPacket(result, packet + 1); i++) {
            if (result.get(i).contains(" " + name + " ")) result.set(i, field(name, Long.parseLong(value)));
        }
        return result;
    }
    private static List<String> insertPacketField(List<String> lines, int packet, String name, String value) {
        List<String> result = new ArrayList<>(lines);
        result.add(indexOfPacket(result, packet + 1), field(name, Long.parseLong(value)));
        return result;
    }
}
