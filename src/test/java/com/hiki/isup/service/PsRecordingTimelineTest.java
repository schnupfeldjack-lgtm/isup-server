package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class PsRecordingTimelineTest {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 14, 13, 37, 50);

    @ParameterizedTest @ValueSource(ints = {2, 8})
    void knownHikPrivateRecordsDoNotInventASecondAudioTrack(int type) throws Exception {
        var timeline = parse(join(anchor(BASE, 900_000), pes(0xc0, 900_000, new byte[320]),
                hikPrivatePes(type, 900_000)));
        assertEquals(1, timeline.audioStreamCount(), "海康私有记录不是第二条音轨: type=" + type);
    }

    @Test void knownHikPrivateRecordsAloneDoNotDeclareAudio() throws Exception {
        assertEquals(0, parse(join(anchor(BASE, 900_000), hikPrivatePes(2, 900_000),
                hikPrivatePes(8, 900_000))).audioStreamCount());
    }

    @Test void unknownOrMalformedPrivateRecordsRemainPotentialAudio() throws Exception {
        byte[] unknown = hikPrivatePes(8, 900_000);
        unknown[17] = 9;
        byte[] shortLength = hikPrivatePes(8, 900_000);
        shortLength[19]--;
        byte[] longLength = hikPrivatePes(2, 900_000);
        longLength[19]++;
        byte[] badPts = hikPrivatePes(8, 900_000);
        badPts[11] &= (byte) 0xfe;
        byte[] badHeader = hikPrivatePes(8, 900_000);
        badHeader[8] = (byte) 255;
        byte[] incompleteRecord = hikPrivatePes(8, 900_000);
        incompleteRecord[15] = (byte) 0xf9;
        byte[] scrambled = hikPrivatePes(8, 900_000);
        scrambled[6] |= 0x10;
        byte[] audioLike = pes(0xbd, 900_000, new byte[400]);
        for (byte[] candidate : new byte[][]{unknown, shortLength, longLength, badPts, badHeader,
                incompleteRecord, scrambled, audioLike}) {
            assertEquals(2, parse(join(anchor(BASE, 900_000), pes(0xc0, 900_000, new byte[320]),
                    candidate)).audioStreamCount(), "未证明是私有记录时必须保留潜在音频保护");
        }
    }

    @Test void knownPrivateRecordsDoNotHideTwoRealAudioStreamIds() throws Exception {
        assertEquals(2, parse(join(anchor(BASE, 900_000), pes(0xc0, 900_000, new byte[320]),
                pes(0xc1, 900_000, new byte[320]), hikPrivatePes(8, 900_000))).audioStreamCount());
    }

    @Test void explicitlyDeclaredPrivateAudioCannotBeOverriddenByARecordShapedPayload() throws Exception {
        byte[] map = java.util.Arrays.copyOf(psm(BASE), psm(BASE).length + 4);
        map[5] += 4; map[27] = 8; map[32] = (byte) 0x90; map[33] = (byte) 0xbd;
        for (boolean declarationFirst : new boolean[]{true, false}) {
            byte[] privatePacket = hikPrivatePes(8, 900_000);
            byte[] declaration = join(map, pes(0xe0, 900_000, new byte[]{1}));
            var timeline = parse(join(pack(), declarationFirst ? declaration : privatePacket,
                    declarationFirst ? privatePacket : declaration, pes(0xc0, 900_000, new byte[320])));
            assertEquals(2, timeline.audioStreamCount(), "PSM 音频声明优先于私有载荷外观");
        }
    }

    @Test void incompleteLastPesDoesNotDiscardCompleteEarlierOrderAnchors() throws Exception {
        byte[] prefix = join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000));
        byte[] tail = anchor(BASE.plusSeconds(6), 1_440_000);
        var timeline = parse(join(prefix, java.util.Arrays.copyOf(tail, tail.length - 1)));
        var window = timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(4));
        assertEquals(2, window.anchors().size(), "残包的 HK 不得用作覆盖证据");
        assertEquals(14, window.endPtsSeconds(), 1e-9);
        assertTrue(timeline.hasIncompleteTail());
        assertEquals(prefix.length + tail.length - 1, timeline.sourceBytes());
        assertEquals(prefix.length + pack().length, timeline.completePrefixBytes(), "须排除尾部待绑定 PSM");
    }

    @Test void incompleteLastPesCannotSupplyMissingOrderTailCoverage() throws Exception {
        byte[] tail = anchor(BASE.plusSeconds(6), 1_440_000);
        var timeline = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000),
                java.util.Arrays.copyOf(tail, tail.length - 1)));
        var failure = assertThrows(RecordingCoverageException.class,
                () -> timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(6)));
        assertEquals(BASE.plusSeconds(5), failure.availableEnd());
    }

    @Test void incompleteSameClockContinuationExcludesItsWholeHkAccessUnit() throws Exception {
        byte[] prefix = join(decodeAnchor(BASE, 900_000, 891_000),
                decodeAnchor(BASE.plusSeconds(5), 1_350_000, 1_341_000));
        byte[] continuation = pesDts(0xe0, 1_440_000, 1_431_000, new byte[]{1, 2, 3});
        var timeline = parse(join(prefix, decodeAnchor(BASE.plusSeconds(6), 1_440_000, 1_431_000),
                java.util.Arrays.copyOf(continuation, continuation.length - 1)));
        assertEquals(2, timeline.anchors().size(), "未完整 AU 的首 PES 锚点也必须排除");
        assertEquals(prefix.length + pack().length, timeline.completePrefixBytes(), "回退至整个 AU 的 HK PSM 之前");
        assertEquals(14, timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(4)).endPtsSeconds(), 1e-9);
        var failure = assertThrows(RecordingCoverageException.class,
                () -> timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(7)));
        assertEquals(BASE.plusSeconds(5), failure.availableEnd());
    }

    @Test void differentDecodeClockCannotBeAssumedToContinueThePreviousAccessUnit() throws Exception {
        byte[] prefix = join(decodeAnchor(BASE, 900_000, 891_000),
                decodeAnchor(BASE.plusSeconds(5), 1_350_000, 1_341_000),
                decodeAnchor(BASE.plusSeconds(6), 1_440_000, 1_431_000));
        byte[] continuation = pesDts(0xe0, 1_440_000, 1_435_500, new byte[]{1, 2, 3});
        var timeline = parse(join(prefix, java.util.Arrays.copyOf(continuation, continuation.length - 1)));
        assertEquals(3, timeline.anchors().size(), "PTS 相同而 DTS 不同不能猜测为同一 AU");
        assertEquals(prefix.length, timeline.completePrefixBytes());
    }

    @Test void tailCompatibilityDoesNotHideMalformedFirstOrMiddlePacket() {
        byte[] badHead = pack();
        badHead[4] = 0;
        byte[] reliable = join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000));
        byte[] tail = anchor(BASE.plusSeconds(6), 1_440_000);
        byte[] incomplete = java.util.Arrays.copyOf(tail, tail.length - 1);
        assertThrows(RecordingValidationException.class, () -> parse(join(badHead, reliable, incomplete)));
        assertThrows(RecordingValidationException.class, () -> parse(join(reliable, badHead, incomplete)));
        assertThrows(RecordingValidationException.class, () -> parse(join(reliable, new byte[]{0, 0, 2}, incomplete)));
    }

    @Test void inputReadFailureIsNotMisclassifiedAsAnIncompleteTail() {
        byte[] reliable = join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000));
        var diskFailure = new java.io.InputStream() {
            private final ByteArrayInputStream data = new ByteArrayInputStream(reliable);
            @Override public int read() throws IOException {
                int next = data.read();
                if (next < 0) throw new IOException("disk-read-failed");
                return next;
            }
        };
        IOException failure = assertThrows(IOException.class, () -> PsRecordingTimeline.parse(diskFailure));
        assertEquals("disk-read-failed", failure.getMessage());
    }

    @Test void decodeClockFailureContainsBothClockAndPacketContexts() {
        IOException failure = assertThrows(IOException.class,
                () -> parse(join(decodeAnchor(BASE, 900_000, 891_000),
                        decodeAnchor(BASE.plusSeconds(1), 990_000, 890_000))));
        for (String detail : new String[]{"previousPts=900000", "previousDts=891000", "pts=990000", "dts=890000",
                "previousHk=" + BASE, "hk=" + BASE.plusSeconds(1), "previousOffset=", "PS offset="}) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }

    @Test void incompleteTailCannotHideKnownClockRollbackOrOverlappingHk() {
        byte[] prefix = join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000));
        byte[] reset = anchor(BASE.plusSeconds(6), 450_000);
        assertThrows(RecordingValidationException.class,
                () -> parse(join(prefix, java.util.Arrays.copyOf(reset, reset.length - 1))));
        byte[] overlap = anchor(BASE.plusSeconds(2), 1_440_000);
        assertThrows(RecordingValidationException.class, () -> parse(join(prefix,
                java.util.Arrays.copyOf(overlap, overlap.length - 1))).plan(BASE.plusSeconds(1), BASE.plusSeconds(4)));
    }

    @Test void monotonicIncompleteTailStillReportsCoverageShortageWithoutInventingItsFrame() throws Exception {
        byte[] tail = anchor(BASE.plusNanos(5_500_000_000L), 1_395_000);
        var timeline = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000),
                java.util.Arrays.copyOf(tail, tail.length - 1)));
        var failure = assertThrows(RecordingCoverageException.class,
                () -> timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(6)));
        assertEquals(BASE.plusSeconds(5), failure.availableEnd(), "不完整的5.5秒帧不计入已证覆盖");
    }

    @Test void continuousCoverageShortageIsDistinctFromUntrustedTimeline() throws Exception {
        var timeline = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000)));
        for (LocalDateTime[] window : new LocalDateTime[][]{
                {BASE.plusSeconds(1), BASE.plusSeconds(6)}, {BASE.minusSeconds(1), BASE.plusSeconds(4)}}) {
            IOException failure = assertThrows(IOException.class, () -> timeline.plan(window[0], window[1]));
            assertEquals("RecordingCoverageException", failure.getClass().getSimpleName());
            var coverage = (RecordingCoverageException) failure;
            assertEquals(window[0], coverage.requestedStart());
            assertEquals(window[1], coverage.requestedEnd());
            assertEquals(BASE, coverage.availableStart());
            assertEquals(BASE.plusSeconds(5), coverage.availableEnd());
        }
    }

    @Test void sparseJumpedAmbiguousOrSingleAnchorMustNotBecomeCoverageRetry() throws Exception {
        for (byte[] bytes : new byte[][]{
                join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(11), 1_890_000)),
                join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(2), 1_350_000)),
                join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000),
                        anchor(BASE, 1_800_000), anchor(BASE.plusSeconds(5), 2_250_000)),
                anchor(BASE, 900_000)}) {
            assertThrows(RecordingValidationException.class,
                    () -> parse(bytes).plan(BASE.plusSeconds(1), BASE.plusSeconds(6)));
        }
    }

    @Test void parsingHonorsCancellationInsteadOfContinuingACpuScan() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> parse(anchor(BASE, 900_000)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void acceptsPresentationReorderingOnlyWithMonotonicDecodeClockAndMatchingWallClock() throws Exception {
        PsRecordingTimeline timeline = parse(join(
                decodeAnchor(BASE, 900_000, 891_000),
                decodeAnchor(BASE.plusNanos(150_000_000), 913_500, 895_500),
                decodeAnchor(BASE.plusNanos(50_000_000), 904_500, 900_000),
                decodeAnchor(BASE.plusNanos(100_000_000), 909_000, 904_500),
                decodeAnchor(BASE.plusSeconds(1), 990_000, 981_000)));
        var window = timeline.plan(BASE.plusNanos(50_000_000), BASE.plusNanos(900_000_000));
        assertEquals(10.05, window.startPtsSeconds(), 1e-9);
        assertEquals(10.9, window.endPtsSeconds(), 1e-9);
        assertEquals(5, window.anchors().size());
    }

    @Test void rejectsDecodeRollbackWrapAndPresentationBeforeDecodeEvenWhenPtsLooksOrdered() {
        assertThrows(IOException.class, () -> parse(join(decodeAnchor(BASE, 900_000, 891_000),
                decodeAnchor(BASE.plusSeconds(1), 990_000, 890_000))));
        assertThrows(IOException.class, () -> parse(join(decodeAnchor(BASE, (1L << 33) - 9000, (1L << 33) - 18000),
                decodeAnchor(BASE.plusSeconds(1), 9000, 4500))));
        assertThrows(IOException.class, () -> parse(decodeAnchor(BASE, 900_000, 909_000)));
    }

    @Test void presentationReorderingCannotHideAWallClockRollback() throws Exception {
        PsRecordingTimeline timeline = parse(join(decodeAnchor(BASE, 900_000, 891_000),
                decodeAnchor(BASE.plusSeconds(1), 990_000, 895_500),
                decodeAnchor(BASE.minusSeconds(2), 904_500, 900_000),
                decodeAnchor(BASE.plusSeconds(2), 1_080_000, 1_071_000)));
        assertThrows(IOException.class, () -> timeline.plan(BASE.plusNanos(500_000_000), BASE.plusNanos(1_500_000_000)));
    }

    @Test void rejectsPartialOverlapAndIsolatedAnchorAfterClockRollback() throws Exception {
        byte[] reliable = join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(10), 1_800_000));
        PsRecordingTimeline overlap = parse(join(reliable, anchor(BASE.plusSeconds(4), 2_700_000),
                anchor(BASE.plusSeconds(6), 2_880_000)));
        assertThrows(IOException.class, () -> overlap.plan(BASE.plusSeconds(2), BASE.plusSeconds(8)));
        PsRecordingTimeline isolated = parse(join(reliable, anchor(BASE.plusSeconds(4), 2_700_000)));
        assertThrows(IOException.class, () -> isolated.plan(BASE.plusSeconds(2), BASE.plusSeconds(8)));
    }

    @Test
    void mapsBusinessWindowUsingAbsolutePesPtsRatherThanMp4Zero() throws Exception {
        PsRecordingTimeline timeline = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000),
                anchor(BASE.plusSeconds(10), 1_800_000)));
        PsRecordingTimeline.Window window = timeline.plan(BASE.plusSeconds(2), BASE.plusSeconds(8));
        assertEquals(12, window.startPtsSeconds(), .000001);
        assertEquals(18, window.endPtsSeconds(), .000001);
        assertEquals(3, window.anchors().size());
        assertEquals(0xe0, timeline.streamId());
    }

    @Test
    void discardsInitialHeldFrameMappingOnlyWhenLaterSegmentCoversEntireWindow() throws Exception {
        PsRecordingTimeline timeline = parse(join(anchor(BASE, 900_000), anchor(BASE, 1_935_900),
                anchor(BASE.plusSeconds(5), 2_385_900), anchor(BASE.plusSeconds(10), 2_835_900)));
        PsRecordingTimeline.Window cut = timeline.plan(BASE.plusSeconds(2), BASE.plusSeconds(8));
        assertEquals(23.51, cut.startPtsSeconds(), .000001);
        assertEquals(29.51, cut.endPtsSeconds(), .000001);
        assertEquals(3, cut.anchors().size());
    }

    @Test
    void refusesWallClockJumpInsideBusinessWindowInsteadOfInventingMissingFrames() throws Exception {
        PsRecordingTimeline timeline = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000),
                anchor(BASE.plusSeconds(15), 1_395_000), anchor(BASE.plusSeconds(20), 1_845_000)));
        assertThrows(IOException.class, () -> timeline.plan(BASE.plusSeconds(2), BASE.plusSeconds(18)));
    }

    @Test
    void permitsClockJumpBeforeBusinessWindowWhenStableSegmentFullyCoversIt() throws Exception {
        PsRecordingTimeline timeline = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000),
                anchor(BASE.plusSeconds(15), 1_395_000), anchor(BASE.plusSeconds(20), 1_845_000)));
        assertEquals(16.5, timeline.plan(BASE.plusSeconds(16), BASE.plusSeconds(19)).startPtsSeconds(), .000001);
    }

    @Test
    void rejectsMissingCoverageSparseAnchorsAndAccumulatedClockDrift() throws Exception {
        PsRecordingTimeline sparse = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(11), 1_890_000)));
        assertThrows(IOException.class, () -> sparse.plan(BASE.plusSeconds(2), BASE.plusSeconds(8)));
        PsRecordingTimeline shortStream = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_350_000)));
        assertThrows(IOException.class, () -> shortStream.plan(BASE.plusSeconds(1), BASE.plusSeconds(6)));
        PsRecordingTimeline drift = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 1_353_600),
                anchor(BASE.plusSeconds(10), 1_807_200), anchor(BASE.plusSeconds(15), 2_260_800)));
        assertThrows(IOException.class, () -> drift.plan(BASE.plusSeconds(1), BASE.plusSeconds(14)));
    }

    @Test
    void ignoresHkBytesInsideCompressedPayloadAndRequiresActualPsmDescriptor() throws Exception {
        byte[] payload = join(new byte[]{0, 0, 1, 0x65}, descriptor(BASE));
        PsRecordingTimeline timeline = parse(join(pack(), pes(0xe0, 900_000, payload), anchor(BASE.plusSeconds(5), 1_350_000)));
        assertEquals(1, timeline.anchors().size());
        assertThrows(IOException.class, () -> timeline.plan(BASE.plusSeconds(5), BASE.plusSeconds(6)));
    }

    @Test
    void validatesPsmAndPesStructureAndPtsMarkers() {
        byte[] truncated = anchor(BASE, 900_000);
        assertThrows(IOException.class, () -> parse(java.util.Arrays.copyOf(truncated, truncated.length - 1)));
        byte[] invalidLength = psm(BASE);
        invalidLength[9] = 100;
        assertThrows(IOException.class, () -> parse(join(pack(), invalidLength, pes(0xe0, 900_000, new byte[]{1}))));
        byte[] invalidPts = pes(0xe0, 900_000, new byte[]{1});
        invalidPts[9] &= (byte) 0xfe;
        assertThrows(IOException.class, () -> parse(join(pack(), psm(BASE), invalidPts)));
        byte[] noPts = new byte[]{0, 0, 1, (byte) 0xe0, 0, 4, (byte) 0x80, 0, 0, 1};
        assertThrows(IOException.class, () -> parse(join(pack(), psm(BASE), noPts)));
    }

    @Test
    void rejectsPtsResetWrapAndMultipleVideoStreams() throws Exception {
        assertThrows(IOException.class, () -> parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(5), 450_000))));
        assertThrows(IOException.class, () -> parse(join(anchor(BASE, (1L << 33) - 90_000), anchor(BASE.plusSeconds(2), 90_000))));
        assertThrows(IOException.class, () -> parse(join(anchor(BASE, 900_000), pes(0xe1, 910_000, new byte[]{1}))));
    }

    @Test
    void acceptsRepeatedPtsOnContinuationPesButNotRepeatedTimestampAnchor() throws Exception {
        PsRecordingTimeline timeline = parse(join(anchor(BASE, 900_000), pes(0xe0, 900_000, new byte[]{1}),
                anchor(BASE.plusSeconds(5), 1_350_000)));
        assertEquals(2, timeline.anchors().size());
        assertThrows(IOException.class, () -> parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(1), 900_000))));
    }

    @Test
    void acceptsFragmentedInputAndAllTenMillisecondBits() throws Exception {
        LocalDateTime time = BASE.withNano(300_000_000);
        byte[] data = join(anchor(time, 900_000), anchor(time.plusSeconds(5), 1_350_000));
        ByteArrayInputStream input = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 1)); }
        };
        assertEquals(time, PsRecordingTimeline.parse(input).anchors().getFirst().wallTime());
    }

    @Test
    void rejectsAmbiguousOverlappingWallClockSegments() throws Exception {
        PsRecordingTimeline timeline = parse(join(anchor(BASE, 900_000), anchor(BASE.plusSeconds(10), 1_800_000),
                anchor(BASE, 2_700_000), anchor(BASE.plusSeconds(10), 3_600_000)));
        assertThrows(IOException.class, () -> timeline.plan(BASE.plusSeconds(2), BASE.plusSeconds(8)));
    }

    private static PsRecordingTimeline parse(byte[] bytes) throws IOException {
        return PsRecordingTimeline.parse(new ByteArrayInputStream(bytes));
    }

    static byte[] anchor(LocalDateTime time, long pts) {
        return join(pack(), psm(time), pes(0xe0, pts, new byte[]{0, 0, 1, 0x65, 1, 2, 3}));
    }

    static byte[] pack() {
        return new byte[]{0, 0, 1, (byte) 0xba, 0x44, 0, 4, 0, 4, 1, 0, 0, 3, (byte) 0xf8};
    }

    static byte[] psm(LocalDateTime time) {
        byte[] desc = descriptor(time);
        byte[] data = new byte[16 + desc.length + 4];
        data[2] = 1; data[3] = (byte) 0xbc;
        put16(data, 4, data.length - 6); data[6] = (byte) 0xe0; data[7] = (byte) 0xff;
        put16(data, 8, desc.length); System.arraycopy(desc, 0, data, 10, desc.length);
        put16(data, 10 + desc.length, 4); data[12 + desc.length] = 0x1b; data[13 + desc.length] = (byte) 0xe0;
        return data;
    }

    static byte[] descriptor(LocalDateTime t) {
        byte[] d = new byte[16];
        int ms = t.getNano() / 1_000_000;
        d[0] = 0x40; d[1] = 0x0e; d[2] = 0x48; d[3] = 0x4b;
        d[6] = (byte) (t.getYear() - 2000); d[7] = (byte) ((t.getMonthValue() << 4) | (t.getDayOfMonth() >> 1));
        d[8] = (byte) ((t.getDayOfMonth() << 7) | (t.getHour() << 2) | (t.getMinute() >> 4));
        d[9] = (byte) ((t.getMinute() << 4) | (t.getSecond() >> 2));
        d[10] = (byte) ((t.getSecond() << 6) | 0x20 | (ms >> 5)); d[11] = (byte) (ms << 3);
        d[13] = d[14] = d[15] = (byte) 0xff;
        return d;
    }

    static byte[] pes(int streamId, long pts, byte[] payload) {
        byte[] d = new byte[14 + payload.length];
        d[2] = 1; d[3] = (byte) streamId; put16(d, 4, d.length - 6);
        d[6] = (byte) 0x80; d[7] = (byte) 0x80; d[8] = 5;
        d[9] = (byte) (0x21 | ((pts >>> 29) & 0x0e)); d[10] = (byte) (pts >>> 22);
        d[11] = (byte) (((pts >>> 14) & 0xfe) | 1); d[12] = (byte) (pts >>> 7);
        d[13] = (byte) (((pts << 1) & 0xfe) | 1); System.arraycopy(payload, 0, d, 14, payload.length);
        return d;
    }

    static byte[] pesDts(int streamId, long pts, long dts, byte[] payload) {
        byte[] base = pes(streamId, pts, payload);
        byte[] result = new byte[base.length + 5];
        System.arraycopy(base, 0, result, 0, 14);
        put16(result, 4, result.length - 6);
        result[7] = (byte) 0xc0; result[8] = 10; result[9] |= 0x10;
        byte[] decode = pes(streamId, dts, new byte[]{1});
        System.arraycopy(decode, 9, result, 14, 5); result[14] = (byte) ((result[14] & 0x0f) | 0x10);
        System.arraycopy(payload, 0, result, 19, payload.length);
        return result;
    }

    /** 与实录一致的私有记录头和长度，内容为合成零值，不含实录载荷。 */
    static byte[] hikPrivatePes(int type, long pts) {
        int words = type == 2 ? 23 : 22;
        byte[] payload = new byte[4 + words * 4];
        put16(payload, 0, type); put16(payload, 2, words);
        byte[] base = pes(0xbd, pts, payload);
        byte[] result = new byte[base.length + 2];
        System.arraycopy(base, 0, result, 0, 14);
        put16(result, 4, result.length - 6);
        result[6] = (byte) 0x8c; result[8] = 7;
        result[14] = (byte) 0xff; result[15] = (byte) 0xf8;
        System.arraycopy(payload, 0, result, 16, payload.length);
        return result;
    }

    private static byte[] decodeAnchor(LocalDateTime time, long pts, long dts) {
        return join(pack(), psm(time), pesDts(0xe0, pts, dts, new byte[]{0, 0, 1, 0x65, 1}));
    }

    private static void put16(byte[] data, int offset, int value) { data[offset] = (byte) (value >> 8); data[offset + 1] = (byte) value; }
    static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
