package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.hiki.isup.service.PsRecordingTimelineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PsRecordingDuplicateAccessUnitTest {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 15, 9, 19, 48, 641_000_000);
    private static final long PTS = 4_512_561_806L;
    @TempDir Path tempDir;

    @Test void exactRepeatedSixPesIdrKeepsOneAnchorAndContinuousCoverage() throws Exception {
        byte[] au = idr(243_610);
        var timeline = parse(join(group(BASE, PTS, au), group(BASE, PTS, au),
                anchor(BASE.plusSeconds(1), PTS + 90_000)));
        assertEquals(2, timeline.anchors().size());
        assertEquals(6, timeline.duplicateVideoPackets().size());
        assertEquals(PTS / 90_000d + .2,
                timeline.plan(BASE.plusNanos(200_000_000), BASE.plusNanos(800_000_000)).startPtsSeconds(), 1e-9);
    }

    @Test void exactRepeatedSevenPesWithUntimedContinuationRetainsAudioAndMetadata() throws Exception {
        byte[] au = separatedIdr();
        byte[] marker = join(hikPrivatePes(8, PTS), pes(0xc0, PTS, new byte[320]));
        byte[] first = untimedGroup(BASE, PTS, au, marker);
        byte[] second = untimedGroup(BASE, PTS, au, marker);
        byte[] tail = anchor(BASE.plusSeconds(1), PTS + 90_000);
        byte[] original = join(first, second, tail);
        Path source = tempDir.resolve("untimed-repeat.ps");
        Files.write(source, original);
        var timeline = PsRecordingTimeline.parse(source);
        assertEquals(2, timeline.anchors().size());
        assertEquals(7, timeline.duplicateVideoPackets().size());
        assertEquals(1, timeline.audioStreamCount());
        ByteArrayOutputStream retained = new ByteArrayOutputStream();
        retained.writeBytes(join(pack(), psm(BASE)));
        for (int i = 0; i < 7; i++) retained.writeBytes(marker);
        try (var copy = ExactDurationTrimmer.prepareDecodeSource(source, timeline, Long.MAX_VALUE)) {
            assertArrayEquals(join(first, retained.toByteArray(), tail), Files.readAllBytes(copy.path()));
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void untimedContinuationStillRequiresIdenticalPayloadAndSingleIdr() {
        byte[] au = separatedIdr();
        byte[] changed = au.clone();
        changed[changed.length - 100] ^= 1;
        byte[] tail = anchor(BASE.plusSeconds(1), PTS + 90_000);
        assertThrows(IOException.class, () -> parse(join(untimedGroup(BASE, PTS, au),
                untimedGroup(BASE, PTS, changed), tail)));
        byte[] nonIdr = au.clone();
        nonIdr[43] = 0x61;
        assertThrows(IOException.class, () -> parse(join(untimedGroup(BASE, PTS, nonIdr),
                untimedGroup(BASE, PTS, nonIdr), tail)));
        byte[] extraSlice = au.clone();
        System.arraycopy(new byte[]{0, 0, 0, 1, 0x65, (byte) 0x88, (byte) 0x80}, 0,
                extraSlice, extraSlice.length - 100, 7);
        assertThrows(IOException.class, () -> parse(join(untimedGroup(BASE, PTS, extraSlice),
                untimedGroup(BASE, PTS, extraSlice), tail)));
    }

    @Test void untimedContinuationCannotUseEofOrTruncationAsDuplicateClosure() {
        byte[] group = untimedGroup(BASE, PTS, separatedIdr());
        assertThrows(IOException.class, () -> parse(join(group, group)));
        assertThrows(IOException.class, () -> parse(join(group, Arrays.copyOf(group, group.length - 1))));
    }

    @Test void untimedContinuationCanCloseAtTheNextTimestampWithoutHk() throws Exception {
        byte[] group = untimedGroup(BASE, PTS, separatedIdr());
        var timeline = parse(join(group, group, pes(0xe0, PTS + 4500, new byte[]{0, 0, 1, 0x61, 0x55}),
                anchor(BASE.plusSeconds(1), PTS + 90_000)));
        assertEquals(7, timeline.duplicateVideoPackets().size());
        assertEquals(2, timeline.anchors().size());
    }

    @Test void untimedContinuationKeepsTheExistingByteAndPacketBounds() {
        byte[] group = untimedGroup(BASE, PTS, separatedIdr());
        ByteArrayOutputStream oversized = new ByteArrayOutputStream();
        oversized.writeBytes(join(group, group));
        for (int i = 0; i < 40; i++) oversized.writeBytes(untimedPes(0xe0, new byte[60_000]));
        IOException bytes = assertThrows(IOException.class, () -> parse(oversized.toByteArray()));
        assertTrue(bytes.getMessage().contains("超出有界"));
        ByteArrayOutputStream tooMany = new ByteArrayOutputStream();
        tooMany.writeBytes(join(group, group));
        for (int i = 0; i < 1024; i++) tooMany.writeBytes(untimedPes(0xe0, new byte[]{0x55}));
        IOException packets = assertThrows(IOException.class, () -> parse(tooMany.toByteArray()));
        assertTrue(packets.getMessage().contains("超出有界"));
    }

    @Test void truncatedUntimedFirstGroupDoesNotCreateDuplicateEvidence() throws Exception {
        byte[] group = untimedGroup(BASE, PTS, separatedIdr());
        var timeline = parse(Arrays.copyOf(group, group.length - 1));
        assertTrue(timeline.hasIncompleteTail());
        assertTrue(timeline.duplicateVideoPackets().isEmpty());
    }

    @Test void untimedContinuationCannotStartWithoutTimestampOrCrossParameterMap() {
        byte[] au = separatedIdr();
        byte[] group = untimedGroup(BASE, PTS, au);
        byte[] changedMap = group.clone();
        changedMap[pack().length + 6] ^= 1;
        byte[] tail = anchor(BASE.plusSeconds(1), PTS + 90_000);
        assertThrows(IOException.class, () -> parse(join(group, changedMap, tail)));
        assertThrows(IOException.class, () -> parse(join(pack(), psm(BASE),
                untimedPes(0xe0, Arrays.copyOfRange(au, 0, 31)))));
        assertThrows(IOException.class, () -> parse(join(group, pack(), psm(BASE),
                pes(0xe0, PTS, Arrays.copyOfRange(au, 0, 31)),
                untimedPes(0xe1, Arrays.copyOfRange(au, 31, 39)), tail)));
    }

    @Test void equalClocksWithDifferentPayloadRemainRejected() {
        byte[] changed = idr(243_610);
        changed[changed.length - 100] ^= 1;
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, idr(243_610)),
                group(BASE, PTS, changed), anchor(BASE.plusSeconds(1), PTS + 90_000))));
    }

    @Test void repeatedPayloadWithDifferentHkOrRollbackRemainsRejected() {
        byte[] au = idr(300);
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, au),
                group(BASE.plusNanos(1_000_000), PTS, au), anchor(BASE.plusSeconds(1), PTS + 90_000))));
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, au),
                group(BASE, PTS - 4500, au), anchor(BASE.plusSeconds(1), PTS + 90_000))));
    }

    @Test void repeatedNonIdrOrUnclosedDuplicateRemainsRejected() {
        byte[] au = idr(300);
        byte[] nonIdr = au.clone();
        nonIdr[17] = 0x61;
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, nonIdr),
                group(BASE, PTS, nonIdr), anchor(BASE.plusSeconds(1), PTS + 90_000))));
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, au), group(BASE, PTS, au))));
        byte[] duplicate = group(BASE, PTS, au);
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, au),
                Arrays.copyOf(duplicate, duplicate.length - 1))));
    }

    @Test void copyRemovesOnlyConfirmedVideoPesAndPreservesInterleavedAudioAndMetadata() throws Exception {
        byte[] au = idr(243_610);
        byte[] first = group(BASE, PTS, au);
        byte[] marker = join(hikPrivatePes(8, PTS), pes(0xc0, PTS, new byte[320]));
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        ByteArrayOutputStream retained = new ByteArrayOutputStream();
        second.writeBytes(join(pack(), psm(BASE)));
        retained.writeBytes(join(pack(), psm(BASE)));
        for (int at = 0; at < au.length; at += 45_000) {
            second.writeBytes(pes(0xe0, PTS, Arrays.copyOfRange(au, at, Math.min(au.length, at + 45_000))));
            second.writeBytes(marker);
            retained.writeBytes(marker);
        }
        byte[] tail = anchor(BASE.plusSeconds(1), PTS + 90_000);
        byte[] original = join(first, second.toByteArray(), tail);
        Path source = tempDir.resolve("repeat.ps");
        Files.write(source, original);
        var timeline = PsRecordingTimeline.parse(source);
        Path normalized;
        try (var copy = ExactDurationTrimmer.prepareDecodeSource(source, timeline, Long.MAX_VALUE)) {
            normalized = copy.path();
            assertNotEquals(source, normalized);
            assertArrayEquals(join(first, retained.toByteArray(), tail), Files.readAllBytes(normalized));
            assertArrayEquals(original, Files.readAllBytes(source));
        }
        assertFalse(Files.exists(normalized), "临时副本在结束后必须清理");
    }

    @Test void sourceMutationWithTheSameSizeInvalidatesDuplicateEvidence() throws Exception {
        byte[] original = join(group(BASE, PTS, idr(300)), group(BASE, PTS, idr(300)),
                anchor(BASE.plusSeconds(1), PTS + 90_000));
        Path source = tempDir.resolve("changed.ps");
        Files.write(source, original);
        var timeline = PsRecordingTimeline.parse(source);
        original[100] ^= 1;
        Files.write(source, original);
        assertThrows(IOException.class, () -> ExactDurationTrimmer.prepareDecodeSource(source, timeline, Long.MAX_VALUE));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count(), "失败的副本必须清理");
        }
    }

    @Test void oversizedOrChangedMapCannotQualifyForExactDuplicateRemoval() {
        byte[] oversized = idr(2 * 1024 * 1024 + 1);
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, oversized),
                group(BASE, PTS, oversized), anchor(BASE.plusSeconds(1), PTS + 90_000))));
        byte[] changedMap = group(BASE, PTS, idr(300));
        changedMap[pack().length + 6] ^= 1; // 合法 PSM version 变化仍不能复用旧证明。
        assertThrows(IOException.class, () -> parse(join(group(BASE, PTS, idr(300)),
                changedMap, anchor(BASE.plusSeconds(1), PTS + 90_000))));
    }

    @Test void repeatedIdrBeforeAnIncompleteLaterFrameKeepsBothProtections() throws Exception {
        byte[] later = anchor(BASE.plusSeconds(2), PTS + 180_000);
        byte[] original = join(group(BASE, PTS, idr(300)), group(BASE, PTS, idr(300)),
                anchor(BASE.plusSeconds(1), PTS + 90_000), Arrays.copyOf(later, later.length - 1));
        Path source = tempDir.resolve("repeat-and-tail.ps");
        Files.write(source, original);
        var timeline = PsRecordingTimeline.parse(source);
        assertTrue(timeline.hasIncompleteTail());
        assertEquals(1, timeline.duplicateVideoPackets().size());
        try (var copy = ExactDurationTrimmer.prepareDecodeSource(source, timeline, Long.MAX_VALUE)) {
            assertEquals(timeline.completePrefixBytes() - timeline.duplicateVideoPackets().stream()
                    .mapToLong(p -> p.end() - p.start()).sum(), Files.size(copy.path()));
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test void adjacentRepeatedCopiesAreVerifiedSeparately() throws Exception {
        byte[] group = group(BASE, PTS, idr(300));
        var timeline = parse(join(group, group, group, anchor(BASE.plusSeconds(1), PTS + 90_000)));
        assertEquals(2, timeline.anchors().size());
        assertEquals(2, timeline.duplicateVideoPackets().size());
    }

    @Test void copyHonorsSharedDeadlineAndCancellationBeforeCreatingOutput() throws Exception {
        Path source = tempDir.resolve("budget.ps");
        byte[] original = join(group(BASE, PTS, idr(300)), group(BASE, PTS, idr(300)),
                anchor(BASE.plusSeconds(1), PTS + 90_000));
        Files.write(source, original);
        var timeline = PsRecordingTimeline.parse(source);
        assertThrows(IOException.class, () -> ExactDurationTrimmer.prepareDecodeSource(source, timeline, System.nanoTime() - 1));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> ExactDurationTrimmer.prepareDecodeSource(source, timeline, Long.MAX_VALUE));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var files = Files.list(tempDir)) { assertEquals(1, files.count()); }
    }

    private static PsRecordingTimeline parse(byte[] bytes) throws IOException {
        return PsRecordingTimeline.parse(new ByteArrayInputStream(bytes));
    }

    static byte[] idr(int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) 0x55);
        byte[] prefix = {0, 0, 0, 1, 0x67, 0x42, 0x55,
                0, 0, 0, 1, 0x68, 0x55, 0x55,
                0, 0, 1, 0x65, (byte) 0x88, (byte) 0x80}; // ue(0), ue(7), ue(0)
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        return result;
    }

    static byte[] group(LocalDateTime time, long pts, byte[] au) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(pack());
        out.writeBytes(psm(time));
        for (int at = 0; at < au.length; at += 45_000) {
            out.writeBytes(pes(0xe0, pts, Arrays.copyOfRange(au, at, Math.min(au.length, at + 45_000))));
        }
        return out.toByteArray();
    }

    private static byte[] separatedIdr() {
        byte[] au = new byte[254_966];
        Arrays.fill(au, (byte) 0x55);
        System.arraycopy(new byte[]{0, 0, 0, 1, 0x67, 0x42}, 0, au, 0, 6);
        System.arraycopy(new byte[]{0, 0, 0, 1, 0x68}, 0, au, 31, 5);
        System.arraycopy(new byte[]{0, 0, 0, 1, 0x65, (byte) 0x88, (byte) 0x80}, 0, au, 39, 7);
        return au;
    }

    private static byte[] untimedGroup(LocalDateTime wall, long pts, byte[] au, byte[]... markers) {
        int[] lengths = {31, 8, 63_975, 63_976, 63_976, 54_776, 8_224};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(join(pack(), psm(wall)));
        int at = 0;
        for (int i = 0; i < lengths.length; i++) {
            byte[] payload = Arrays.copyOfRange(au, at, at + lengths[i]);
            out.writeBytes(i == 0 ? pes(0xe0, pts, payload) : untimedPes(0xe0, payload));
            for (byte[] marker : markers) out.writeBytes(marker);
            at += lengths[i];
        }
        assertEquals(au.length, at);
        return out.toByteArray();
    }

    static byte[] untimedPes(int id, byte[] payload) {
        int length = 3 + payload.length;
        return join(new byte[]{0, 0, 1, (byte) id, (byte) (length >>> 8), (byte) length,
                (byte) 0x80, 0, 0}, payload);
    }
}
