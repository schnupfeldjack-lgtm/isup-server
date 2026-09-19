package com.hiki.isup.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载文件名生成测试（含 deviceId 安全化，防止路径穿越）。
 */
class DownloadServiceTest {

    @Test
    @DisplayName("文件名包含设备、通道与时间段")
    void fileName() {
        String name = DownloadService.buildFileName("GW4206623", 1,
                LocalDateTime.of(2026, 9, 19, 14, 0, 0),
                LocalDateTime.of(2026, 9, 19, 14, 10, 0));

        assertEquals("GW4206623_ch1_20260919140000-20260919141000.mp4", name);
        assertTrue(name.endsWith(".mp4"));
    }

    @Test
    @DisplayName("deviceId 中的路径分隔符会被替换，避免路径穿越")
    void fileNameSanitized() {
        String name = DownloadService.buildFileName("../../etc/passwd", 2,
                LocalDateTime.of(2026, 9, 19, 14, 0, 0),
                LocalDateTime.of(2026, 9, 19, 14, 10, 0));

        assertTrue(!name.contains("/"), "文件名不能包含 / : " + name);
        assertTrue(!name.contains("\\"), "文件名不能包含 \\ : " + name);
        assertTrue(!name.contains(".."), "文件名不能包含 .. : " + name);
        assertTrue(name.endsWith("_ch2_20260919140000-20260919141000.mp4"), name);
    }
}
