package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.PlaybackRequest;
import com.hiki.isup.sdk.HCISUPCMS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static com.hiki.isup.sdk.NativeSdkLoader.readString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回放入参构造测试：验证按时间回放模式、通道、起止时间与流媒体地址是否被正确填充。
 * （JNA 结构体为纯内存操作，不需要真实 SDK）
 */
@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

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

    private PlaybackService service;
    private MediaSession session;

    @BeforeEach
    void setUp() {
        IsupProperties properties = new IsupProperties();
        properties.setPublicIp("203.0.113.10");
        properties.setMediaDir(tempDir.toString());
        service = new PlaybackService(properties, isupService, deviceRegistry, sessionManager, ffmpegService);
        session = new MediaSession("sess-playback", SessionType.PLAYBACK, "GW4206623", 1);
        lenient().when(deviceRegistry.requireConnection(anyString(), anyString())).thenReturn(new DeviceInfo("GW4206623", 7, "127.0.0.1"));
        lenient().when(sessionManager.create(any(), any(DeviceInfo.class), anyInt())).thenReturn(session);
        lenient().when(isupService.cms()).thenReturn(cms);
    }

    @Test
    @DisplayName("按时间回放入参：byPlayBackMode=1、PS 封装、起止时间正确")
    void buildPlaybackInfoIn() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 19, 14, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 19, 14, 10, 0);

        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN in =
                PlaybackService.buildPlaybackInfoIn(1, start, end, "47.100.100.100", 8661);

        assertEquals(1, in.dwChannel, "通道号");
        assertEquals(1, in.byPlayBackMode, "1=按时间回放");
        assertEquals(0, in.byStreamPackage, "0=PS 封装，交给 ffmpeg 解封装");
        assertEquals("47.100.100.100", readString(in.struStreamSever.szIP), "回传给设备的是公网 IP");
        assertEquals((short) 8661, in.struStreamSever.wPort, "回传给设备的是回放监听端口 8661");

        in.unionPlayBackMode.setType(HCISUPCMS.NET_EHOME_PLAYBACKBYTIME.class);
        in.unionPlayBackMode.read();
        HCISUPCMS.NET_EHOME_PLAYBACKBYTIME byTime = in.unionPlayBackMode.struPlayBackbyTime;

        assertEquals(2026, byTime.struStartTime.wYear);
        assertEquals(9, byTime.struStartTime.byMonth);
        assertEquals(19, byTime.struStartTime.byDay);
        assertEquals(14, byTime.struStartTime.byHour);
        assertEquals(0, byTime.struStartTime.byMinute);
        assertEquals(0, byTime.struStartTime.bySecond);

        assertEquals(14, byTime.struStopTime.byHour);
        assertEquals(10, byTime.struStopTime.byMinute);

        assertEquals(0, byTime.byLocalOrUTC, "0=设备本地时间");
        assertEquals(0, byTime.byDuplicateSegment);
    }

    @Test
    @DisplayName("不同通道与时间应正确带入")
    void buildPlaybackInfoInOtherChannel() {
        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN in = PlaybackService.buildPlaybackInfoIn(
                33, LocalDateTime.of(2025, 1, 2, 3, 4, 5),
                LocalDateTime.of(2025, 1, 2, 5, 6, 7), "10.0.0.1", 8661);

        assertEquals(33, in.dwChannel);
        in.unionPlayBackMode.setType(HCISUPCMS.NET_EHOME_PLAYBACKBYTIME.class);
        in.unionPlayBackMode.read();
        HCISUPCMS.NET_EHOME_PLAYBACKBYTIME byTime = in.unionPlayBackMode.struPlayBackbyTime;
        assertEquals(2025, byTime.struStartTime.wYear);
        assertEquals(3, byTime.struStartTime.byHour);
        assertEquals(5, byTime.struStopTime.byHour);
    }

    @Test
    @DisplayName("StartPlayBack 失败时不起 ffmpeg")
    void sdkStartFailureDoesNotStartFfmpeg() throws Exception {
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenReturn(false);
        when(cms.NET_ECMS_GetLastError()).thenReturn(17);

        assertThrows(IsupException.class, () -> service.startPlayback(playbackRequest()));
        verify(ffmpegService, never()).startPlaybackHls(any(), any(), anyString());
    }

    @Test
    @DisplayName("先 StartPlayBack，成功后再起 ffmpeg，最后 StartPushPlayBack")
    void ffmpegStartsAfterSdkThenPush() throws Exception {
        stubSdkStartSuccess();
        when(ffmpegService.startPlaybackHls(any(), any(), anyString())).thenReturn(ffmpegProcess);
        when(cms.NET_ECMS_StartPushPlayBack(anyInt(), any(), any())).thenReturn(true);

        service.startPlayback(playbackRequest());

        InOrder order = inOrder(cms, ffmpegService);
        order.verify(cms).NET_ECMS_StartPlayBack(anyInt(), any(), any());
        order.verify(ffmpegService).startPlaybackHls(any(), any(), anyString());
        order.verify(cms).NET_ECMS_StartPushPlayBack(anyInt(), any(), any());
    }

    private void stubSdkStartSuccess() {
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenAnswer(invocation -> {
            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT out = invocation.getArgument(2);
            out.lSessionID = 42;
            out.write();
            return true;
        });
    }

    private static PlaybackRequest playbackRequest() {
        PlaybackRequest request = new PlaybackRequest();
        request.setDeviceId("GW4206623");
        request.setChannel(1);
        request.setStartTime("2026-09-19 14:00:00");
        request.setEndTime("2026-09-19 14:10:00");
        return request;
    }
}
