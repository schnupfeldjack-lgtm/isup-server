package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.*;
import com.hiki.isup.sdk.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeCleanupRecoveryTest {
    @Test void completedViewerLeaseBlocksRecoveryAfterApiQuietPeriod() {
        try (Fixture f = new Fixture(true, null)) {
            f.awaitLocalStopped();
            MediaSession viewer = f.manager.create(SessionType.PLAYBACK, "viewer", 1);
            viewer.enableViewerLease();
            f.manager.stop(viewer, SessionStatus.COMPLETED, "source ended");
            assertEquals(SessionStatus.COMPLETED, viewer.getStatus());
            var clock = new java.util.concurrent.atomic.AtomicLong();
            RecordingRecoveryGate gate = new RecordingRecoveryGate(clock::get);
            clock.set(TimeUnit.SECONDS.toNanos(31));

            assertFalse(f.manager.canRecoverProcess(0));
            assertFalse(gate.tryQuiesce(30, () -> f.manager.prepareProcessRecovery(0)));
            assertSame(viewer, f.manager.heartbeat(viewer.getSessionId()), "rejected recovery must not freeze the viewer");
            assertEquals(1, f.manager.cleanupSnapshot().size());
            assertSame(f.callback, f.session.getDataCallback());
            assertFalse(f.manager.cleanupSnapshot().getFirst().cmsStopped());

            f.manager.stop(viewer, SessionStatus.STOPPED, "viewer closed");
            assertTrue(f.manager.canRecoverProcess(0));
            assertTrue(gate.tryQuiesce(30, () -> f.manager.prepareProcessRecovery(0)));
            f.prepared = true;
            assertFalse(viewer.renewViewerLease(System.currentTimeMillis()));
            assertSame(f.callback, f.session.getDataCallback());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final NativeSdkLoader loader = mock(NativeSdkLoader.class);
        final HCISUPCMS cms = mock(HCISUPCMS.class);
        final HCISUPStream stream = mock(HCISUPStream.class);
        final SessionManager manager;
        final MediaSession session;
        final Object callback = new Object();
        boolean prepared;

        Fixture(boolean streamStops, FfmpegProcess process) {
            when(loader.isLoaded()).thenReturn(true);
            when(loader.cms()).thenReturn(cms);
            when(loader.stream()).thenReturn(stream);
            when(stream.NET_ESTREAM_StopPlayBack(9)).thenReturn(streamStops);
            when(stream.NET_ESTREAM_GetLastError()).thenReturn(55);
            when(cms.NET_ECMS_GetLastError()).thenReturn(3715);
            manager = new SessionManager(loader, new IsupProperties());
            session = manager.create(SessionType.PLAYBACK, "dev", 1);
            session.setLUserId(7);
            session.setIsupSessionId(8);
            session.setLinkHandle(9);
            session.setDataCallback(callback);
            session.setFfmpegProcess(process);
            manager.stop(session, SessionStatus.FAILED, "exception");
            await().atMost(Duration.ofSeconds(3)).until(() -> {
                var snapshot = manager.cleanupSnapshot().getFirst();
                return snapshot.attempts() > 0 && !snapshot.nativeRunning()
                        && manager.resourceSnapshot().nativeStopActive() == 0;
            });
        }

        void awaitLocalStopped() {
            await().atMost(Duration.ofSeconds(3)).until(() -> manager.cleanupSnapshot().getFirst().localStopped()
                    && !manager.cleanupSnapshot().getFirst().localRunning());
        }

        @Override public void close() {
            if (prepared) return; // prepare already stopped watchdog; native references must remain held.
            when(stream.NET_ESTREAM_StopPlayBack(9)).thenReturn(true);
            when(cms.NET_ECMS_StopPlayBack(7, 8)).thenReturn(true);
            manager.sweep();
            manager.shutdown();
        }
    }

    @Test void preparesOnlyAfterAgeAndQuiescenceThenFreezesAdmissionAndRetainsCallbacks() {
        try (Fixture f = new Fixture(true, null)) {
            f.awaitLocalStopped();
            assertEquals(3715, f.manager.cleanupSnapshot().getFirst().lastCmsErrorCode());
            assertTrue(f.manager.canRecoverProcess(0));
            assertFalse(f.manager.prepareProcessRecovery(TimeUnit.DAYS.toMillis(1)));
            assertTrue(f.manager.prepareProcessRecovery(0));
            f.prepared = true;
            assertFalse(f.session.retainFiles());
            assertFalse(f.session.tryBeginNativeOperation());
            assertThrows(IllegalStateException.class,
                    () -> f.manager.create(SessionType.PLAYBACK, "dev", 1));
            assertEquals(1, f.manager.cleanupSnapshot().size());
            assertSame(f.callback, f.session.getDataCallback());
            assertFalse(f.manager.cleanupSnapshot().getFirst().cmsStopped());
        }
    }

    @Test void nonterminalSessionRejectsBothReadOnlyCheckAndPreparation() {
        try (Fixture f = new Fixture(true, null)) {
            MediaSession active = f.manager.create(SessionType.PLAYBACK, "other", 1);
            assertFalse(f.manager.canRecoverProcess(0));
            assertFalse(f.manager.prepareProcessRecovery(0));
            f.manager.stop(active, SessionStatus.STOPPED, "done");
            f.awaitLocalStopped();
            assertTrue(f.manager.canRecoverProcess(0));
        }
    }

    @Test void retainedFileConsumerRejectsPreparationWithoutPartiallyFreezingOtherSessions() {
        try (Fixture f = new Fixture(true, null)) {
            MediaSession retained = f.manager.create(SessionType.PLAYBACK, "other", 1);
            f.manager.stop(retained, SessionStatus.STOPPED, "done");
            assertTrue(retained.retainFiles());
            assertFalse(f.manager.canRecoverProcess(0));
            assertFalse(f.manager.prepareProcessRecovery(0));
            // A failed prepare must not freeze even the already-quiescent first session.
            assertTrue(f.session.retainFiles());
            f.session.releaseFiles();
            retained.releaseFiles();
            f.awaitLocalStopped();
            assertTrue(f.manager.canRecoverProcess(0));
        }
    }

    @Test void inFlightNativeOperationOnTerminalSessionRejectsPreparation() {
        try (Fixture f = new Fixture(true, null)) {
            MediaSession nativeCall = f.manager.create(SessionType.PLAYBACK, "other", 1);
            assertTrue(nativeCall.tryBeginNativeOperation());
            f.manager.stop(nativeCall, SessionStatus.FAILED, "cancel during native start");
            assertTrue(nativeCall.isFinished());
            assertFalse(f.manager.canRecoverProcess(0));
            assertFalse(f.manager.prepareProcessRecovery(0));
            f.manager.endNativeOperation(nativeCall);
            f.awaitLocalStopped();
            assertTrue(f.manager.canRecoverProcess(0));
        }
    }

    @Test void failedStreamStopBlocksRecoveryAndRemainsVisible() {
        try (Fixture f = new Fixture(false, null)) {
            assertFalse(f.manager.canRecoverProcess(0));
            assertFalse(f.manager.prepareProcessRecovery(0));
            assertEquals(55, f.manager.cleanupSnapshot().getFirst().lastStreamErrorCode());
            assertFalse(f.manager.cleanupSnapshot().getFirst().streamStopped());
            assertTrue(f.session.retainFiles());
            f.session.releaseFiles();
        }
    }

    @Test void activeLocalWriterAndLocalCleanupRejectRecoveryUntilActuallyExited() throws Exception {
        CountDownLatch exit = new CountDownLatch(1);
        AtomicBoolean alive = new AtomicBoolean(true);
        FfmpegProcess writer = mock(FfmpegProcess.class);
        when(writer.isAlive()).thenAnswer(invocation -> alive.get());
        when(writer.waitFor(anyLong())).thenAnswer(invocation -> {
            if (!exit.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test writer timed out");
            alive.set(false);
            return 0;
        });
        try (Fixture f = new Fixture(true, writer)) {
            try {
                assertTrue(writer.isAlive());
                assertTrue(f.manager.cleanupSnapshot().getFirst().localRunning());
                assertFalse(f.manager.cleanupSnapshot().getFirst().localStopped());
                assertFalse(f.manager.canRecoverProcess(0));
                assertFalse(f.manager.prepareProcessRecovery(0));
            } finally {
                exit.countDown();
            }
            f.awaitLocalStopped();
            assertTrue(f.manager.canRecoverProcess(0));
        } finally { exit.countDown(); }
    }
}
