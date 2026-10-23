package com.hiki.isup.service;

import com.hiki.isup.model.request.OverlayTextRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OverlayRecoveryGateTest {
    private static final String CLEARED = """
            <VideoOverlay><TextOverlayList>
            <TextOverlay><id>1</id><enabled>true</enabled><displayText>快递单号：</displayText></TextOverlay>
            <TextOverlay><id>2</id><enabled>false</enabled><displayText></displayText></TextOverlay>
            </TextOverlayList></VideoOverlay>
            """;
    private final DeviceIsapiClient client = mock(DeviceIsapiClient.class);
    private final OverlayService service = new OverlayService(client);

    @AfterEach
    void close() { service.destroy(); }

    @Test
    void successfulRecoveryKeepsWritesClosed() {
        assertTrue(quiesce(() -> true));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.apply("DEVICE", 1, OverlayTextRequest.external("NEW")));
        assertEquals(503, error.getStatusCode().value());
        assertFalse(quiesce(() -> fail("already closed")));
        verifyNoInteractions(client);
    }

    @Test
    void declinedOrFailedRecoveryReopensWrites() {
        assertFalse(quiesce(() -> false));
        assertThrows(IllegalStateException.class,
                () -> quiesce(() -> { throw new IllegalStateException("readback failed"); }));
        when(client.get(anyString(), anyString())).thenReturn(CLEARED);
        service.apply("DEVICE", 1, OverlayTextRequest.external(""));
        verify(client, atLeastOnce()).get(anyString(), anyString());
        assertTrue(quiesce(() -> true));
    }

    @Test
    void inFlightApplyDeclinesImmediatelyWithoutInvokingAction() throws Exception {
        assertBusyWriteDeclines(false);
    }

    @Test
    void inFlightRestoreDeclinesImmediatelyWithoutInvokingAction() throws Exception {
        assertBusyWriteDeclines(true);
    }

    private void assertBusyWriteDeclines(boolean restore) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.get(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return CLEARED;
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var write = pool.submit(() -> {
                if (restore) ReflectionTestUtils.invokeMethod(service, "restorePrefix", "DEVICE", 1, 1);
                else service.apply("DEVICE", 1, OverlayTextRequest.external(""));
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1),
                        () -> assertFalse(quiesce(() -> fail("write still active"))));
            } finally {
                release.countDown();
            }
            write.get(5, TimeUnit.SECONDS);
        }
        assertTrue(quiesce(() -> true));
    }

    @Test
    void pendingHidePreventsRecoveryAndCancellationRestoresEligibility() {
        ReflectionTestUtils.invokeMethod(service, "scheduleHideWaybill", "DEVICE", 1, 1, 60);
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertFalse(quiesce(() -> fail("hide pending"))));
        ReflectionTestUtils.invokeMethod(service, "cancelPending", "DEVICE", 1, 1);
        assertTrue(quiesce(() -> true));
    }

    @Test
    void actionHoldsExclusiveAdmissionWithoutBlockingConcurrentCallers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var recovery = pool.submit(() -> quiesce(() -> {
                entered.countDown();
                try { return release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    assertFalse(quiesce(() -> fail("recovery already active")));
                    ResponseStatusException error = assertThrows(ResponseStatusException.class,
                            () -> service.apply("DEVICE", 1, OverlayTextRequest.external("NEW")));
                    assertEquals(503, error.getStatusCode().value());
                    assertThrows(ResponseStatusException.class,
                            () -> ReflectionTestUtils.invokeMethod(service, "restorePrefix", "DEVICE", 1, 1));
                    assertThrows(ResponseStatusException.class,
                            () -> ReflectionTestUtils.invokeMethod(service, "scheduleHideWaybill", "DEVICE", 1, 1, 60));
                    assertThrows(ResponseStatusException.class,
                            () -> ReflectionTestUtils.invokeMethod(service, "cancelPending", "DEVICE", 1, 1));
                });
            } finally {
                release.countDown();
            }
            assertTrue(recovery.get(5, TimeUnit.SECONDS));
        }
        verifyNoInteractions(client);
    }

    @Test
    void completedOldHideCannotEraseReplacementPendingHide() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.get(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return CLEARED;
        });
        ReflectionTestUtils.invokeMethod(service, "scheduleHideWaybill", "DEVICE", 1, 1, 0);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ReflectionTestUtils.invokeMethod(service, "scheduleHideWaybill", "DEVICE", 1, 1, 60);
        } finally {
            release.countDown();
        }
        ScheduledExecutorService executor = (ScheduledExecutorService) ReflectionTestUtils.getField(service, "executor");
        assertNotNull(executor);
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        assertFalse(quiesce(() -> fail("replacement hide must survive old completion")));
        ReflectionTestUtils.invokeMethod(service, "cancelPending", "DEVICE", 1, 1);
        assertTrue(quiesce(() -> true));
    }

    @Test
    void clearedReadbackRequiresKnownStructureAndEveryPackingBlockCleared() {
        assertTrue(cleared(CLEARED));
        assertFalse(cleared(CLEARED.replace("快递单号：", "快递单号：NEW")));
        assertFalse(cleared(CLEARED.replace("<id>2</id><enabled>false", "<id>2</id><enabled>true")));
        assertFalse(cleared(CLEARED.replace("<displayText></displayText>", "<displayText>地址</displayText>")));
        assertFalse(cleared(CLEARED.replace("<id>2</id>", "<id>1</id>")));
        assertFalse(cleared("<Unknown>" + CLEARED + "</Unknown>"));
        assertFalse(cleared(CLEARED.replace("<TextOverlayList>", "<Unknown><TextOverlayList>")
                .replace("</TextOverlayList>", "</TextOverlayList></Unknown>")));
        assertFalse(cleared(CLEARED.replace("<id>2</id>", "<id>9</id>")));
        assertFalse(cleared("<VideoOverlay/>"));
        assertFalse(cleared("<VideoOverlay"));
        assertFalse(cleared(null));
    }

    private boolean quiesce(BooleanSupplier action) {
        return service.tryQuiesceForRecovery(action);
    }

    private static boolean cleared(String xml) {
        return OverlayService.packingOverlayCleared(xml);
    }
}
