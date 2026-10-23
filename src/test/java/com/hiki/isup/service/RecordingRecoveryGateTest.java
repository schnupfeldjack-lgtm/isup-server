package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RecordingRecoveryGateTest {
    @Test
    void requiresQuietPeriodAndReopensWhenReadinessFails() throws Exception {
        AtomicLong now = new AtomicLong();
        RecordingRecoveryGate gate = new RecordingRecoveryGate(now::get);
        assertFalse(gate.tryQuiesce(30, () -> fail("too soon")));
        now.set(TimeUnit.SECONDS.toNanos(31));
        assertFalse(gate.tryQuiesce(30, () -> false));
        MockHttpServletResponse response = new MockHttpServletResponse();
        gate.doFilter(request(), response, (req, res) -> res.getWriter().write("accepted"));
        assertEquals("accepted", response.getContentAsString());
        assertFalse(gate.tryQuiesce(30, () -> fail("request reset quiet period")));
        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertTrue(gate.tryQuiesce(30, () -> true));
        response = new MockHttpServletResponse();
        gate.doFilter(request(), response, (req, res) -> fail("closed"));
        assertEquals(503, response.getStatus());
    }

    @Test
    void neverDrainsAnInflightRequestAndReopensOnException() throws Exception {
        AtomicLong now = new AtomicLong();
        RecordingRecoveryGate gate = new RecordingRecoveryGate(now::get);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread requestThread = new Thread(() -> {
            try {
                gate.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
                    entered.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            } catch (Exception e) { throw new AssertionError(e); }
        });
        requestThread.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            now.set(TimeUnit.SECONDS.toNanos(60));
            assertFalse(gate.tryQuiesce(30, () -> fail("in flight")));
        } finally {
            release.countDown();
            requestThread.join(3000);
        }
        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThrows(IllegalStateException.class,
                () -> gate.tryQuiesce(30, () -> { throw new IllegalStateException("probe failed"); }));
        assertTrue(gate.tryQuiesce(30, () -> true));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/devices/DLWH/overlay");
    }

    @Test
    void blockedDeviceProbeCannotHangNewBusinessRequests() throws Exception {
        AtomicLong now = new AtomicLong();
        RecordingRecoveryGate gate = new RecordingRecoveryGate(now::get);
        now.set(TimeUnit.SECONDS.toNanos(31));
        CountDownLatch probing = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread recovery = new Thread(() -> gate.tryQuiesce(30, () -> {
            probing.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return false;
        }));
        recovery.start();
        try {
            assertTrue(probing.await(2, TimeUnit.SECONDS));
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTimeoutPreemptively(java.time.Duration.ofMillis(500), () ->
                    gate.doFilter(request(), response, (req, res) -> fail("recovering")));
            assertEquals(503, response.getStatus());
        } finally {
            release.countDown();
            recovery.join(3000);
        }
    }

    @Test
    void requestDuringDeviceReadbackInvalidatesTheIdleSnapshot() throws Exception {
        AtomicLong now = new AtomicLong();
        RecordingRecoveryGate gate = new RecordingRecoveryGate(now::get);
        now.set(TimeUnit.SECONDS.toNanos(31));
        long version = gate.activityVersion();
        gate.doFilter(request(), new MockHttpServletResponse(), (req, res) -> { });
        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertFalse(gate.tryQuiesce(30, version, () -> fail("stale device readback")));
        assertTrue(gate.tryQuiesce(30, gate.activityVersion(), () -> true));
    }
}
