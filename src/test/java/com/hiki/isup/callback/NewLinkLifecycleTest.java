package com.hiki.isup.callback;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.hiki.isup.service.SessionManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NewLinkLifecycleTest {
    @Test
    void liveDownloadExactIdWinsOverRetainedOldPlaybackId() {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_SetPlayBackDataCB(anyInt(), any())).thenReturn(true);
        SessionManager manager = new SessionManager(loader, new IsupProperties());
        try {
            MediaSession old = manager.create(SessionType.PLAYBACK, "device", 1);
            manager.registerIsupSession(old, 42);
            manager.stop(old, SessionStatus.STOPPED, "old done");
            MediaSession next = manager.create(SessionType.DOWNLOAD, "device", 1);
            manager.registerIsupSession(next, 42);
            assertTrue(invokeLink(manager, loader, SessionType.DOWNLOAD, 123));
            assertEquals(123, next.getLinkHandle());
            assertEquals(-1, old.getLinkHandle());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void missingNativeIdCannotChooseBetweenPlaybackAndDownloadOnSameChannel() {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_SetPlayBackDataCB(anyInt(), any())).thenReturn(true);
        SessionManager manager = new SessionManager(loader, new IsupProperties());
        try {
            MediaSession playback = manager.create(SessionType.PLAYBACK, "device", 1);
            MediaSession download = manager.create(SessionType.DOWNLOAD, "device", 1);
            HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO info = new HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO();
            info.lSessionID = -1;
            info.dwChannelNo = 1;
            System.arraycopy("device".getBytes(StandardCharsets.UTF_8), 0, info.szDeviceID, 0, 6);
            assertFalse(new PlaybackNewLinkCallback(manager, loader).invoke(123, info, null));
            assertNull(playback.getDataCallback());
            assertNull(download.getDataCallback());
            verifyNoInteractions(stream);
        } finally {
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void unknownPositiveIdMustNotOverwriteNextOrdersRegisteredId(SessionType type) {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_SetPlayBackDataCB(anyInt(), any())).thenReturn(true);
        when(stream.NET_ESTREAM_SetPreviewDataCB(anyInt(), any())).thenReturn(true);
        SessionManager manager = new SessionManager(loader, new IsupProperties());
        try {
            MediaSession next = manager.create(type, "device", 1);
            manager.registerIsupSession(next, 43);
            assertFalse(invokeLink(manager, loader, type, 123), "未知旧 ID 42 不能改写新单已登记的 ID 43");
            assertEquals(43, next.getIsupSessionId());
            assertNull(next.getDataCallback());
            verifyNoInteractions(stream);
        } finally {
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void nativeCallbackRegistrationMustFinishBeforeStopAndUnload(SessionType type) throws Exception {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.stream()).thenReturn(stream);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        when(stream.NET_ESTREAM_SetPlayBackDataCB(anyInt(), any())).thenAnswer(inv -> {
            entered.countDown(); release.await(); return true;
        });
        when(stream.NET_ESTREAM_SetPreviewDataCB(anyInt(), any())).thenAnswer(inv -> {
            entered.countDown(); release.await(); return true;
        });
        when(stream.NET_ESTREAM_StopPlayBack(anyInt())).thenAnswer(inv -> { stopped.countDown(); return true; });
        when(stream.NET_ESTREAM_StopPreview(anyInt())).thenAnswer(inv -> { stopped.countDown(); return true; });
        IsupProperties properties = new IsupProperties();
        properties.getSession().setMaxActiveSessions(1);
        SessionManager manager = new SessionManager(loader, properties);
        MediaSession session = manager.create(type, "device", 1);
        manager.registerIsupSession(session, 42);
        CompletableFuture<Boolean> registration = CompletableFuture.supplyAsync(() -> invokeLink(manager, loader, type, 123));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            manager.stop(session, SessionStatus.FAILED, "cancel during SetDataCB");
            assertEquals(SessionStatus.FAILED, session.getStatus());
            assertFalse(stopped.await(200, TimeUnit.MILLISECONDS), "SetDataCB 尚未返回时不能同时 Stop");
            assertNotNull(session.getDataCallback(), "native 仍持有注册中的 callback");
            assertFalse(manager.prepareSdkUnload(20), "注册中的 native 调用必须阻止 SDK 卸载");
            assertThrows(IllegalStateException.class, () -> manager.create(type, "device", 2));
            release.countDown();
            assertFalse(registration.get(2, TimeUnit.SECONDS), "取消后的注册不能再报告成功");
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            assertTrue(manager.prepareSdkUnload(2000));
            assertNull(session.getDataCallback());
        } finally {
            release.countDown();
            registration.get(2, TimeUnit.SECONDS);
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void duplicateLinkCannotReplaceLiveCallback(SessionType type) {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_SetPlayBackDataCB(anyInt(), any())).thenReturn(true);
        when(stream.NET_ESTREAM_SetPreviewDataCB(anyInt(), any())).thenReturn(true);
        SessionManager manager = new SessionManager(loader, new IsupProperties());
        try {
            MediaSession session = manager.create(type, "device", 1);
            manager.registerIsupSession(session, 42);
            assertTrue(invokeLink(manager, loader, type, 123));
            Object originalCallback = session.getDataCallback();
            assertFalse(invokeLink(manager, loader, type, 124));
            assertEquals(123, session.getLinkHandle());
            assertSame(originalCallback, session.getDataCallback());
        } finally {
            manager.shutdown();
        }
    }

    private static boolean invokeLink(SessionManager manager, NativeSdkLoader loader, SessionType type, int handle) {
        if (type == SessionType.PREVIEW) {
            HCISUPStream.NET_EHOME_NEWLINK_CB_MSG info = new HCISUPStream.NET_EHOME_NEWLINK_CB_MSG();
            info.iSessionID = 42;
            info.dwChannelNo = 1;
            System.arraycopy("device".getBytes(StandardCharsets.UTF_8), 0, info.szDeviceID, 0, 6);
            return new PreviewNewLinkCallback(manager, loader).invoke(handle, info, null);
        }
        HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO info = new HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO();
        info.lSessionID = 42;
        info.dwChannelNo = 1;
        System.arraycopy("device".getBytes(StandardCharsets.UTF_8), 0, info.szDeviceID, 0, 6);
        return new PlaybackNewLinkCallback(manager, loader).invoke(handle, info, null);
    }

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void lateLinkForOldSessionMustNotAttachToNextOrder(SessionType type) {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_SetPlayBackDataCB(anyInt(), any())).thenReturn(true);
        when(stream.NET_ESTREAM_SetPreviewDataCB(anyInt(), any())).thenReturn(true);
        SessionManager manager = new SessionManager(loader, new IsupProperties());
        try {
            MediaSession old = manager.create(type, "device", 1);
            manager.registerIsupSession(old, 42);
            old.markStopping();
            old.setStatus(SessionStatus.FAILED);
            MediaSession next = manager.create(type, "device", 1);
            boolean accepted;
            if (type == SessionType.PREVIEW) {
                HCISUPStream.NET_EHOME_NEWLINK_CB_MSG info = new HCISUPStream.NET_EHOME_NEWLINK_CB_MSG();
                info.iSessionID = 42;
                info.dwChannelNo = 1;
                System.arraycopy("device".getBytes(StandardCharsets.UTF_8), 0, info.szDeviceID, 0, 6);
                accepted = new PreviewNewLinkCallback(manager, loader).invoke(123, info, null);
            } else {
                HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO info = new HCISUPStream.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO();
                info.lSessionID = 42;
                info.dwChannelNo = 1;
                System.arraycopy("device".getBytes(StandardCharsets.UTF_8), 0, info.szDeviceID, 0, 6);
                accepted = new PlaybackNewLinkCallback(manager, loader).invoke(123, info, null);
            }
            assertFalse(accepted, "旧流必须拒绝，不能以相同设备通道兜底绑定下一单");
            assertEquals(-1, next.getLinkHandle());
            assertNull(next.getDataCallback());
            verifyNoInteractions(stream);
        } finally {
            manager.shutdown();
        }
    }
}
