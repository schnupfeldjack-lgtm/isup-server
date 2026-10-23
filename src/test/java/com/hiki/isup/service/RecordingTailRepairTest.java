package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.hiki.isup.service.PsRecordingTimelineTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordingTailRepairTest {
    @TempDir Path directory;

    @Test void rejectsSameSizeSourceMutationBeforeUsingCandidate() throws Exception {
        Path source = source();
        PsRecordingTimeline timeline = PsRecordingTimeline.parse(source);
        byte[] changed = Files.readAllBytes(source); changed[changed.length - 1] ^= 1; Files.write(source, changed);
        assertThrows(IOException.class, () -> RecordingTailRepair.copyAndVerify(source, null, timeline, 0, Long.MAX_VALUE));
        assertArrayEquals(changed, Files.readAllBytes(source), "Validation must not rewrite even an externally changed source");
    }

    @Test void rejectsGrowthAndShrinkage() throws Exception {
        Path source = source();
        PsRecordingTimeline timeline = PsRecordingTimeline.parse(source);
        Files.write(source, new byte[]{1}, java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> RecordingTailRepair.copyAndVerify(source, null, timeline, 0, Long.MAX_VALUE));
        Files.write(source, new byte[]{1});
        assertThrows(IOException.class, () -> RecordingTailRepair.copyAndVerify(source, null, timeline, 0, Long.MAX_VALUE));
    }

    @Test void sharedDeadlineAndInterruptPreventCreatingPrefix() throws Exception {
        Path source = source(), candidate = directory.resolve("candidate.ps");
        PsRecordingTimeline timeline = PsRecordingTimeline.parse(source);
        assertThrows(IOException.class, () -> RecordingTailRepair.copyAndVerify(source, candidate, timeline, 1, System.nanoTime() - 1));
        assertFalse(Files.exists(candidate));
        Thread.currentThread().interrupt();
        try { assertThrows(IOException.class, () -> RecordingTailRepair.copyAndVerify(source, candidate, timeline, 1, Long.MAX_VALUE)); }
        finally { Thread.interrupted(); }
        assertFalse(Files.exists(candidate));
    }

    private Path source() throws Exception {
        return Files.write(directory.resolve("source.ps"), join(PsTailRepairTest.anchors(),
                pes(0xe0, PsTailRepairTest.ORIGIN + 6 * 90_000, PsTailRepairTest.SLICE),
                PsTailRepairTest.partial(new byte[]{0x55})));
    }
}
