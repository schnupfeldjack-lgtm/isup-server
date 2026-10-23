package com.hiki.isup.service;

import com.hiki.isup.callback.DeviceRegisterCallback;
import com.hiki.isup.callback.PlaybackNewLinkCallback;
import com.hiki.isup.callback.PreviewNewLinkCallback;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.sdk.NativeSdkLoader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IsupServiceTest {

    @org.junit.jupiter.api.Test
    void confirmedNativeCleanupAllowsNormalUnload() {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        when(loader.isLoaded()).thenReturn(true);
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.prepareSdkUnload(5000)).thenReturn(true);
        IsupService service = new IsupService(new IsupProperties(), loader, mock(DeviceRegisterCallback.class),
                mock(PreviewNewLinkCallback.class), mock(PlaybackNewLinkCallback.class), sessions, mock(FfmpegService.class));
        service.destroy();
        org.mockito.InOrder order = inOrder(sessions, loader);
        order.verify(sessions).stopAll(anyString());
        order.verify(sessions).prepareSdkUnload(5000);
        order.verify(loader).unload(anyInt(), anyInt(), anyInt());
    }

    @org.junit.jupiter.api.Test
    void nativeCleanupNotConfirmedMustNotUnloadLibrary() {
        IsupProperties properties = new IsupProperties();
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        when(loader.isLoaded()).thenReturn(true);
        SessionManager sessions = mock(SessionManager.class);
        IsupService service = new IsupService(properties, loader, mock(DeviceRegisterCallback.class),
                mock(PreviewNewLinkCallback.class), mock(PlaybackNewLinkCallback.class), sessions, mock(FfmpegService.class));
        service.destroy();
        verify(loader, never()).unload(anyInt(), anyInt(), anyInt());
    }

    @org.junit.jupiter.api.Test
    void shutdownClosesAdmissionBeforeStoppingSessions() {
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        SessionManager sessions = mock(SessionManager.class);
        IsupService service = new IsupService(new IsupProperties(), loader, mock(DeviceRegisterCallback.class),
                mock(PreviewNewLinkCallback.class), mock(PlaybackNewLinkCallback.class), sessions, mock(FfmpegService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "ready", true);
        doAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertFalse(service.isReady());
            return null;
        }).when(sessions).stopAll(anyString());
        service.destroy();
    }

    @ParameterizedTest
    @CsvSource({"15, 6", "23, 7"})
    void startWritesConfiguredKeepAliveToNativeListenParameters(int interval, int count) {
        IsupProperties properties = new IsupProperties();
        properties.setPublicIp("203.0.113.10");
        properties.setEhomeKey("test-key");
        properties.setKeepAliveSec(interval);
        properties.setTimeOutCount(count);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPCMS cms = mock(HCISUPCMS.class);
        when(loader.cms()).thenReturn(cms);
        when(cms.NET_ECMS_SetExceptionCallBack(anyInt(), any(), any(), any())).thenReturn(true);
        when(loader.stream()).thenReturn(mock(HCISUPStream.class));
        when(loader.stream().NET_ESTREAM_SetExceptionCallBack(anyInt(), any(), any(), any())).thenReturn(true);
        when(cms.NET_ECMS_StartListen(any())).thenAnswer(invocation -> {
            HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM params = invocation.getArgument(0);
            params.read();
            assertEquals(interval, params.dwKeepAliveSec);
            assertEquals(count, params.dwTimeOutCount);
            return 1;
        });
        DeviceRegisterCallback callback = new DeviceRegisterCallback(properties,
                mock(DeviceRegistry.class), mock(SessionManager.class), loader);
        IsupService service = new IsupService(properties, loader, callback,
                mock(PreviewNewLinkCallback.class), mock(PlaybackNewLinkCallback.class),
                mock(SessionManager.class), mock(FfmpegService.class));

        service.start();

        org.junit.jupiter.api.Assertions.assertTrue(mockingDetails(loader.stream()).getInvocations().stream()
                .anyMatch(invocation -> invocation.getMethod().getName().equals("NET_ESTREAM_SetExceptionCallBack")),
                "Playback exceptions originate in SMS, not CMS");
        org.mockito.InOrder registrationOrder = inOrder(cms);
        registrationOrder.verify(cms).NET_ECMS_SetExceptionCallBack(eq(0), isNull(), any(), isNull());
        registrationOrder.verify(cms).NET_ECMS_StartListen(any());
    }
}
