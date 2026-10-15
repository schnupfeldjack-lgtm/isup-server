package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.PreviewRequest;
import com.hiki.isup.sdk.HCISUPCMS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreviewServiceTest {

    @Mock
    private IsupService isupService;
    @Mock
    private DeviceRegistry deviceRegistry;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private FfmpegService ffmpegService;
    @Mock
    private HCISUPCMS cms;
    @Mock
    private FfmpegProcess ffmpegProcess;

    @TempDir
    Path tempDir;

    private PreviewService service;
    private MediaSession session;

    @BeforeEach
    void setUp() {
        IsupProperties properties = new IsupProperties();
        properties.setPublicIp("203.0.113.10");
        properties.setMediaDir(tempDir.toString());
        service = new PreviewService(properties, isupService, deviceRegistry, sessionManager, ffmpegService);
        session = new MediaSession("sess-preview", SessionType.PREVIEW, "GW4206623", 1);
        lenient().when(deviceRegistry.requireConnection(anyString(), anyString())).thenReturn(new DeviceInfo("GW4206623", 7, "127.0.0.1"));
        lenient().when(sessionManager.create(any(), any(DeviceInfo.class), anyInt())).thenReturn(session);
        lenient().when(isupService.cms()).thenReturn(cms);
    }

    @Test
    @DisplayName("未指定 streamType 时按子码流取流")
    void omittedStreamTypeUsesSubstream() throws Exception {
        stubSdkStartSuccess();
        when(ffmpegService.startHls(any(), any(), anyString())).thenReturn(ffmpegProcess);
        when(cms.NET_ECMS_StartPushRealStream(anyInt(), any(), any())).thenReturn(true);

        PreviewRequest request = previewRequest();
        request.setStreamType(null);
        service.startPreview(request);

        ArgumentCaptor<HCISUPCMS.NET_EHOME_PREVIEWINFO_IN_V11> captor =
                ArgumentCaptor.forClass(HCISUPCMS.NET_EHOME_PREVIEWINFO_IN_V11.class);
        verify(cms).NET_ECMS_StartGetRealStreamV11(anyInt(), captor.capture(), any());
        assertEquals(1, captor.getValue().dwStreamType);
    }

    @Test
    @DisplayName("预览监听未启用时不调 SDK、不起 ffmpeg")
    void previewListenDisabledFailsBeforeSdk() throws Exception {
        doThrow(IsupException.failed("NET_ECMS_StartGetRealStreamV11", null, null, null,
                "预览监听未启用"))
                .when(isupService).requirePreviewListen(anyString());

        assertThrows(IsupException.class, () -> service.startPreview(previewRequest()));
        verify(cms, never()).NET_ECMS_StartGetRealStreamV11(anyInt(), any(), any());
        verify(ffmpegService, never()).startHls(any(), any(), anyString());
    }

    @Test
    @DisplayName("StartGetRealStream 失败时不起 ffmpeg")
    void sdkStartFailureDoesNotStartFfmpeg() throws Exception {
        when(cms.NET_ECMS_StartGetRealStreamV11(anyInt(), any(), any())).thenReturn(false);
        when(cms.NET_ECMS_GetLastError()).thenReturn(17);

        assertThrows(IsupException.class, () -> service.startPreview(previewRequest()));
        verify(ffmpegService, never()).startHls(any(), any(), anyString());
    }

    @Test
    @DisplayName("先 StartGetRealStream，成功后再起 ffmpeg，最后 StartPush")
    void ffmpegStartsAfterSdkThenPush() throws Exception {
        stubSdkStartSuccess();
        when(ffmpegService.startHls(any(), any(), anyString())).thenReturn(ffmpegProcess);
        when(cms.NET_ECMS_StartPushRealStream(anyInt(), any(), any())).thenReturn(true);

        service.startPreview(previewRequest());

        InOrder order = inOrder(cms, ffmpegService);
        order.verify(cms).NET_ECMS_StartGetRealStreamV11(anyInt(), any(), any());
        order.verify(ffmpegService).startHls(any(), any(), anyString());
        order.verify(cms).NET_ECMS_StartPushRealStream(anyInt(), any(), any());
    }

    private void stubSdkStartSuccess() {
        when(cms.NET_ECMS_StartGetRealStreamV11(anyInt(), any(), any())).thenAnswer(invocation -> {
            HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT out = invocation.getArgument(2);
            out.lSessionID = 42;
            out.write();
            return true;
        });
    }

    private static PreviewRequest previewRequest() {
        PreviewRequest request = new PreviewRequest();
        request.setDeviceId("GW4206623");
        request.setChannel(1);
        return request;
    }
}
