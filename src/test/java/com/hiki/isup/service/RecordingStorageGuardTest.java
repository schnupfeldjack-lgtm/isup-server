package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RecordingStorageGuardTest {
    @TempDir Path root;

    @Test void countsAllSessionsAndAllIntermediateFormats() throws Exception {
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        Files.write(first.resolve("source.ps"), new byte[4]);
        Files.write(first.resolve("exact.mp4"), new byte[3]);
        Files.write(second.resolve("old.mp4"), new byte[4]);
        assertThrows(IOException.class, () -> RecordingStorageGuard.checkRoot(root, 10, 1, p -> 100));
        assertDoesNotThrow(() -> RecordingStorageGuard.checkRoot(root, 12, 1, p -> 100));
    }

    @Test void reserveIsCheckedEvenWithoutAnyRawWriter() {
        assertThrows(IOException.class, () -> RecordingStorageGuard.checkRoot(root, 100, 20, p -> 19));
        assertDoesNotThrow(() -> RecordingStorageGuard.checkRoot(root, 100, 20, p -> 20));
    }

    @Test void missingDownloadDirectoryIsNotCreatedByReadOnlyAdmissionCheck() {
        Path missing = root.resolve("download");
        assertDoesNotThrow(() -> RecordingStorageGuard.checkRoot(missing, 100, 20, p -> 100));
        assertFalse(Files.exists(missing));
    }

    @Test void cappedOutputIsRejectedEvenWhenMuxerExitedSuccessfully() throws Exception {
        Path session = Files.createDirectories(root.resolve("session"));
        Path output = Files.write(session.resolve("manual.mp4"), new byte[10]);
        assertThrows(IOException.class, () -> RecordingStorageGuard.checkOutput(output, 10));
        assertDoesNotThrow(() -> RecordingStorageGuard.checkOutput(output, 11));
    }
}
