package com.hiki.isup.service;

import com.hiki.isup.exception.IsupException;
import com.hiki.isup.exception.BadRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.verifyNoInteractions;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.request.OverlayRecipient;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationOverlayServiceTest {

    @Mock
    private ChannelService channelService;
    @Mock
    private OverlayService overlayService;

    private StationOverlayService service;


    @Test
    void bothCamerasReceiveTheSameFrozenAddressAndClearGuard() {
        when(channelService.listChannels("D")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        when(overlayService.apply(eq("D"), anyInt(), any())).thenReturn(applied());
        OverlayRecipient recipient = new OverlayRecipient("SF1", "R1", "Jane", "US", "CA",
                "Los Angeles", null, "90001", "Demo Road");
        service.apply("D", "打包台1", "SF1", recipient, null);
        service.apply("D", "打包台1", "", null, "SF1");
        ArgumentCaptor<OverlayTextRequest> requests = ArgumentCaptor.forClass(OverlayTextRequest.class);
        verify(overlayService, times(4)).apply(eq("D"), anyInt(), requests.capture());
        assertEquals(recipient, requests.getAllValues().get(0).getRecipient());
        assertEquals(recipient, requests.getAllValues().get(1).getRecipient());
        assertEquals("", requests.getAllValues().get(2).getWaybillNo());
        assertEquals("SF1", requests.getAllValues().get(2).getExpectedWaybillNo());
        assertEquals("SF1", requests.getAllValues().get(3).getExpectedWaybillNo());
        assertTrue(requests.getAllValues().stream().allMatch(OverlayTextRequest::isPackingOverlay));
    }

    @BeforeEach
    void setUp() {
        service = new StationOverlayService(channelService, overlayService);
    }

    @Test
    @DisplayName("打包台1 同时写全景和细节，不碰到打包台2")
    void appliesBothCamerasOfTheStation() {
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"),
                new DeviceChannel(2, "打包台1细节"),
                new DeviceChannel(3, "打包台2全景"),
                new DeviceChannel(4, "打包台2细节")));
        when(overlayService.apply(eq("DLWH1xx"), anyInt(), any())).thenReturn(applied());

        service.apply("DLWH1xx", "打包台1", "SF1234567890");

        ArgumentCaptor<OverlayTextRequest> request = ArgumentCaptor.forClass(OverlayTextRequest.class);
        verify(overlayService).apply(eq("DLWH1xx"), eq(1), request.capture());
        verify(overlayService).apply(eq("DLWH1xx"), eq(2), request.capture());
        verify(overlayService, never()).apply(eq("DLWH1xx"), eq(3), any());
        verify(overlayService, never()).apply(eq("DLWH1xx"), eq(4), any());
        assertEquals("快递单号：SF1234567890", request.getValue().resolveDisplayText());
    }

    @Test
    @DisplayName("空单号两路都只留快递单号前缀")
    void blankWaybillKeepsPrefixOnBothCameras() {
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"),
                new DeviceChannel(2, "打包台1细节")));
        when(overlayService.apply(anyString(), anyInt(), any())).thenReturn(applied());

        service.apply("DLWH1xx", " 打包台1 ", "");

        ArgumentCaptor<OverlayTextRequest> request = ArgumentCaptor.forClass(OverlayTextRequest.class);
        verify(overlayService, times(2)).apply(eq("DLWH1xx"), anyInt(), request.capture());
        assertEquals("快递单号：", request.getValue().resolveDisplayText());
        assertTrue(request.getValue().isEnabled());
    }

    @Test
    @DisplayName("只匹配到一路时写这一路并报告缺失通道")
    void writesTheCameraThatExists() {
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景")));
        when(overlayService.apply(anyString(), anyInt(), any())).thenReturn(applied());

        assertThrows(IsupException.class, () -> service.apply("DLWH1xx", "打包台1", "SF1"));

        verify(overlayService).apply(eq("DLWH1xx"), eq(1), any());
        verify(overlayService, times(1)).apply(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("工位不存在或其中一路写入失败时返回失败")
    void missingStationAndWriteFailureAreReported() {
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"),
                new DeviceChannel(2, "打包台1细节")));
        when(overlayService.apply(eq("DLWH1xx"), eq(1), any()))
                .thenThrow(IsupException.failed("下发叠加", "DLWH1xx", 1, null, "设备未接受"));
        when(overlayService.apply(eq("DLWH1xx"), eq(2), any())).thenReturn(applied());

        assertThrows(IsupException.class, () -> service.apply("DLWH1xx", "打包台9", "SF1"));
        assertThrows(IsupException.class, () -> service.apply("DLWH1xx", "打包台1", "SF1"));

        verify(overlayService).apply(eq("DLWH1xx"), eq(1), any());
        verify(overlayService).apply(eq("DLWH1xx"), eq(2), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "undefined", " null "})
    void invalidStationNeverCallsDevice(String station) {
        assertThrows(BadRequestException.class, () -> service.apply("DLWH", station, "SF1"));
        verifyNoInteractions(channelService, overlayService);
    }

    @Test
    void deviceRejectionIsIncludedInFailure() {
        when(channelService.listChannels("DLWH")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        OverlayTextResponse rejected = new OverlayTextResponse();
        rejected.setDeviceStatusCode(4);
        rejected.setDeviceStatusString("Rejected");
        when(overlayService.apply(eq("DLWH"), eq(1), any())).thenReturn(rejected);
        when(overlayService.apply(eq("DLWH"), eq(2), any())).thenReturn(applied());
        IsupException error = assertThrows(IsupException.class, () -> service.apply("DLWH", "打包台1", "SF1"));
        assertTrue(error.getMessage().contains("channel=1"));
        assertTrue(error.getMessage().contains("Rejected"));
        verify(overlayService).apply(eq("DLWH"), eq(2), any());
    }

    @Test
    void forwardsTheSamePlatformOrderToBothCameras() {
        when(channelService.listChannels("DLWH")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        when(overlayService.apply(anyString(), anyInt(), any())).thenReturn(applied());
        service.apply("DLWH", "打包台1", "SF1", null, null, "321123456789");
        ArgumentCaptor<OverlayTextRequest> request = ArgumentCaptor.forClass(OverlayTextRequest.class);
        verify(overlayService, times(2)).apply(eq("DLWH"), anyInt(), request.capture());
        request.getAllValues().forEach(value -> assertEquals("321123456789", value.getPlatformOrderNo()));
    }

    private static OverlayTextResponse applied() {
        OverlayTextResponse response = new OverlayTextResponse();
        response.setApplied(true);
        return response;
    }
}
