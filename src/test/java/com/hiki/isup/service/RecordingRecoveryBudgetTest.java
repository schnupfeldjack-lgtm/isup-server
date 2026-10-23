package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RecordingRecoveryBudgetTest {
    @TempDir Path directory;
    AtomicLong now = new AtomicLong(1000000000L);
    static final long HOUR = 3600000L;
    Path file() { return directory.resolve("nested/budget"); }
    RecordingRecoveryBudget budget() { return new RecordingRecoveryBudget(file(), now::get); }

    @Test void firstAvailableDoesNotCreateAnything() throws Exception {
        assertTrue(budget().available());
        assertFalse(Files.exists(file().getParent()));
        assertTrue(budget().reserve());
        assertFalse(budget().available());
    }
    @Test void cooldownIncludesExactHourBoundaryAndSurvivesNewInstance() throws Exception {
        assertTrue(budget().reserve());
        now.addAndGet(HOUR - 1);
        assertFalse(budget().reserve());
        now.incrementAndGet();
        assertTrue(budget().available());
        assertTrue(budget().reserve());
    }
    @Test void threeReservationsPerRollingDayAndExpiryAtExactBoundary() throws Exception {
        long start = now.get();
        for (int i = 0; i < 3; i++) {
            assertTrue(budget().reserve());
            now.addAndGet(HOUR);
        }
        assertFalse(budget().available());
        assertFalse(budget().reserve());
        now.set(start + 24 * HOUR);
        assertTrue(budget().reserve());
        now.addAndGet(HOUR);
        assertTrue(budget().reserve());
    }
    @Test void corruptionIsRejectedAndPreserved() throws Exception {
        assertTrue(budget().reserve());
        Files.writeString(file(), "broken state");
        assertThrows(IOException.class, () -> budget().available());
        assertThrows(IOException.class, () -> budget().reserve());
        assertEquals("broken state", Files.readString(file()));
    }
    @Test void futureRecordIsRejectedAndPreserved() throws Exception {
        assertTrue(budget().reserve());
        byte[] original = Files.readAllBytes(file());
        now.decrementAndGet();
        assertThrows(IOException.class, () -> budget().available());
        assertThrows(IOException.class, () -> budget().reserve());
        assertArrayEquals(original, Files.readAllBytes(file()));
    }
    @Test void concurrentInstancesCannotConsumeSameSlot() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> reserve = () -> { start.await(); return budget().reserve(); };
            Future<Boolean> a = workers.submit(reserve), b = workers.submit(reserve);
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertFalse(budget().available());
        } finally { workers.shutdownNow(); }
    }
}
