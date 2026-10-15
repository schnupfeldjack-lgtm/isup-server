package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RecordingDeadlineTest {
    @Test
    void expiredBudgetCannotBeConvertedToUnlimitedZeroTimeout() {
        assertThrows(SocketTimeoutException.class, () -> RecordingDeadline.remainingMillis(System.nanoTime() - 1));
    }

    @Test
    void liveBudgetAlwaysProvidesPositiveTimeout() throws Exception {
        assertTrue(RecordingDeadline.remainingMillis(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)) > 0);
    }

    @Test
    void secondsBudgetIsMonotonicAndBounded() throws Exception {
        long before = System.nanoTime();
        long deadline = RecordingDeadline.afterSeconds(2);
        assertTrue(deadline - before >= TimeUnit.SECONDS.toNanos(2));
        assertTrue(RecordingDeadline.remainingMillis(deadline) <= 2000);
    }
}
