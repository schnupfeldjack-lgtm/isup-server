package com.hiki.isup.service;

import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.StationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelStationsTest {

    @Test
    @DisplayName("全景和细节按前缀合成一个工位，通道号取设备返回值")
    void groupsPanoramaAndDetailByPrefix() {
        List<StationChannel> stations = ChannelStations.group(List.of(
                new DeviceChannel(1, "打包台1全景"),
                new DeviceChannel(2, "打包台1细节"),
                new DeviceChannel(3, "打包台2全景"),
                new DeviceChannel(4, "打包台2细节"),
                new DeviceChannel(5, "IPCamera 03")));

        assertEquals(2, stations.size());
        assertEquals("打包台1", stations.get(0).getChannelCode());
        assertEquals(1, stations.get(0).getPanoramaChannel());
        assertEquals(2, stations.get(0).getDetailChannel());
        assertEquals("打包台2", stations.get(1).getChannelCode());
        assertEquals(3, stations.get(1).getPanoramaChannel());
        assertEquals(4, stations.get(1).getDetailChannel());
    }

    @Test
    @DisplayName("只有一边时另一边通道号为空")
    void keepsSingleSide() {
        List<StationChannel> stations = ChannelStations.group(List.of(
                new DeviceChannel(7, "打包台3细节")));

        assertEquals(1, stations.size());
        assertEquals("打包台3", stations.get(0).getChannelCode());
        assertNull(stations.get(0).getPanoramaChannel());
        assertEquals(7, stations.get(0).getDetailChannel());
    }

    @Test
    @DisplayName("channel_code 精确匹配工位，缺边和重复边给出对应错误")
    void matchReportsMissingAndDuplicate() {
        List<DeviceChannel> channels = List.of(
                new DeviceChannel(1, "打包台1全景"),
                new DeviceChannel(2, "打包台1细节"),
                new DeviceChannel(3, "打包台2全景"),
                new DeviceChannel(8, "打包台2全景"),
                new DeviceChannel(9, "打包台4细节"));

        ChannelStations.Match hit = ChannelStations.match(channels, "打包台1");
        assertEquals(1, hit.panoramaChannel());
        assertEquals(2, hit.detailChannel());
        assertNull(hit.panoramaError());
        assertNull(hit.detailError());

        ChannelStations.Match missingDetail = ChannelStations.match(channels, "打包台4");
        assertNull(missingDetail.panoramaChannel());
        assertEquals(9, missingDetail.detailChannel());
        assertEquals("未匹配到全景通道：打包台4全景", missingDetail.panoramaError());
        assertNull(missingDetail.detailError());

        ChannelStations.Match duplicate = ChannelStations.match(channels, "打包台2");
        assertNull(duplicate.panoramaChannel());
        assertEquals("全景通道不唯一：打包台2全景", duplicate.panoramaError());
        assertEquals("未匹配到细节通道：打包台2细节", duplicate.detailError());

        ChannelStations.Match unknown = ChannelStations.match(channels, "打包台9");
        assertEquals("未匹配到全景通道：打包台9全景", unknown.panoramaError());
        assertEquals("未匹配到细节通道：打包台9细节", unknown.detailError());
    }
}
