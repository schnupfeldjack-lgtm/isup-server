package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RecordingSourceCaptureTest {
    @TempDir Path directory;

    @Test void preservesTheExactStreamAndClosesBeforeUse() throws Exception {
        Path ps = directory.resolve("session/source.ps");
        try (var capture = new RecordingSourceCapture(ps, 8, 0)) {
            capture.write(new byte[]{0, 0, 1, (byte) 0xba}, 0, 4);
            capture.write(new byte[]{8, 9, 10}, 1, 2);
        }
        assertArrayEquals(new byte[]{0, 0, 1, (byte)0xba, 9, 10}, Files.readAllBytes(ps));
    }

    @Test void stopsAtLimitWithoutWritingPartialPacket() throws Exception {
        Path ps = directory.resolve("session/source.ps");
        try (var capture = new RecordingSourceCapture(ps, 4, 0)) {
            capture.write(new byte[]{1, 2, 3}, 0, 3);
            assertThrows(IOException.class, () -> capture.write(new byte[]{4, 5}, 0, 2));
        }
        assertEquals(3, Files.size(ps));
    }

    @Test void refusesToOverwriteAnExistingSource() throws Exception {
        Path ps = Files.write(Files.createDirectories(directory.resolve("session")).resolve("source.ps"), new byte[]{9});
        assertThrows(IOException.class, () -> new RecordingSourceCapture(ps, 1024, 0));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(ps));
    }

    @Test void checksDiskReserveBeforeCreatingSource() {
        Path ps = directory.resolve("session/source.ps");
        assertThrows(IOException.class, () -> new RecordingSourceCapture(ps, 1024, Long.MAX_VALUE));
        assertFalse(Files.exists(ps));
    }
}
