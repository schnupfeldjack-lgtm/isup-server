package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecordingFailureEvidenceTest {
    private static final long CAP = 64L * 1024 * 1024;
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 15, 10, 0);
    @TempDir Path temporary;

    @Test void retainsOriginalBytesWithBoundedMetadataAndHash() throws Exception {
        Path root = root();
        byte[] bytes = {0, 0, 1, (byte) 0xba, 9, 8, 7};
        Path source = source(root, bytes);
        Path saved = save(root, source, new IOException("原始 PS 截断" + "x".repeat(1400)));
        assertNotNull(saved);
        assertEquals(root.resolve("recording-failures/structure.ps"), saved);
        assertArrayEquals(bytes, Files.readAllBytes(saved));
        assertArrayEquals(bytes, Files.readAllBytes(source));
        Properties metadata = metadata(saved);
        assertEquals("test-record", metadata.getProperty("recordId"));
        assertEquals("2", metadata.getProperty("channel"));
        assertEquals(START.toString(), metadata.getProperty("start"));
        assertEquals(START.plusMinutes(1).toString(), metadata.getProperty("end"));
        assertEquals(Integer.toString(bytes.length), metadata.getProperty("sourceSize"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), metadata.getProperty("sha256"));
        assertFalse(metadata.getProperty("runtimeVersion").isBlank());
        assertTrue(metadata.getProperty("error").length() <= 1000);
        assertEquals(2, children(root.resolve("recording-failures")));
    }

    @Test void retainsFirstSampleInsteadOfGrowingOrReplacingSameCategory() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1, 2, 3});
        Path first = save(root, source, new IOException("原始 PS 截断"));
        assertNotNull(first);
        byte[] metadata = Files.readAllBytes(first.resolveSibling("structure.properties"));
        Files.write(source, new byte[]{9});
        for (int i = 0; i < 8; i++) assertNull(save(root, source, new IOException("PES EOF")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(first));
        assertArrayEquals(metadata, Files.readAllBytes(first.resolveSibling("structure.properties")));
        assertEquals(2, children(first.getParent()));
    }

    @Test void limitsFourCategoriesToFourFullSizeSlots() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1});
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "rw")) { file.setLength(CAP); }
        Throwable[] errors = {new IOException("PS EOF"), new IOException("wrapper", new IOException("视频 DTS 回退")),
                new RecordingCoverageException(START, START.plusMinutes(1), START, START.plusSeconds(59)),
                new IOException("录像解码失败")};
        String[] slots = {"structure", "timestamp", "coverage", "decode"};
        long total = 0;
        for (int i = 0; i < errors.length; i++) {
            Path result = save(root, source, errors[i]);
            assertNotNull(result);
            assertEquals(slots[i] + ".ps", result.getFileName().toString());
            total += Files.size(result);
            assertNull(save(root, source, errors[i]));
        }
        assertEquals(256L * 1024 * 1024, total);
        assertEquals(8, children(root.resolve("recording-failures")));
    }

    @Test void skipsOversizeSourceWithoutLeavingEvidence() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1});
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "rw")) { file.setLength(CAP + 1); }
        assertNull(save(root, source, new IOException("PS EOF")));
        assertNoTemporaryFiles(root);
        assertFalse(Files.exists(root.resolve("recording-failures/structure.ps")));
    }

    @Test void prunesExpiredSamplesEvenWithoutAnotherFailure() throws Exception {
        Path root = root();
        Path saved = save(root, source(root, new byte[]{1}), new IOException("PS EOF"));
        assertNotNull(saved);
        expire(saved);
        RecordingFailureEvidence.prune(root);
        assertEquals(0, children(root.resolve("recording-failures")));
    }

    @Test void replacesExpiredSampleAndCleansOrphanTemporaryFiles() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1});
        Path saved = save(root, source, new IOException("PS EOF"));
        assertNotNull(saved);
        expire(saved);
        Files.write(saved.resolveSibling("timestamp.ps.tmp"), new byte[]{4});
        Files.write(source, new byte[]{2, 3});
        assertEquals(saved, save(root, source, new IOException("PS EOF")));
        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(saved));
        assertEquals(2, children(saved.getParent()));
    }

    @Test void expiredDeadlineAndInterruptLeaveNoTemporaryFiles() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1});
        assertThrows(IOException.class, () -> RecordingFailureEvidence.preserve(root, source, "test-record", 2,
                START, START.plusMinutes(1), new IOException("PS EOF"), System.nanoTime() - 1));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> save(root, source, new IOException("PS EOF")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertNoTemporaryFiles(root);
    }

    @Test void interruptionDuringCopyRemovesPartialSampleAndMetadata() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1});
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "rw")) { file.setLength(CAP); }
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { save(root, source, new IOException("PS EOF")); }
            catch (Throwable error) { failed.set(error); }
        }, "failure-evidence-cancellation-test");
        worker.start();
        Path partial = root.resolve("recording-failures/structure.ps.tmp");
        long waitUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try {
            while (!Files.exists(partial) && worker.isAlive() && System.nanoTime() < waitUntil) Thread.sleep(1);
            assertTrue(Files.exists(partial), "必须在真实复制期间执行中断");
            worker.interrupt();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertInstanceOf(IOException.class, failed.get());
            assertEquals(0, children(root.resolve("recording-failures")));
            assertEquals(CAP, Files.size(source));
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test void contendedEvidenceLockHonorsShortDeadline() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1});
        ReentrantLock lock = evidenceLock();
        AtomicReference<Path> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        lock.lock();
        Thread worker = new Thread(() -> {
            try {
                result.set(RecordingFailureEvidence.preserve(root, source, "test-record", 2, START,
                        START.plusMinutes(1), new IOException("PS EOF"),
                        System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)));
            } catch (Throwable error) { failure.set(error); }
        }, "failure-evidence-lock-deadline-test");
        try {
            worker.start();
            worker.join(1000);
            assertFalse(worker.isAlive(), "等待采证锁不能超过本行短截止时间");
            assertNull(result.get(), "其他任务持锁时不得开始采证");
            assertTrue(failure.get() == null || failure.get() instanceof IOException);
            assertFalse(Files.exists(root.resolve("recording-failures/structure.ps")));
        } finally {
            lock.unlock();
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test void contendedPruneReturnsWithoutWaitingOrDeletingActiveEvidence() throws Exception {
        Path root = root();
        Path saved = save(root, source(root, new byte[]{1}), new IOException("PS EOF"));
        assertNotNull(saved);
        expire(saved);
        ReentrantLock lock = evidenceLock();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        lock.lock();
        Thread worker = new Thread(() -> {
            try { RecordingFailureEvidence.prune(root); }
            catch (Throwable error) { failure.set(error); }
        }, "failure-evidence-prune-contention-test");
        try {
            worker.start();
            worker.join(1000);
            assertFalse(worker.isAlive(), "每轮清理不能等待正在复制的样本");
            assertNull(failure.get());
            assertTrue(Files.exists(saved), "占锁期间不得删除其他任务的采证文件");
        } finally {
            lock.unlock();
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test void refusesMissingOutsideAndNonRegularSources() throws Exception {
        Path root = root();
        Path outside = Files.write(temporary.resolve("outside.ps"), new byte[]{1});
        assertThrows(IOException.class, () -> save(root, root.resolve("missing.ps"), new IOException("PS EOF")));
        assertThrows(IOException.class, () -> save(root, outside, new IOException("PS EOF")));
        assertThrows(IOException.class, () -> save(root, root, new IOException("PS EOF")));
        assertNoTemporaryFiles(root);
    }

    @Test void refusesDestinationDirectoryAndKeepsOriginalSample() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{3});
        Path blocked = Files.createDirectories(root.resolve("recording-failures/structure.ps"));
        assertThrows(IOException.class, () -> save(root, source, new IOException("PS EOF")));
        assertArrayEquals(new byte[]{3}, Files.readAllBytes(source));
        assertTrue(Files.isDirectory(blocked));
        assertNoTemporaryFiles(root);
    }

    @Test void refusesLinksAtSourceDirectoryRootAndEvidenceSlot() throws Exception {
        Path root = root();
        Path source = source(root, new byte[]{1});
        Path sourceLink = root.resolve("source-link.ps");
        try { Files.createSymbolicLink(sourceLink, source); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "当前 Windows 环境不允许创建符号链接: " + unavailable.getClass().getSimpleName());
        }
        assertThrows(IOException.class, () -> save(root, sourceLink, new IOException("PS EOF")));
        Files.delete(sourceLink);
        Path directoryLink = Files.createSymbolicLink(root.resolve("session-link"), source.getParent());
        assertThrows(IOException.class, () -> save(root, directoryLink.resolve("source.ps"), new IOException("PS EOF")));
        Files.delete(directoryLink);
        Path rootLink = Files.createSymbolicLink(temporary.resolve("root-link"), root);
        assertThrows(IOException.class, () -> save(rootLink, rootLink.resolve("download/session/source.ps"), new IOException("PS EOF")));
        Path evidence = Files.createDirectories(root.resolve("recording-failures"));
        Files.createSymbolicLink(evidence.resolve("structure.ps"), source);
        assertThrows(IOException.class, () -> save(root, source, new IOException("PS EOF")));
        assertThrows(IOException.class, () -> RecordingFailureEvidence.prune(root));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(source));
    }

    @Test void usesOwnerOnlyPermissionsWhenPosixIsAvailable() throws Exception {
        Path root = root();
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path saved = save(root, source(root, new byte[]{1}), new IOException("PS EOF"));
        assertNotNull(saved);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(saved.getParent()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(saved));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(saved.resolveSibling("structure.properties")));
    }

    private Path root() throws IOException { return Files.createDirectory(temporary.resolve("media")); }
    private ReentrantLock evidenceLock() throws ReflectiveOperationException {
        var field = RecordingFailureEvidence.class.getDeclaredField("LOCK");
        field.setAccessible(true);
        return (ReentrantLock) field.get(null);
    }
    private Path source(Path root, byte[] bytes) throws IOException {
        return Files.write(Files.createDirectories(root.resolve("download/session")).resolve("source.ps"), bytes);
    }
    private Path save(Path root, Path source, Throwable error) throws IOException {
        return RecordingFailureEvidence.preserve(root, source, "test-record", 2, START, START.plusMinutes(1), error, Long.MAX_VALUE);
    }
    private Properties metadata(Path saved) throws IOException {
        Properties values = new Properties();
        try (var input = Files.newInputStream(saved.resolveSibling(saved.getFileName().toString().replace(".ps", ".properties")))) { values.load(input); }
        return values;
    }
    private void expire(Path saved) throws IOException {
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 25L * 60 * 60 * 1000);
        Files.setLastModifiedTime(saved, old);
        Files.setLastModifiedTime(saved.resolveSibling(saved.getFileName().toString().replace(".ps", ".properties")), old);
    }
    private long children(Path path) throws IOException { try (var files = Files.list(path)) { return files.count(); } }
    private void assertNoTemporaryFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) { assertTrue(paths.noneMatch(p -> p.toString().endsWith(".tmp"))); }
    }
}
