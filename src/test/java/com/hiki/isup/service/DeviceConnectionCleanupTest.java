package com.hiki.isup.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.hiki.isup.callback.DeviceRegisterCallback;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.PreviewRequest;
import com.hiki.isup.model.request.PlaybackRequest;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.NativeSdkLoader;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceConnectionCleanupTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void publicationBeforeCleanupPreservesNativeLeaseAndOnlyStopsOldSdkSession(SessionType type) {
        IsupProperties properties = new IsupProperties();
        properties.getSession().setMaxActiveSessions(2);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPCMS cms = mock(HCISUPCMS.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.cms()).thenReturn(cms);
        when(cms.NET_ECMS_StopGetRealStream(anyInt(), anyInt())).thenReturn(true);
        when(cms.NET_ECMS_StopPlayBack(anyInt(), anyInt())).thenReturn(true);
        SessionManager manager = new SessionManager(loader, properties);
        try {
            DeviceInfo expired = new DeviceInfo("device", 7, "127.0.0.1");
            MediaSession oldSession = manager.create(type, expired, 1);
            oldSession.setLUserId(7);
            manager.registerIsupSession(oldSession, 41);
            assertTrue(oldSession.tryBeginNativeOperation());
            DeviceInfo reconnected = new DeviceInfo("device", 7, "127.0.0.2");
            MediaSession newSession = manager.create(type, reconnected, 1);
            newSession.setLUserId(7);
            manager.registerIsupSession(newSession, 42);
            manager.stopByConnectionAsync(expired);
            assertEquals(SessionStatus.FAILED, oldSession.getStatus());
            assertEquals(SessionStatus.STARTING, newSession.getStatus());
            assertEquals(2, manager.resourceSnapshot().reservedSlots(), "native lease must retain old resource slot");
            verifyNoInteractions(cms);
            manager.endNativeOperation(oldSession);
            if (type == SessionType.PREVIEW) {
                verify(cms, timeout(2000)).NET_ECMS_StopGetRealStream(7, 41);
                verify(cms, never()).NET_ECMS_StopGetRealStream(7, 42);
            } else {
                verify(cms, timeout(2000)).NET_ECMS_StopPlayBack(7, 41);
                verify(cms, never()).NET_ECMS_StopPlayBack(7, 42);
            }
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (manager.resourceSnapshot().reservedSlots() != 1) Thread.sleep(5);
            });
            assertEquals(SessionStatus.STARTING, newSession.getStatus());
        } finally {
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void cleanupBeforePublicationRejectsExpiredConnectionWithoutReservingSlot(SessionType type) {
        IsupProperties properties = new IsupProperties();
        properties.getSession().setMaxActiveSessions(1);
        SessionManager manager = new SessionManager(mock(NativeSdkLoader.class), properties);
        DeviceInfo expired = new DeviceInfo("device", 7, "127.0.0.1");
        try {
            manager.stopByConnectionAsync(expired);
            assertThrows(IllegalStateException.class, () -> manager.create(type, expired, 1));
            assertEquals(0, manager.resourceSnapshot().reservedSlots());
            assertTrue(manager.list().isEmpty());
            DeviceInfo reconnected = new DeviceInfo("device", 7, "127.0.0.2");
            MediaSession newSession = manager.create(type, reconnected, 1);
            manager.stopByConnectionAsync(expired);
            assertEquals(SessionStatus.STARTING, newSession.getStatus());
            assertEquals(1, manager.resourceSnapshot().reservedSlots());
        } finally {
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void expirationBetweenServiceSnapshotAndPublicationNeverStartsSdk(SessionType type) {
        IsupProperties properties = new IsupProperties();
        properties.setMediaDir(directory.toString());
        SessionManager manager = new SessionManager(mock(NativeSdkLoader.class), properties);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        DeviceInfo expired = new DeviceInfo("device", 7, "127.0.0.1");
        when(registry.requireConnection(anyString(), anyString())).thenAnswer(call -> {
            manager.stopByConnectionAsync(expired);
            return expired;
        });
        IsupService isup = mock(IsupService.class);
        HCISUPCMS cms = mock(HCISUPCMS.class);
        when(isup.cms()).thenReturn(cms);
        when(cms.NET_ECMS_StartGetRealStreamV11(anyInt(), any(), any())).thenReturn(true);
        when(cms.NET_ECMS_StartPushRealStream(anyInt(), any(), any())).thenReturn(true);
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenReturn(true);
        when(cms.NET_ECMS_StartPushPlayBack(anyInt(), any(), any())).thenReturn(true);
        FfmpegService ffmpeg = mock(FfmpegService.class);
        DownloadService downloads = new DownloadService(properties, isup, registry, manager, ffmpeg);
        try {
            assertThrows(IllegalStateException.class, () -> {
                switch (type) {
                    case PREVIEW -> {
                        PreviewRequest request = new PreviewRequest();
                        request.setDeviceId("device"); request.setChannel(1);
                        new PreviewService(properties, isup, registry, manager, ffmpeg).startPreview(request);
                    }
                    case PLAYBACK -> {
                        PlaybackRequest request = new PlaybackRequest();
                        request.setDeviceId("device"); request.setChannel(1);
                        request.setStartTime("2026-10-15 10:00:00"); request.setEndTime("2026-10-15 10:01:00");
                        new PlaybackService(properties, isup, registry, manager, ffmpeg).startPlayback(request);
                    }
                    case DOWNLOAD -> {
                        DownloadRequest request = new DownloadRequest();
                        request.setDeviceId("device"); request.setChannel(1);
                        request.setStartTime("2026-10-15 10:00:00"); request.setEndTime("2026-10-15 10:01:00");
                        downloads.startDownload(request);
                    }
                }
            });
            verifyNoInteractions(cms, ffmpeg);
            assertEquals(0, manager.resourceSnapshot().reservedSlots());
        } finally {
            downloads.shutdown();
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(SessionType.class)
    void eventualOfflineAlsoCleansSessionsPreservedThroughEarlierGraceReconnect(SessionType type) {
        DeviceRegistry registry = new DeviceRegistry(30);
        IsupProperties properties = new IsupProperties();
        // 本用例有意同时保留三代，独立验证连接清理；默认两路限额另有按需回放测试。
        properties.getSession().setMaxPlaybackPerDevice(3);
        SessionManager manager = new SessionManager(mock(NativeSdkLoader.class), properties);
        try {
            DeviceInfo first = registry.online(7, "device", "127.0.0.1", "serial", "camera");
            MediaSession preserved = manager.create(type, first, 1);
            registry.offlineConnection(7, manager::stopByConnectionAsync);
            DeviceInfo second = registry.online(7, "device", "127.0.0.2", "serial", "camera");
            MediaSession expiring = manager.create(type, second, 1);
            assertEquals(SessionStatus.STARTING, preserved.getStatus(), "grace reconnect must preserve existing session");
            registry.offlineConnection(7, manager::stopByConnectionAsync);
            AtomicReference<MediaSession> newest = new AtomicReference<>();
            registry.expireOffline("device", second, ignored -> {
                DeviceInfo third = registry.online(7, "device", "127.0.0.3", "serial", "camera");
                newest.set(manager.create(type, third, 1));
                manager.stopByConnectionAsync(second);
            });
            assertEquals(SessionStatus.FAILED, preserved.getStatus(), "eventual offline must clean older preserved generations");
            assertEquals(SessionStatus.FAILED, expiring.getStatus());
            assertEquals(SessionStatus.STARTING, newest.get().getStatus(), "newer reused-handle generation must survive");
        } finally {
            registry.shutdown();
            manager.shutdown();
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 7", "0, 8", "1, 7", "1, 8"})
    void reconnectAfterRemovalBeforeCleanupKeepsNewSession(int graceSeconds, int newHandle) throws Exception {
        IsupProperties properties = new IsupProperties();
        properties.setMediaDir(directory.toString());
        properties.getSession().setStartTimeoutSec(3600);
        properties.getSession().setIdleTimeoutSec(3600);
        CountDownLatch cleanupDone = new CountDownLatch(1);
        DeviceRegistry registry = new DeviceRegistry(graceSeconds) {
            @Override void expireOffline(String deviceId, DeviceInfo expected, Consumer<String> cleanup) {
                try { super.expireOffline(deviceId, expected, cleanup); }
                finally { cleanupDone.countDown(); }
            }
        };
        HCISUPCMS cms = mock(HCISUPCMS.class);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.cms()).thenReturn(cms);
        when(cms.NET_ECMS_StopGetRealStream(anyInt(), anyInt())).thenReturn(true);
        AtomicInteger ids = new AtomicInteger(40);
        when(cms.NET_ECMS_StartGetRealStreamV11(anyInt(), any(), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT out = call.getArgument(2);
            out.lSessionID = ids.incrementAndGet();
            out.write();
            return true;
        });
        when(cms.NET_ECMS_StartPushRealStream(anyInt(), any(), any())).thenReturn(true);
        IsupService isup = mock(IsupService.class);
        when(isup.cms()).thenReturn(cms);
        SessionManager manager = new SessionManager(loader, properties);
        PreviewService previews = new PreviewService(properties, isup, registry, manager, mock(FfmpegService.class));
        DeviceRegisterCallback callback = new DeviceRegisterCallback(properties, registry, manager, loader);
        registry.online(7, "device", "127.0.0.1", "serial", "camera");
        PreviewRequest request = new PreviewRequest();
        request.setDeviceId("device");
        request.setChannel(1);
        previews.startPreview(request);
        MediaSession oldSession = manager.list().getFirst();
        AtomicReference<MediaSession> newSession = new AtomicReference<>();
        AtomicReference<Throwable> reconnectFailure = new AtomicReference<>();
        CountDownLatch reconnected = new CountDownLatch(1);
        Logger logger = (Logger) LoggerFactory.getLogger(DeviceRegistry.class);
        AppenderBase<ILoggingEvent> reconnectAtRemoval = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                if (reconnected.getCount() == 0 || registry.get("device") != null) return;
                if (!event.getMessage().contains("已移出列表") && !event.getMessage().startsWith("设备下线：")) return;
                try {
                    registry.online(newHandle, "device", "127.0.0.2", "serial", "camera");
                    previews.startPreview(request);
                    newSession.set(manager.list().stream().filter(s -> s != oldSession).findFirst().orElseThrow());
                } catch (Throwable failure) {
                    reconnectFailure.set(failure);
                } finally {
                    reconnected.countDown();
                }
            }
        };
        reconnectAtRemoval.start();
        logger.addAppender(reconnectAtRemoval);
        try {
            assertTrue(callback.invoke(7, HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_OFF, null, 0, null, 0, null));
            assertTrue(reconnected.await(5, TimeUnit.SECONDS), "must reconnect after removal before cleanup callback");
            if (graceSeconds > 0) assertTrue(cleanupDone.await(3, TimeUnit.SECONDS));
            assertNull(reconnectFailure.get());
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!oldSession.isFinished()) Thread.sleep(5);
            });
            assertEquals(SessionStatus.FAILED, oldSession.getStatus(), "expired connection must still be cleaned");
            assertNotNull(newSession.get());
            assertEquals(SessionStatus.STARTING, newSession.get().getStatus(), "cleanup must preserve the new connection even when lUserID is reused");
            assertEquals(newHandle, registry.get("device").getLUserId());
            verify(cms, timeout(2000)).NET_ECMS_StopGetRealStream(7, 41);
            verify(cms, never()).NET_ECMS_StopGetRealStream(newHandle, 42);
        } finally {
            logger.detachAppender(reconnectAtRemoval);
            reconnectAtRemoval.stop();
            registry.shutdown();
            manager.shutdown();
        }
    }
}
