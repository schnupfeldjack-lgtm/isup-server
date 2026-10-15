package com.hiki.isup.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaybillVideoRecordTest {

    @Test
    @DisplayName("channel_code 空白视为未填，定时任务应跳过")
    void blankChannelCodeIsMissing() {
        WaybillVideoRecord row = new WaybillVideoRecord();
        assertFalse(row.hasChannelCode());
        row.setChannelCode("  ");
        assertFalse(row.hasChannelCode());
        row.setChannelCode("打包台1");
        assertTrue(row.hasChannelCode());
    }

    @Test
    @DisplayName("已有的通道 URL 视为完成，空白视为待下载")
    void urlPresencePerChannel() {
        WaybillVideoRecord row = new WaybillVideoRecord();
        assertFalse(row.hasUrl(CameraChannel.PANORAMA));
        assertFalse(row.hasUrl(CameraChannel.DETAIL));

        row.setUrl(CameraChannel.PANORAMA, "https://oss.example/ch1.mp4");
        assertTrue(row.hasUrl(CameraChannel.PANORAMA));
        assertFalse(row.hasAllUrls(List.of(CameraChannel.PANORAMA, CameraChannel.DETAIL)));

        row.setUrl(CameraChannel.DETAIL, "https://oss.example/ch2.mp4");
        assertTrue(row.hasAllUrls(List.of(CameraChannel.PANORAMA, CameraChannel.DETAIL)));
    }
}
