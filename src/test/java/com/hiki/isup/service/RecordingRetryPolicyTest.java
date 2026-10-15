package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class RecordingRetryPolicyTest {
    @TempDir Path directory;
    AtomicLong now = new AtomicLong(1000);

    private RecordingRetryPolicy policy(int capacity) throws IOException {
        return new RecordingRetryPolicy(directory.resolve("retry.properties"), 3, capacity,
                100, 400, 10000, now::get);
    }

    @Test
    void retriesBackOffAndStopAfterThreeAttemptsAcrossRestarts() throws Exception {
        RecordingRetryPolicy first = policy(10);
        assertTrue(first.beginAttempt("row", "input"));
        first.failed("row", false);
        assertFalse(policy(10).beginAttempt("row", "input"));
        now.addAndGet(100);
        assertTrue(policy(10).beginAttempt("row", "input"));
        now.addAndGet(199);
        assertFalse(policy(10).beginAttempt("row", "input"));
        now.incrementAndGet();
        assertTrue(policy(10).beginAttempt("row", "input"));
        now.addAndGet(100000);
        assertFalse(policy(10).beginAttempt("row", "input"));
    }

    @Test
    void permanentFailuresStayQuarantinedUntilInputsChange() throws Exception {
        RecordingRetryPolicy first = policy(10);
        assertTrue(first.beginAttempt("row", "old"));
        first.failed("row", true);
        now.addAndGet(100000);
        assertFalse(policy(10).canAttempt("row", "old"));
        assertTrue(policy(10).beginAttempt("row", "corrected"));
    }

    @Test
    void fullLedgerFailsClosedAndSuccessReleasesCapacity() throws Exception {
        RecordingRetryPolicy first = policy(1);
        assertTrue(first.beginAttempt("one", "input"));
        assertThrows(IOException.class, () -> first.beginAttempt("two", "input"));
        first.succeeded("one");
        assertTrue(first.beginAttempt("two", "input"));
    }

    @Test
    void expiredEntriesRemainBlockedAfterRestart() throws Exception {
        assertTrue(policy(10).beginAttempt("row", "input"));
        now.addAndGet(10001);
        assertFalse(policy(10).beginAttempt("row", "input"));
    }

    @Test
    void transientBackoffStartsWhenSlowAttemptActuallyFails() throws Exception {
        RecordingRetryPolicy first = policy(10);
        assertTrue(first.beginAttempt("row", "input"));
        now.addAndGet(1000);
        first.failed("row", false);
        assertFalse(policy(10).canAttempt("row", "input"));
        now.addAndGet(100);
        assertTrue(policy(10).canAttempt("row", "input"));
    }

    @Test
    void corruptLedgerFailsClosedInsteadOfResettingAttempts() throws Exception {
        Files.writeString(directory.resolve("retry.properties"), "row=broken");
        assertThrows(IOException.class, () -> policy(10));
    }

    @Test
    void emptyOrCommentOnlyLedgerCannotResetAttempts() throws Exception {
        Files.writeString(directory.resolve("retry.properties"), "");
        assertThrows(IOException.class, () -> policy(10));
        Files.writeString(directory.resolve("retry.properties"), "# truncated\n");
        assertThrows(IOException.class, () -> policy(10));
    }

    @Test
    void missingEntryFromOtherwiseValidPropertiesIsRejected() throws Exception {
        RecordingRetryPolicy first = policy(10);
        assertTrue(first.beginAttempt("one", "input"));
        assertTrue(first.beginAttempt("two", "input"));
        Path file = directory.resolve("retry.properties");
        String text = Files.readString(file);
        text = text.lines().filter(line -> !line.startsWith("b25l=")).collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(file, text);
        assertThrows(IOException.class, () -> policy(10));
    }

    @Test
    void quarantinedRowsDoNotConsumeActiveCapacityAfterRestart() throws Exception {
        RecordingRetryPolicy first = policy(1);
        for (int i = 0; i < 8; i++) {
            assertTrue(first.beginAttempt("bad-" + i, "input"));
            first.failed("bad-" + i, true);
        }
        RecordingRetryPolicy restarted = policy(1);
        for (int i = 0; i < 8; i++) assertFalse(restarted.canAttempt("bad-" + i, "input"));
        assertTrue(restarted.beginAttempt("new-good", "input"));
    }

    @Test
    void expiredRowsReleaseActiveCapacityWithoutRevivingOldWork() throws Exception {
        RecordingRetryPolicy first = policy(1);
        assertTrue(first.beginAttempt("expired", "input"));
        now.addAndGet(10001);
        assertTrue(first.beginAttempt("new-good", "input"));
        assertFalse(policy(1).canAttempt("expired", "input"));
    }

    @Test
    void tenThousandLegacyQuarantinesMigrateWithoutBlockingOneActiveSlot() throws Exception {
        Properties legacy = new Properties();
        for (int i = 0; i < 10000; i++) legacy.setProperty(encoded("bad-" + i), encoded("input") + ",1,1000,1100,true");
        writeLegacy(legacy);
        RecordingRetryPolicy migrated = policy(1);
        assertEquals(10000, migrated.stats().quarantined());
        assertEquals(0, migrated.stats().active());
        assertTrue(migrated.beginAttempt("good", "input"));
        RecordingRetryPolicy restarted = policy(1);
        assertFalse(restarted.canAttempt("bad-1234", "input"));
        assertEquals(1, restarted.stats().active());
        assertTrue(restarted.stats().atCapacity());
    }

    @Test
    void missingOrCorruptArchiveCannotReviveOldTask() throws Exception {
        RecordingRetryPolicy first = policy(2);
        first.beginAttempt("row", "input");
        first.failed("row", true, "时间轴回退");
        Path shard = archiveFiles().getFirst();
        byte[] original = Files.readAllBytes(shard);
        Files.delete(shard);
        assertThrows(IOException.class, () -> first.canAttempt("row", "input"));
        assertThrows(IOException.class, () -> policy(2));
        Files.write(shard, original);
        Files.writeString(shard, "# truncated\n");
        assertThrows(IOException.class, () -> first.canAttempt("row", "input"));
        assertThrows(IOException.class, () -> policy(2));
    }

    @Test
    void missingMainLedgerCannotResetArchive() throws Exception {
        RecordingRetryPolicy first = policy(2);
        first.beginAttempt("row", "input");
        first.failed("row", true);
        Files.delete(directory.resolve("retry.properties"));
        assertThrows(IOException.class, () -> policy(2));
    }

    @Test
    void recoveryRequiresKnownQuarantineExactVersionFingerprintAndReason() throws Exception {
        RecordingRetryPolicy first = policy(2);
        first.beginAttempt("row", "input");
        assertThrows(IllegalArgumentException.class, () -> first.release("row", "any", "input", "已核实"));
        first.failed("row", true, "时间轴异常");
        RecordingRetryPolicy.Snapshot snapshot = first.inspect("row");
        assertTrue(snapshot.quarantined());
        assertTrue(snapshot.reason().contains("时间轴异常"));
        assertThrows(IllegalArgumentException.class, () -> first.release("unknown", snapshot.revision(), "input", "已核实"));
        assertThrows(IllegalArgumentException.class, () -> first.release("row", "stale", "input", "已核实"));
        assertThrows(IllegalArgumentException.class, () -> first.release("row", snapshot.revision(), "changed", "已核实"));
        assertThrows(IllegalArgumentException.class, () -> first.release("row", snapshot.revision(), "input", " "));
        assertNull(first.inspect("unknown"));
        assertFalse(first.canAttempt("row", "input"));
        assertEquals(0, first.inspect("row").recoveryAudit().size());
    }

    @Test
    void recoverySurvivesRestartGrantsOnlyOneAttemptAndRetainsAuditAfterSuccess() throws Exception {
        RecordingRetryPolicy first = policy(1);
        first.beginAttempt("row", "input");
        first.failed("row", true, "设备此前时间异常");
        RecordingRetryPolicy.Snapshot original = first.inspect("row");
        first.release("row", original.revision(), original.fingerprint(), "已修正设备并核对录像");
        assertEquals(1, policy(1).stats().pendingReleases());
        RecordingRetryPolicy restarted = policy(1);
        assertTrue(restarted.beginAttempt("row", "input"));
        restarted.failed("row", false, "仍未完成");
        now.addAndGet(1000);
        restarted = policy(1);
        assertFalse(restarted.canAttempt("row", "input"));
        assertEquals(2, restarted.inspect("row").totalAttempts());
        assertEquals(1, restarted.inspect("row").recoveryAudit().size());
        RecordingRetryPolicy.Snapshot failed = restarted.inspect("row");
        restarted.release("row", failed.revision(), failed.fingerprint(), "再次修正设备后人工确认");
        assertTrue(restarted.beginAttempt("row", "input"));
        restarted.succeeded("row");
        RecordingRetryPolicy.Snapshot succeeded = policy(1).inspect("row");
        assertFalse(succeeded.quarantined());
        assertEquals(3, succeeded.totalAttempts());
        assertEquals(2, succeeded.recoveryAudit().size());
        assertEquals("已修正设备并核对录像", succeeded.recoveryAudit().getFirst().reason());
        assertEquals(0, policy(1).stats().quarantined());
    }

    @Test
    void concurrentRecoveryCasAllowsOnlyOneDurableGrant() throws Exception {
        RecordingRetryPolicy first = policy(2);
        first.beginAttempt("row", "input");
        first.failed("row", true);
        RecordingRetryPolicy.Snapshot before = first.inspect("row");
        java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                try { first.release("row", before.revision(), before.fingerprint(), "已核实"); granted.incrementAndGet(); }
                catch (IllegalArgumentException expectedConflict) { }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }));
            for (var future : futures) future.get();
        }
        assertEquals(1, granted.get());
        assertEquals(1, policy(2).inspect("row").recoveryAudit().size());
    }

    @Test
    void orphanSnapshotsAreCleanedAndOldSnapshotsDoNotAccumulate() throws Exception {
        RecordingRetryPolicy first = policy(1);
        first.beginAttempt("row", "input");
        first.failed("row", true);
        Path current = archiveFiles().getFirst();
        Path orphan = current.resolveSibling("0".repeat(64) + ".properties");
        Files.copy(current, orphan);
        RecordingRetryPolicy restarted = policy(1);
        assertFalse(Files.exists(orphan));
        for (int i = 0; i < 4; i++) {
            var before = restarted.inspect("row");
            restarted.release("row", before.revision(), before.fingerprint(), "人工核查 " + i);
            assertTrue(restarted.beginAttempt("row", "input"));
            restarted.failed("row", true);
            assertEquals(1, archiveFiles().size());
        }
    }

    @Test
    void diagnosticsAreReadOnlyAndReportExpiredActiveCapacityAsAvailable() throws Exception {
        RecordingRetryPolicy first = policy(1);
        first.beginAttempt("expired", "input");
        now.addAndGet(10001);
        Path main = directory.resolve("retry.properties");
        byte[] before = Files.readAllBytes(main);
        var timestamp = java.nio.file.attribute.FileTime.fromMillis(1000);
        Files.setLastModifiedTime(main, timestamp);
        RecordingRetryPolicy.Stats stats = first.stats();
        assertEquals(0, stats.active());
        assertEquals(1, stats.quarantined());
        assertFalse(stats.atCapacity());
        assertTrue(first.inspect("expired").quarantined());
        assertArrayEquals(before, Files.readAllBytes(main));
        assertEquals(timestamp, Files.getLastModifiedTime(main));
    }

    @Test
    void oneTimeRecoveryRemainsReadableWhenSystemClockMovedBackwards() throws Exception {
        RecordingRetryPolicy first = policy(1);
        first.beginAttempt("row", "input");
        first.failed("row", true);
        var before = first.inspect("row");
        now.set(1);
        first.release("row", before.revision(), before.fingerprint(), "已核实设备校时");
        assertTrue(first.beginAttempt("row", "input"));
        RecordingRetryPolicy restarted = policy(1);
        assertFalse(restarted.canAttempt("row", "input"));
        assertEquals(2, restarted.inspect("row").totalAttempts());
    }

    @Test
    void diagnosticsUseCachedSummaryWithoutReadingHistoricalShard() throws Exception {
        RecordingRetryPolicy first = policy(2);
        first.beginAttempt("row", "input");
        first.failed("row", true, "时间轴异常");
        RecordingRetryPolicy.Stats before = first.stats();
        assertEquals(Files.size(directory.resolve("retry.properties"))
                + Files.size(archiveFiles().getFirst()), before.storageBytes());
        assertTrue(before.storageCached());
        assertEquals(now.get(), before.storageSampledAt());
        now.addAndGet(1000);
        Files.delete(archiveFiles().getFirst());
        RecordingRetryPolicy.Stats cached = first.stats();
        assertEquals(before.storageBytes(), cached.storageBytes());
        assertEquals(before.storageSampledAt(), cached.storageSampledAt());
        assertEquals(1, cached.quarantined());
        assertThrows(IOException.class, () -> first.inspect("row"));
        assertThrows(IOException.class, () -> first.beginAttempt("row", "input"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "missing,can", "missing,begin", "missing,failed", "missing,release", "missing,succeeded",
            "corrupt,can", "corrupt,begin", "corrupt,failed", "corrupt,release", "corrupt,succeeded"
    })
    void damagedMainLedgerWhileRunningFailsClosedWithoutReplacingEvidence(String damage, String operation) throws Exception {
        RecordingRetryPolicy first = policy(2);
        first.beginAttempt("row", "input");
        first.failed("row", true);
        var before = first.inspect("row");
        Path main = directory.resolve("retry.properties");
        if (damage.equals("missing")) Files.delete(main);
        else Files.writeString(main, "# broken main snapshot\n");
        org.junit.jupiter.api.function.Executable action = switch (operation) {
            case "can" -> () -> first.canAttempt("new", "input");
            case "begin" -> () -> first.beginAttempt("new", "input");
            case "failed" -> () -> first.failed("row", true);
            case "release" -> () -> first.release("row", before.revision(), before.fingerprint(), "核查完成");
            case "succeeded" -> () -> first.succeeded("row");
            default -> throw new IllegalArgumentException(operation);
        };
        assertThrows(IOException.class, action);
        if (damage.equals("missing")) assertFalse(Files.exists(main));
        else assertEquals("# broken main snapshot\n", Files.readString(main));
    }

    @Test
    void replacingMainLedgerWithValidOlderSnapshotDoesNotResetInMemoryAttempts() throws Exception {
        RecordingRetryPolicy first = policy(2);
        first.beginAttempt("row", "input");
        Path main = directory.resolve("retry.properties");
        byte[] older = Files.readAllBytes(main);
        now.addAndGet(100);
        first.beginAttempt("row", "input");
        Files.write(main, older);
        assertThrows(IOException.class, () -> first.beginAttempt("new", "input"));
        assertArrayEquals(older, Files.readAllBytes(main));
    }

    @Test
    void pendingRecoveryChecksActiveCapacityBeforeAdmission() throws Exception {
        RecordingRetryPolicy first = fullPolicyWithPendingRecovery();
        assertThrows(IOException.class, () -> first.canAttempt("isolated", "input"));
        assertEquals(1, first.stats().active());
        assertEquals(1, first.stats().pendingReleases());
    }

    @Test
    void failedAdmissionKeepsUnconsumedRecoveryArchivedAcrossRestart() throws Exception {
        RecordingRetryPolicy first = fullPolicyWithPendingRecovery();
        assertThrows(IOException.class, () -> first.beginAttempt("isolated", "input"));
        first.failed("isolated", false, "活动容量暂满");
        RecordingRetryPolicy restarted = policy(1);
        assertEquals(1, restarted.stats().active());
        assertEquals(1, restarted.stats().pendingReleases());
        assertEquals(1, restarted.inspect("isolated").totalAttempts());
        restarted.succeeded("running");
        assertTrue(restarted.beginAttempt("isolated", "input"));
        assertEquals(0, restarted.stats().pendingReleases());
    }

    @Test
    void shardDirectoryAndMainDirectoryAreSyncedBeforeOldShardDeletion() throws Exception {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        var oldShard = new java.util.concurrent.atomic.AtomicReference<Path>();
        List<String> synced = new ArrayList<>();
        RecordingRetryPolicy first = new RecordingRetryPolicy(directory.resolve("retry.properties"), 3, 1,
                100, 400, 10000, now::get, path -> {
                    if (!armed.get()) return;
                    if (path.equals(oldShard.get().getParent())) {
                        assertTrue(Files.exists(oldShard.get()));
                        synced.add("shard");
                    }
                    if (path.equals(directory)) {
                        assertTrue(Files.exists(oldShard.get()));
                        synced.add("main");
                    }
                });
        first.beginAttempt("row", "input");
        first.failed("row", true);
        oldShard.set(archiveFiles().getFirst());
        var before = first.inspect("row");
        armed.set(true);
        first.release("row", before.revision(), before.fingerprint(), "已核实");
        assertEquals(List.of("shard", "main"), synced);
        assertFalse(Files.exists(oldShard.get()));
    }

    @Test
    void directorySyncFailureCannotReportSuccessfulRecoveryOrDeleteOldShard() throws Exception {
        var armed = new java.util.concurrent.atomic.AtomicBoolean();
        RecordingRetryPolicy first = new RecordingRetryPolicy(directory.resolve("retry.properties"), 3, 1,
                100, 400, 10000, now::get, path -> {
                    if (armed.get() && path.equals(directory)) throw new IOException("directory fsync failed");
                });
        first.beginAttempt("row", "input");
        first.failed("row", true);
        Path oldShard = archiveFiles().getFirst();
        byte[] oldBytes = Files.readAllBytes(oldShard);
        var before = first.inspect("row");
        armed.set(true);
        assertThrows(IOException.class, () -> first.release("row", before.revision(), before.fingerprint(), "已核实"));
        assertArrayEquals(oldBytes, Files.readAllBytes(oldShard));
        armed.set(false);
        assertThrows(IOException.class, () -> first.beginAttempt("new", "input"));
        assertThrows(IOException.class, () -> first.failed("row", true));
        assertArrayEquals(oldBytes, Files.readAllBytes(oldShard));
    }

    @Test
    void restartConfirmsDirectoryDurabilityBeforeReclaimingOldSnapshots() throws Exception {
        RecordingRetryPolicy first = policy(1);
        first.beginAttempt("row", "input");
        first.failed("row", true);
        Path current = archiveFiles().getFirst();
        Path old = current.resolveSibling("0".repeat(64) + ".properties");
        Files.copy(current, old);
        var confirmed = new java.util.concurrent.atomic.AtomicBoolean();
        new RecordingRetryPolicy(directory.resolve("retry.properties"), 3, 1,
                100, 400, 10000, now::get, path -> {
                    if (path.equals(directory)) {
                        assertTrue(Files.exists(old));
                        confirmed.set(true);
                    }
                });
        assertTrue(confirmed.get());
        assertFalse(Files.exists(old));
    }

    private RecordingRetryPolicy fullPolicyWithPendingRecovery() throws Exception {
        RecordingRetryPolicy first = policy(1);
        first.beginAttempt("isolated", "input");
        first.failed("isolated", true);
        var isolated = first.inspect("isolated");
        first.release("isolated", isolated.revision(), isolated.fingerprint(), "已核实");
        first.beginAttempt("running", "input");
        return first;
    }

    private List<Path> archiveFiles() throws IOException {
        try (var files = Files.walk(directory.resolve("retry.properties.archive"))) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private void writeLegacy(Properties legacy) throws Exception {
        StringBuilder canonical = new StringBuilder();
        legacy.stringPropertyNames().stream().sorted().forEach(key -> {
            String value = legacy.getProperty(key);
            canonical.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value);
        });
        legacy.setProperty("@checksum", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8))));
        legacy.setProperty("@format", "1");
        try (var out = Files.newOutputStream(directory.resolve("retry.properties"))) { legacy.store(out, "v3 fixture"); }
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
