package com.hiki.isup.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 摄像头通道枚举：1=全景写 video_url1，2=细节写 video_url2。
 */
class CameraChannelTest {

    @Test
    @DisplayName("通道 1 是全景，对应 video_url1")
    void panoramaMapsToUrl1() {
        assertEquals(1, CameraChannel.PANORAMA.getCode());
        assertEquals("video_url1", CameraChannel.PANORAMA.getUrlColumn());
        assertEquals(CameraChannel.PANORAMA, CameraChannel.fromCode(1));
    }

    @Test
    @DisplayName("通道 2 是细节，对应 video_url2")
    void detailMapsToUrl2() {
        assertEquals(2, CameraChannel.DETAIL.getCode());
        assertEquals("video_url2", CameraChannel.DETAIL.getUrlColumn());
        assertEquals(CameraChannel.DETAIL, CameraChannel.fromCode(2));
    }

    @Test
    @DisplayName("非法通道号拒绝，只允许枚举里的 1 和 2")
    void unknownCodeRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CameraChannel.fromCode(3));
        assertEquals("不支持的摄像头通道: 3，仅允许 1(全景) 或 2(细节)", ex.getMessage());
    }
}
