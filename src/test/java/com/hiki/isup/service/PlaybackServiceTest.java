package com.hiki.isup.service;

import com.hiki.isup.sdk.HCISUPCMS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.hiki.isup.sdk.NativeSdkLoader.readString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回放入参构造测试：验证按时间回放模式、通道、起止时间与流媒体地址是否被正确填充。
 * （JNA 结构体为纯内存操作，不需要真实 SDK）
 */
class PlaybackServiceTest {

    @Test
    @DisplayName("按时间回放入参：byPlayBackMode=1、PS 封装、起止时间正确")
    void buildPlaybackInfoIn() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 19, 14, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 19, 14, 10, 0);

        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN in =
                PlaybackService.buildPlaybackInfoIn(1, start, end, "47.100.100.100", 8004);

        assertEquals(1, in.dwChannel, "通道号");
        assertEquals(1, in.byPlayBackMode, "1=按时间回放");
        assertEquals(0, in.byStreamPackage, "0=PS 封装，交给 ffmpeg 解封装");
        assertEquals("47.100.100.100", readString(in.struStreamSever.szIP), "回传给设备的是公网 IP");
        assertEquals((short) 8004, in.struStreamSever.wPort, "回传给设备的是回放监听端口 8004");

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
                LocalDateTime.of(2025, 1, 2, 5, 6, 7), "10.0.0.1", 8004);

        assertEquals(33, in.dwChannel);
        in.unionPlayBackMode.setType(HCISUPCMS.NET_EHOME_PLAYBACKBYTIME.class);
        in.unionPlayBackMode.read();
        HCISUPCMS.NET_EHOME_PLAYBACKBYTIME byTime = in.unionPlayBackMode.struPlayBackbyTime;
        assertEquals(2025, byTime.struStartTime.wYear);
        assertEquals(3, byTime.struStartTime.byHour);
        assertEquals(5, byTime.struStopTime.byHour);
    }
}
