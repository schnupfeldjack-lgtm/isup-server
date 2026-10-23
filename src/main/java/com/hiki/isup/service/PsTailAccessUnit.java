package com.hiki.isup.service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;

/** Conservative, bounded proof of one truncated terminal H264 slice, independent of HK anchor ownership. */
final class PsTailAccessUnit {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PACKETS = 1024;
    record Proof(long cutoffBytes, int streamId, long pts90k, long dts90k,
                 LocalDateTime hk, long truncatedPesOffset, int missingBytes) {}
    private ByteArrayOutputStream payload;
    private long cutoff, pts, dts;
    private int stream, packets;
    private LocalDateTime hk;
    private Proof proof;

    void barrier() { payload = null; proof = null; }

    void video(int type, int id, long timestamp, long decodeTimestamp, LocalDateTime time,
               long startOffset, long packetOffset, byte[] data, int from, boolean complete) {
        proof = null;
        if (type != 0x1b) { barrier(); return; }
        if (timestamp >= 0 && (payload == null || timestamp != pts || decodeTimestamp != dts)) {
            barrier();
            if (timestamp != decodeTimestamp || !startsSlice(data, from)) return;
            payload = new ByteArrayOutputStream();
            cutoff = startOffset; pts = timestamp; dts = decodeTimestamp; stream = id; hk = time; packets = 0;
        } else if (time != null) { barrier(); return; }
        if (payload == null || id != stream || ++packets > MAX_PACKETS || data.length - from > MAX_BYTES - payload.size()) {
            barrier(); return;
        }
        payload.write(data, from, data.length - from);
        if (!complete && singleSlice(payload.toByteArray())) {
            proof = new Proof(cutoff, stream, pts, dts, hk, packetOffset, 0);
        }
    }

    void eof(int missingBytes) {
        if (proof != null && missingBytes > 0) proof = new Proof(proof.cutoffBytes(), stream, pts, dts,
                hk, proof.truncatedPesOffset(), missingBytes);
        else proof = null;
    }
    Proof proof() { return proof != null && proof.missingBytes() > 0 ? proof : null; }

    private static boolean startsSlice(byte[] data, int from) {
        int nal = prefixEnd(data, from);
        if (nal < 0 || nal + 1 >= data.length || (data[nal] & 0x80) != 0) return false;
        int type = data[nal] & 31;
        if (type != 1 && type != 5) return false;
        // first_mb_in_slice must be zero; the next two Exp-Golomb values must be valid.
        Bits bits = new Bits(data, nal + 1);
        return bits.ue() == 0 && bits.ue() <= 9 && bits.ue() <= 255 && !bits.invalid;
    }

    private static boolean singleSlice(byte[] data) {
        if (!startsSlice(data, 0)) return false;
        int nal = prefixEnd(data, 0);
        // Search joined ES bytes, including prefixes split across any number of PES packets.
        for (int i = nal + 1; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) return false;
        }
        return true;
    }

    private static int prefixEnd(byte[] data, int from) {
        if (from + 3 >= data.length || data[from] != 0 || data[from + 1] != 0) return -1;
        if (data[from + 2] == 1) return from + 3;
        return from + 4 < data.length && data[from + 2] == 0 && data[from + 3] == 1 ? from + 4 : -1;
    }

    private static final class Bits {
        private final byte[] data;
        private int bit;
        private boolean invalid;
        Bits(byte[] data, int offset) { this.data = data; bit = offset * 8; }
        int read() {
            if (bit >= data.length * 8) { invalid = true; return 0; }
            return (data[bit / 8] >>> (7 - bit++ % 8)) & 1;
        }
        int ue() {
            int zeros = 0;
            while (read() == 0 && !invalid) if (++zeros > 15) { invalid = true; return Integer.MAX_VALUE; }
            if (invalid) return Integer.MAX_VALUE;
            int value = 1;
            for (int i = 0; i < zeros; i++) value = (value << 1) | read();
            return value - 1;
        }
    }
}
