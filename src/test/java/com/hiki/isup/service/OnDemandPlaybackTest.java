package com.hiki.isup.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.*;
import com.hiki.isup.model.request.PlaybackRequest;
import com.hiki.isup.repository.WaybillVideoRepository;
import com.hiki.isup.sdk.NativeSdkLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnDemandPlaybackTest {
    @TempDir Path dir;

    @Test void enabledOverrideStillCannotScanDownloadOrUpload() {
        var p = new IsupProperties(); p.getWaybillVideo().setEnabled(true);
        var db = mock(WaybillVideoRepository.class); var download = mock(DownloadService.class);
        var oss = mock(OssService.class); var manager = mock(SessionManager.class);
        var service = new WaybillVideoSyncService(p, download, manager, oss, db,
                mock(ChannelService.class), mock(DeviceRegistry.class), mock(RecordingRemuxer.class));
        try {
            service.syncOnce();
            verifyNoInteractions(db, download, oss, manager);
            assertThrows(WaybillVideoSyncService.RecoveryConflictException.class,
                    () -> service.releaseRetry("id", "revision", "fingerprint", "manual"));
            verifyNoInteractions(db, download, oss, manager);
            assertEquals(false, service.diagnostics().get("enabled"));
        } finally { service.close(); }
    }

    @Test void completedPlaybackRetainsAllSegments() throws Exception {
        var manager = new SessionManager(new NativeSdkLoader(), new IsupProperties());
        try {
            var session = manager.create(SessionType.PLAYBACK, "device", 1);
            session.setOutputDir(dir.toString());
            Files.writeString(dir.resolve("index.m3u8"), "#EXTM3U\n#EXT-X-ENDLIST\n");
            manager.stop(session, SessionStatus.COMPLETED, null);
            Thread.sleep(150);
            assertTrue(Files.exists(dir.resolve("index.m3u8")), "completed HLS must survive source end");
        } finally { manager.shutdown(); }
    }

    @Test void previewUsesRollingPlaylist() {
        var command = FfmpegService.buildHlsCommand(new IsupProperties(), dir);
        assertEquals("6", command.get(command.indexOf("-hls_list_size") + 1));
        assertTrue(command.contains("delete_segments+append_list"));
    }

    @Test void playbackUsesEventPlaylistWithoutDeletingSegments() throws Exception {
        var method = assertDoesNotThrow(() -> FfmpegService.class.getMethod("buildPlaybackHlsCommand", IsupProperties.class, Path.class));
        @SuppressWarnings("unchecked") var command = (List<String>) method.invoke(null, new IsupProperties(), dir);
        assertEquals("0", command.get(command.indexOf("-hls_list_size") + 1));
        assertEquals("event", command.get(command.indexOf("-hls_playlist_type") + 1));
        assertFalse(command.stream().anyMatch(s -> s.contains("delete_segments") || s.contains("omit_endlist")));
    }

    @Test void playbackPerDeviceLimitDoesNotLimitPreview() {
        var manager = new SessionManager(new NativeSdkLoader(), new IsupProperties());
        try {
            manager.create(SessionType.PLAYBACK, "device", 1);
            manager.create(SessionType.PLAYBACK, "device", 2);
            assertThrows(IllegalStateException.class, () -> manager.create(SessionType.PLAYBACK, "device", 3));
            assertDoesNotThrow(() -> manager.create(SessionType.PREVIEW, "device", 3));
            assertDoesNotThrow(() -> manager.create(SessionType.PLAYBACK, "other", 1));
        } finally { manager.shutdown(); }
    }

    @Test void playbackLongerThanHourRejectedBeforeSdkOrSession() {
        var sdk = mock(IsupService.class); var registry = mock(DeviceRegistry.class);
        var manager = mock(SessionManager.class); var ffmpeg = mock(FfmpegService.class);
        var service = new PlaybackService(new IsupProperties(), sdk, registry, manager, ffmpeg);
        var request = new PlaybackRequest(); request.setDeviceId("device"); request.setChannel(1);
        request.setStartTime("2026-10-22 09:00:00"); request.setEndTime("2026-10-22 10:00:01");
        assertThrows(IllegalArgumentException.class, () -> service.startPlayback(request));
        verifyNoInteractions(sdk, registry, manager, ffmpeg);
    }

    @Test void viewerLeaseIsOptionalAndAcceptedByJson() throws Exception {
        var mapper = new ObjectMapper();
        assertDoesNotThrow(() -> mapper.readValue("{\"viewerLease\":true}", PlaybackRequest.class));
    }

    @Test void leaseExpiryCleansCompletedTailAndGetDoesNotRenew() throws Exception {
        var manager = new SessionManager(new NativeSdkLoader(), new IsupProperties());
        try {
            var session = manager.create(SessionType.PLAYBACK, "device", 1);
            var enable = assertDoesNotThrow(() -> MediaSession.class.getMethod("enableViewerLease"));
            enable.invoke(session);
            var deadline = MediaSession.class.getDeclaredField("viewerLeaseExpiresAt"); deadline.setAccessible(true);
            deadline.setLong(session, System.currentTimeMillis() - 1);
            session.setOutputDir(dir.toString()); Files.writeString(dir.resolve("seg_00000.ts"), "tail");
            session.setStatus(SessionStatus.COMPLETED);
            manager.get(session.getSessionId());
            assertTrue(deadline.getLong(session) < System.currentTimeMillis());
            manager.sweep();
            assertFalse(Files.exists(dir.resolve("seg_00000.ts")));
            assertNull(manager.get(session.getSessionId()));
        } finally { manager.shutdown(); }
    }

    @Test void heartbeatRenewsCompletedPlaybackButRejectsExpiredAndPreview() throws Exception {
        var manager = new SessionManager(new NativeSdkLoader(), new IsupProperties());
        try {
            var session = manager.create(SessionType.PLAYBACK, "device", 1);
            var enable = assertDoesNotThrow(() -> MediaSession.class.getMethod("enableViewerLease")); enable.invoke(session);
            session.setStatus(SessionStatus.COMPLETED);
            var heartbeat = assertDoesNotThrow(() -> SessionManager.class.getMethod("heartbeat", String.class));
            assertSame(session, heartbeat.invoke(manager, session.getSessionId()));
            var deadline = MediaSession.class.getDeclaredField("viewerLeaseExpiresAt"); deadline.setAccessible(true);
            assertTrue(deadline.getLong(session) >= System.currentTimeMillis() + 89_000);
            deadline.setLong(session, 1);
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> heartbeat.invoke(manager, session.getSessionId()));
            var preview = manager.create(SessionType.PREVIEW, "device", 1);
            assertThrows(java.lang.reflect.InvocationTargetException.class, () -> heartbeat.invoke(manager, preview.getSessionId()));
        } finally { manager.shutdown(); }
    }

    @Test void stoppedCompletedPlaybackCannotRenewAndIsCleanedOnNextSweep() throws Exception {
        var manager = new SessionManager(new NativeSdkLoader(), new IsupProperties());
        try {
            var session = manager.create(SessionType.PLAYBACK, "device", 1);
            session.enableViewerLease(); session.setStatus(SessionStatus.COMPLETED);
            session.setOutputDir(dir.toString()); Files.writeString(dir.resolve("index.m3u8"), "tail");
            manager.stopAsync(session, SessionStatus.STOPPED, "client close");
            assertThrows(com.hiki.isup.exception.BadRequestException.class, () -> manager.heartbeat(session.getSessionId()));
            manager.sweep();
            assertFalse(Files.exists(dir.resolve("index.m3u8")));
        } finally { manager.shutdown(); }
    }

    @Test void disabledJobCannotInvokeEvenMockedSyncService() {
        var sync = mock(WaybillVideoSyncService.class);
        new com.hiki.isup.job.WaybillVideoSyncJob(sync).run();
        verifyNoInteractions(sync);
    }

    @Test void leaseCannotReviveFilesWhoseCleanupAlreadyStarted() {
        var session = new MediaSession("id", SessionType.PLAYBACK, "device", 1);
        session.enableViewerLease(); session.setStatus(SessionStatus.COMPLETED);
        assertTrue(session.beginFileCleanup());
        assertFalse(session.renewViewerLease(System.currentTimeMillis()));
    }

    @Test void playbackCodecOverrideLeavesPreviewCodecUnchanged() throws Exception {
        var p = new IsupProperties();
        var setter = assertDoesNotThrow(() -> IsupProperties.Ffmpeg.class.getMethod("setPlaybackCodecArgs", String.class));
        setter.invoke(p.getFfmpeg(), "-c:v libx264 -an");
        assertTrue(FfmpegService.buildPlaybackHlsCommand(p, dir).contains("libx264"));
        assertFalse(FfmpegService.buildHlsCommand(p, dir).contains("libx264"));
    }

    @Test void viewerPlaybackHonorsCleanupAdmissionGuardBeforeAnyNativeWork() {
        var sdk = mock(IsupService.class); var registry = mock(DeviceRegistry.class);
        var manager = mock(SessionManager.class); var ffmpeg = mock(FfmpegService.class);
        when(manager.recordingAdmissionBlocked()).thenReturn(true);
        var request = new PlaybackRequest(); request.setDeviceId("device"); request.setChannel(1);
        request.setStartTime("2026-10-22 09:00:00"); request.setEndTime("2026-10-22 09:01:00");
        request.setViewerLease(true);
        var service = new PlaybackService(new IsupProperties(), sdk, registry, manager, ffmpeg);
        assertThrows(IllegalStateException.class, () -> service.startPlayback(request));
        verifyNoInteractions(sdk, registry, ffmpeg);
        verify(manager).recordingAdmissionBlocked();
        verifyNoMoreInteractions(manager);
    }
}
