package com.hiki.isup.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 仅识别实录已证实的 H.264 SPS/PPS/单 IDR 整组重发，不推断其它重复时间戳。 */
final class PsDuplicateAccessUnits {
    // 同时至多保留前后两组；普通大帧不受影响，只是不具备本兼容分支的去重资格。
    private static final int MAX_UNIT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_UNIT_PACKETS = 1024;
    private static final int MAX_SKIPPED_PACKETS = 10_000;
    private final List<VideoPacket> skipped = new ArrayList<>();
    private Candidate current;
    private Completed previous;
    private byte[] map;
    private int videoType = -1;

    record VideoPacket(long start, long end) { }
    private record Completed(int id, LocalDateTime wall, long pts, long dts, byte[] map,
                             byte[] payload, byte[] hash) { }

    List<VideoPacket> skippedPackets() { return List.copyOf(skipped); }

    void psm(byte[] bytes, int type) throws IOException {
        if (current == null) previous = null;
        finishCandidate();
        map = bytes.clone();
        videoType = type;
    }

    boolean permitsRepeatedAnchor(int id, LocalDateTime wall, long pts, long dts) {
        return previous != null && videoType == 0x1b && previous.id() == id
                && previous.pts() == pts && previous.dts() == dts && previous.wall().equals(wall)
                && Arrays.equals(previous.map(), map);
    }

    void video(int id, LocalDateTime wall, long pts, long dts, byte[] data, int payloadAt,
               long start, long end, boolean duplicate) throws IOException {
        if (wall != null) {
            current = new Candidate(id, wall, pts, dts, map, duplicate ? previous : null);
            previous = null;
        } else if (current != null && (current.pts != pts || current.dts != dts)) {
            finishCandidate();
            // 无 HK 的下一帧不能继续沿用前一帧的重发证明。
            previous = null;
        }
        if (wall == null && current == null) previous = null;
        append(data, payloadAt, start, end);
    }

    /** 完整无时间戳 PES 只可续接已由 HK/PTS 建立的同流候选，不能自行建立 AU 或推造时间。 */
    void continuation(int id, byte[] data, int payloadAt, long start, long end) throws IOException {
        if (current == null || current.id != id) {
            uncertainVideo();
            return;
        }
        append(data, payloadAt, start, end);
    }

    private void append(byte[] data, int payloadAt, long start, long end) throws IOException {
        if (current != null) {
            if (videoType != 0x1b || ++current.packetCount > MAX_UNIT_PACKETS
                    || current.payload.size() + data.length - payloadAt > MAX_UNIT_BYTES) {
                if (current.expected != null) throw invalid("重复候选超出有界完整 AU 范围");
                current = null;
                previous = null;
                return;
            }
            current.payload.write(data, payloadAt, data.length - payloadAt);
            if (current.expected != null) current.packets.add(new VideoPacket(start, end));
        }
    }

    void uncertainVideo() throws IOException {
        if (current != null && current.expected != null) throw invalid("重复候选含无时间戳或不完整 PES");
        current = null;
        previous = null;
    }

    void end() throws IOException {
        if (current != null && current.expected != null) throw invalid("重复候选缺少下一 AU 边界");
    }

    private void finishCandidate() throws IOException {
        if (current == null) return;
        byte[] payload = current.payload.toByteArray();
        byte[] hash = sha256().digest(payload);
        if (current.expected != null) {
            Completed expected = current.expected;
            if (payload.length != expected.payload().length || !MessageDigest.isEqual(hash, expected.hash())
                    || !Arrays.equals(payload, expected.payload()) || !singleIdr(payload)) {
                throw invalid("同 HK/PTS/DTS 的视频内容不同或不能证明单 IDR 分组");
            }
            if (skipped.size() + current.packets.size() > MAX_SKIPPED_PACKETS) {
                throw invalid("重复视频 PES 数超过安全上限");
            }
            skipped.addAll(current.packets);
        }
        previous = singleIdr(payload) ? new Completed(current.id, current.wall, current.pts, current.dts,
                current.map, payload, hash) : null;
        current = null;
    }

    /** 不接受一般多切片/无参数集帧；结构完整性还须由后续严格解码证明。 */
    private static boolean singleIdr(byte[] data) {
        int[] expected = {7, 8, 5};
        int count = 0;
        int idr = -1;
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] != 0 || data[i + 1] != 0) continue;
            int prefix = data[i + 2] == 1 ? 3 : data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
            if (prefix == 0) continue;
            int header = i + prefix;
            if (count >= expected.length || count == 0 && i != 0 || header + 1 >= data.length
                    || (data[header] & 0x80) != 0 || (data[header] & 0x60) == 0
                    || (data[header] & 0x1f) != expected[count]) return false;
            if (count == 2) idr = header + 1;
            count++;
            i = header;
        }
        if (count != 3) return false;
        // 单 IDR slice 必须从 first_mb=0 开始，且 slice_type 为 I；PPS/SPS 的完整解析交给严格解码器。
        int[] bit = {idr * 8};
        int firstMb = unsignedGolomb(data, bit);
        int sliceType = unsignedGolomb(data, bit);
        int pps = unsignedGolomb(data, bit);
        return firstMb == 0 && (sliceType == 2 || sliceType == 7) && pps >= 0 && pps <= 255;
    }

    private static int unsignedGolomb(byte[] data, int[] bit) {
        int zeros = 0;
        while (true) {
            if (bit[0] >= data.length * 8) return -1;
            int next = (data[bit[0] / 8] >>> (7 - bit[0] % 8)) & 1;
            bit[0]++;
            if (next != 0) break;
            if (++zeros > 15) return -1;
        }
        if (bit[0] + zeros > data.length * 8) return -1;
        int value = 1;
        for (int i = 0; i < zeros; i++) value = (value << 1) | ((data[bit[0] / 8] >>> (7 - bit[0]++ % 8)) & 1);
        return value - 1;
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("缺少 SHA-256", e); }
    }

    private static RecordingValidationException invalid(String reason) {
        return new RecordingValidationException("不能安全去除重复视频 AU：" + reason);
    }

    private static final class Candidate {
        private final int id;
        private final LocalDateTime wall;
        private final long pts;
        private final long dts;
        private final byte[] map;
        private final Completed expected;
        private final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        private final List<VideoPacket> packets = new ArrayList<>();
        private int packetCount;

        private Candidate(int id, LocalDateTime wall, long pts, long dts, byte[] map, Completed expected) {
            this.id = id; this.wall = wall; this.pts = pts; this.dts = dts;
            this.map = map; this.expected = expected;
        }
    }
}
