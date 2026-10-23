package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import static com.hiki.isup.service.PsRecordingTimelineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PsTailRepairTest {
    static final LocalDateTime WALL = LocalDateTime.of(2026, 10, 20, 10, 30);
    static final long ORIGIN = 900_000;
    // One non-IDR slice, first_mb_in_slice=0, slice_type=0, pic_parameter_set_id=0.
    static final byte[] SLICE = {0, 0, 0, 1, 0x41, (byte) 0xe0, 0x55, 0x55};

    @Test void provesWholeUntimedContinuationAuWithoutRemovingLastGoodHkAnchor() throws Exception {
        byte[] prefix = anchors();
        PsRecordingTimeline timeline = parse(join(prefix, pes(0xe0, ORIGIN + 6 * 90_000, SLICE), partial(new byte[]{0x55, 0x66})));
        var proof = proof(timeline, WALL.plusSeconds(1), WALL.plusSeconds(4));
        assertEquals(prefix.length, proof.cutoffBytes());
        assertEquals(ORIGIN + 6 * 90_000, proof.pts90k());
        assertEquals(2, timeline.anchors().size());
    }

    @Test void rejectsCrossPesNalBoundaryEvenWhenNoIndividualContinuationContainsStartCode() throws Exception {
        byte[] tail = join(pes(0xe0, ORIGIN + 6 * 90_000, join(SLICE, new byte[]{0, 0})),
                partial(new byte[]{1, 0x41, (byte) 0xe0, 0x55}));
        reject(join(anchors(), tail), WALL.plusSeconds(4));
    }

    @Test void rejectsBusinessInteriorBoundaryAndErrorMargin() throws Exception {
        for (long extra : new long[]{0, 3_600, 7_200}) {
            byte[] tail = join(pes(0xe0, ORIGIN + 5 * 90_000 + extra, SLICE), partial(new byte[]{0x55}));
            reject(join(anchors(), tail), WALL.plusSeconds(5));
        }
    }

    @Test void rejectsUnknownAuCompleteEofAndInterleaving() throws Exception {
        reject(join(anchors(), partial(new byte[]{0x55})), WALL.plusSeconds(4));
        reject(join(anchors(), pes(0xe0, ORIGIN + 6 * 90_000, SLICE)), WALL.plusSeconds(4));
        reject(join(anchors(), pes(0xe0, ORIGIN + 6 * 90_000, SLICE), hikPrivatePes(2, ORIGIN + 6 * 90_000), partial(new byte[]{0x55})), WALL.plusSeconds(4));
        reject(join(anchors(), pes(0xe0, ORIGIN + 6 * 90_000, new byte[]{0x55}), partial(new byte[]{0x55})), WALL.plusSeconds(4));
    }

    @Test void rejectsUnboundedExtrapolationSparseOrRollbackClocks() throws Exception {
        byte[] tail = join(pes(0xe0, ORIGIN + 16 * 90_000, SLICE), partial(new byte[]{0x55}));
        reject(join(anchors(), tail), WALL.plusSeconds(4));
        reject(join(anchor(WALL, ORIGIN), anchor(WALL.plusSeconds(11), ORIGIN + 11 * 90_000), tail), WALL.plusSeconds(4));
        reject(join(anchors(), anchor(WALL.minusSeconds(1), ORIGIN + 6 * 90_000),
                pes(0xe0, ORIGIN + 7 * 90_000, SLICE), partial(new byte[]{0x55})), WALL.plusSeconds(4));
    }

    @Test void rejectsCrossPesPrefixSpreadAcrossThreeContinuationPackets() throws Exception {
        reject(join(anchors(), pes(0xe0, ORIGIN + 6 * 90_000, SLICE),
                PsRecordingDuplicateAccessUnitTest.untimedPes(0xe0, new byte[]{0}),
                PsRecordingDuplicateAccessUnitTest.untimedPes(0xe0, new byte[]{0}),
                partial(new byte[]{1, 0x09, 0x55})), WALL.plusSeconds(4));
    }

    @Test void rejectsNonFirstSliceParameterSetsMissingPesHeaderAndDifferentStream() throws Exception {
        for (byte[] nal : new byte[][]{{0, 0, 1, 0x41, 0x40, 0x55}, {0, 0, 1, 0x67, 0x55},
                {0, 0, 1, (byte) 0xc1, (byte) 0xe0}, join(SLICE, new byte[]{0, 0, 1, 0x68, 0x55})}) {
            reject(join(anchors(), pes(0xe0, ORIGIN + 6 * 90_000, nal), partial(new byte[]{0x55})), WALL.plusSeconds(4));
        }
        assertThrows(IOException.class, () -> parse(join(anchors(), pes(0xe0, ORIGIN + 6 * 90_000, SLICE),
                new byte[]{0, 0, 1, (byte) 0xe0, 0, 100, (byte) 0x80})));
        assertThrows(IOException.class, () -> parse(join(anchors(), pes(0xe1, ORIGIN + 6 * 90_000, SLICE), partial(new byte[]{0x55}))));
    }

    @Test void rejectsExcessiveAuBytesWithoutUnboundedAllocation() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(anchors()); out.writeBytes(pes(0xe0, ORIGIN + 6 * 90_000, SLICE));
        byte[] payload = new byte[60_000]; Arrays.fill(payload, (byte) 0x55);
        for (int i = 0; i < 36; i++) out.writeBytes(PsRecordingDuplicateAccessUnitTest.untimedPes(0xe0, payload));
        out.writeBytes(partial(new byte[]{0x55}));
        reject(out.toByteArray(), WALL.plusSeconds(4));
    }

    static byte[] anchors() { return join(anchor(WALL, ORIGIN), anchor(WALL.plusSeconds(5), ORIGIN + 5 * 90_000)); }
    static byte[] partial(byte[] payload) {
        byte[] packet = PsRecordingDuplicateAccessUnitTest.untimedPes(0xe0, join(payload, new byte[20]));
        return Arrays.copyOf(packet, packet.length - 20);
    }
    static PsRecordingTimeline parse(byte[] data) throws IOException { return PsRecordingTimeline.parse(new ByteArrayInputStream(data)); }
    static PsRecordingTimeline.TailRepairPlan proof(PsRecordingTimeline timeline, LocalDateTime start, LocalDateTime end) throws IOException {
        return timeline.tailRepairPlan(start, end);
    }
    static void reject(byte[] bytes, LocalDateTime end) throws Exception {
        PsRecordingTimeline timeline = parse(bytes);
        assertThrows(IOException.class, () -> proof(timeline, WALL.plusSeconds(1), end));
    }
}
