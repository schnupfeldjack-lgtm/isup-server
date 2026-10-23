package com.hiki.isup.callback;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.*;
import com.hiki.isup.sdk.*;
import com.hiki.isup.service.SessionManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamExceptionLifecycleTest {
    @Test void queuedSmsExceptionCannotStopCurrentOwnerOfReusedHandle() throws Exception {
        try (Fixture f = new Fixture()) {
            MediaSession session = f.link(82, 1);
            Thread invoking = Thread.currentThread();
            AtomicReference<Thread> stopThread = new AtomicReference<>();
            when(f.stream.NET_ESTREAM_StopPlayBack(1)).thenAnswer(i -> {
                stopThread.set(Thread.currentThread()); return true;
            });
            f.exception(0x103, 0, 1, 73);
            assertFalse(session.isFinished());
            verify(f.cms, never()).NET_ECMS_StopPlayBack(anyInt(), anyInt());
        }
    }

    @Test void unknownAndUnrelatedEventsDoNotStopPlayback() throws Exception {
        try (Fixture f = new Fixture()) {
            MediaSession session = f.link(82, 1);
            f.exception(0x102, 0, 1, 73);
            f.exception(0x103, 0, 82, 73);
            assertFalse(session.isFinished());
            verify(f.stream, never()).NET_ESTREAM_StopPlayBack(anyInt());
        }
    }

    @Test void cms3715MustRemainUnconfirmedEvenWithStreamException() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.cms.NET_ECMS_StopPlayBack(anyInt(), anyInt())).thenReturn(false);
            when(f.cms.NET_ECMS_GetLastError()).thenReturn(3715);
            MediaSession session = f.link(82, 1);
            f.exception(0x103, 0, 1, 73);
            f.manager.stopAsync(session, SessionStatus.FAILED, "independently confirmed timeout");
            await().atMost(Duration.ofSeconds(2)).until(() -> !f.manager.cleanupSnapshot().isEmpty()
                    && Integer.valueOf(3715).equals(f.manager.cleanupSnapshot().getFirst().lastCmsErrorCode()));
            assertEquals(SessionStatus.FAILED, session.getStatus());
            assertFalse(f.manager.cleanupSnapshot().getFirst().cmsStopped());
            assertNotNull(session.getDataCallback());
            when(f.cms.NET_ECMS_StopPlayBack(anyInt(), anyInt())).thenReturn(true);
        }
    }

    @Test void duplicateNativeHandleCannotOverwriteLiveBinding() throws Exception {
        try (Fixture f = new Fixture()) {
            MediaSession first = f.link(82, 1);
            MediaSession second = f.prepare(83);
            assertFalse(f.links.invoke(1, f.info(83), null));
            assertEquals(-1, second.getLinkHandle());
            f.exception(0x103, 0, 1, 73);
            assertFalse(first.isFinished());
            assertFalse(second.isFinished());
        }
    }

    private static class Fixture implements AutoCloseable {
        final NativeSdkLoader loader = mock(NativeSdkLoader.class);
        final HCISUPCMS cms = mock(HCISUPCMS.class);
        final HCISUPStream stream = mock(HCISUPStream.class);
        final SessionManager manager;
        final PlaybackNewLinkCallback links;
        Fixture() {
            when(loader.isLoaded()).thenReturn(true);
            when(loader.cms()).thenReturn(cms);
            when(loader.stream()).thenReturn(stream);
            when(stream.NET_ESTREAM_SetPlayBackDataCB(anyInt(), any())).thenReturn(true);
            when(stream.NET_ESTREAM_StopPlayBack(anyInt())).thenReturn(true);
            when(cms.NET_ECMS_StopPlayBack(anyInt(), anyInt())).thenReturn(true);
            manager = new SessionManager(loader, new IsupProperties());
            links = new PlaybackNewLinkCallback(manager, loader);
        }
        MediaSession prepare(int id) {
            MediaSession s = manager.create(SessionType.DOWNLOAD, "device", 2);
            s.setLUserId(7);
            manager.registerIsupSession(s, id);
            return s;
        }
        MediaSession link(int id, int handle) {
            MediaSession s = prepare(id);
            assertTrue(links.invoke(handle, info(id), null));
            return s;
        }
        HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO info(int id) {
            var info = new HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO();
            info.lSessionID = id; info.dwChannelNo = 2;
            byte[] device = "device".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(device, 0, info.szDeviceID, 0, device.length);
            return info;
        }
        void exception(int type, int user, int handle, int error) throws Exception {
            PlaybackNewLinkCallback.class.getMethod("onStreamException", int.class, int.class, int.class, int.class)
                    .invoke(links, type, user, handle, error);
        }
        public void close() { manager.shutdown(); }
    }
}
