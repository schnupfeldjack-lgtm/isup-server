package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.PlaybackRequest;
import com.hiki.isup.model.request.PreviewRequest;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.NativeSdkLoader;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeStartLifecycleTest {
    @TempDir Path temp;

    @ParameterizedTest
    @EnumSource(value = SessionType.class, names = {"PREVIEW", "PLAYBACK"})
    void cancellationInsideNativeStartStopsLateSessionWithoutStartingWriter(SessionType type) throws Exception {
        IsupProperties properties = new IsupProperties();
        properties.setMediaDir(temp.toString());
        properties.setPublicIp("203.0.113.10");
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPCMS cms = mock(HCISUPCMS.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.cms()).thenReturn(cms);
        when(cms.NET_ECMS_StopPlayBack(anyInt(), anyInt())).thenReturn(true);
        when(cms.NET_ECMS_StopGetRealStream(anyInt(), anyInt())).thenReturn(true);
        SessionManager manager = new SessionManager(loader, properties);
        IsupService isup = mock(IsupService.class);
        when(isup.cms()).thenReturn(cms);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        when(registry.requireConnection(anyString(), anyString())).thenReturn(new DeviceInfo("GW4206623", 7, "127.0.0.1"));
        FfmpegService ffmpeg = mock(FfmpegService.class);
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenAnswer(inv -> {
            MediaSession session = manager.list().get(0);
            manager.stop(session, SessionStatus.FAILED, "cancel in Start");
            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT out = inv.getArgument(2);
            out.lSessionID = 42; out.write(); return true;
        });
        when(cms.NET_ECMS_StartGetRealStreamV11(anyInt(), any(), any())).thenAnswer(inv -> {
            MediaSession session = manager.list().get(0);
            manager.stop(session, SessionStatus.FAILED, "cancel in Start");
            HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT out = inv.getArgument(2);
            out.lSessionID = 42; out.write(); return true;
        });
        try {
            assertThrows(RuntimeException.class, () -> {
                if (type == SessionType.PREVIEW) {
                    PreviewRequest request = new PreviewRequest();
                    request.setDeviceId("device"); request.setChannel(1);
                    new PreviewService(properties, isup, registry, manager, ffmpeg).startPreview(request);
                } else {
                    PlaybackRequest request = new PlaybackRequest();
                    request.setDeviceId("device"); request.setChannel(1);
                    request.setStartTime("2026-10-14 10:00:00");
                    request.setEndTime("2026-10-14 10:01:00");
                    new PlaybackService(properties, isup, registry, manager, ffmpeg).startPlayback(request);
                }
            });
            verifyNoInteractions(ffmpeg);
            verify(cms, never()).NET_ECMS_StartPushPlayBack(anyInt(), any(), any());
            verify(cms, never()).NET_ECMS_StartPushRealStream(anyInt(), any(), any());
            if (type == SessionType.PREVIEW) verify(cms, timeout(2000)).NET_ECMS_StopGetRealStream(7, 42);
            else verify(cms, timeout(2000)).NET_ECMS_StopPlayBack(7, 42);
        } finally {
            manager.shutdown();
        }
    }
}
