package com.hiki.isup.service;

import com.hiki.isup.exception.IsupException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import org.springframework.test.util.ReflectionTestUtils;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverlayServiceTest {

    private static final String SPLIT_XML = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <VideoOverlay version="1.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
            <normalizedScreenSize><normalizedScreenWidth>704</normalizedScreenWidth>\
            <normalizedScreenHeight>576</normalizedScreenHeight></normalizedScreenSize>
            <attribute><transparent>false</transparent><flashing>false</flashing></attribute>
            <TextOverlayList size="8">
            <TextOverlay version="1.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
            <id>1</id>
            <enabled>true</enabled>
            <positionX>0</positionX>
            <positionY>480</positionY>
            <displayText>快递单号：</displayText>
            </TextOverlay>
            <TextOverlay version="1.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
            <id>2</id>
            <enabled>true</enabled>
            <positionX>80</positionX>
            <positionY>480</positionY>
            <displayText>123244848</displayText>
            </TextOverlay>
            </TextOverlayList>
            <DateTimeOverlay version="1.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
            <enabled>true</enabled>
            <positionX>0</positionX>
            <positionY>544</positionY>
            <dateStyle>CHR-YYYY-MM-DD</dateStyle>
            <timeStyle>24hour</timeStyle>
            <displayWeek>true</displayWeek>
            </DateTimeOverlay>
            <channelNameOverlay version="1.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
            <enabled>true</enabled>
            <positionX>0</positionX>
            <positionY>512</positionY>
            </channelNameOverlay>
            </VideoOverlay>
            """;

    @Mock
    private DeviceIsapiClient isapiClient;

    private OverlayService service;

    @BeforeEach
    void setUp() {
        service = new OverlayService(isapiClient);
    }

    @Test
    @DisplayName("单号写进前缀同一块：沿用设备坐标，单号块关闭")
    void waybillStaysOnPrefixLineAtTimeLeftEdge() {
        OverlayTextRequest request = new OverlayTextRequest();
        request.setWaybillNo("11323232");
        request.setDurationSec(0);

        String xml = OverlayService.rewrite(SPLIT_XML, request);

        assertTrue(xml.contains("<displayText>快递单号：11323232</displayText>"));
        assertFalse(xml.contains("<displayText>123244848</displayText>"));
        assertEquals(0, OverlayService.parseCoord(overlayBlock(xml, 1), "positionX"), 0.01);
        assertEquals(480, OverlayService.parseCoord(overlayBlock(xml, 1), "positionY"), 0.01);
        assertTrue(overlayBlock(xml, 2).contains("<enabled>false</enabled>"));
        assertTrue(xml.contains("<DateTimeOverlay"));
        assertTrue(xml.contains("<positionX>0</positionX>"));
    }

    @Test
    @DisplayName("请求里带了坐标也不改设备上的位置")
    void ignoresRequestedCoordinates() {
        OverlayTextRequest request = new OverlayTextRequest();
        request.setWaybillNo("11323232");
        request.setPositionX(99);
        request.setPositionY(16);

        String xml = OverlayService.rewrite(SPLIT_XML, request);

        assertEquals(0, OverlayService.parseCoord(overlayBlock(xml, 1), "positionX"), 0.01);
        assertEquals(480, OverlayService.parseCoord(overlayBlock(xml, 1), "positionY"), 0.01);
        assertFalse(xml.contains("<positionX>99</positionX>"));
        assertEquals(80, OverlayService.parseCoord(overlayBlock(xml, 2), "positionX"), 0.01);
    }

    @Test
    @DisplayName("通道名贴着时间时，三行纵坐标收成 544 / 512 / 480")
    void pinsEvenLineSpacing() {
        String tight = SPLIT_XML
                .replace("<positionY>512</positionY>", "<positionY>528</positionY>")
                .replace("<positionY>480</positionY>", "<positionY>464</positionY>");
        OverlayTextRequest request = new OverlayTextRequest();
        request.setWaybillNo("11323232");

        String xml = OverlayService.rewrite(tight, request);

        assertEquals(544, OverlayService.parseCoord(block(xml, "DateTimeOverlay"), "positionY"), 0.01);
        assertEquals(512, OverlayService.parseCoord(block(xml, "channelNameOverlay"), "positionY"), 0.01);
        assertEquals(480, OverlayService.parseCoord(overlayBlock(xml, 1), "positionY"), 0.01);
        assertEquals(0, OverlayService.parseCoord(block(xml, "channelNameOverlay"), "positionX"), 0.01);
    }

    @Test
    void pinsNamespacedVisualLayoutWithoutChangingUnmanagedFields() {
        String original = SPLIT_XML
                .replace("<positionY>544</positionY>", "<positionY>560</positionY>")
                .replace("<positionY>512</positionY>", "<positionY>528</positionY>")
                .replace("<VideoOverlay ", "<VideoOverlay xmlns:osd=\"http://www.hikvision.com/ver20/XMLSchema\" ")
                .replaceAll("<(/?)(DateTimeOverlay|channelNameOverlay|positionY)(?=[ >])", "<$1osd:$2");

        String xml = OverlayService.rewrite(original, OverlayTextRequest.external("NEXT"));

        assertEquals(544, OverlayService.parseCoord(block(xml, "osd:DateTimeOverlay"), "positionY"));
        assertEquals(512, OverlayService.parseCoord(block(xml, "osd:channelNameOverlay"), "positionY"));
        assertEquals(16, OverlayService.parseCoord(block(xml, "osd:DateTimeOverlay"), "positionX"));
        assertEquals(32, OverlayService.parseCoord(block(xml, "osd:channelNameOverlay"), "positionX"));
        assertEquals(480, OverlayService.parseCoord(overlayBlock(xml, 1), "positionY"));
        for (String name : new String[]{"osd:DateTimeOverlay", "osd:channelNameOverlay"}) {
            assertEquals(withoutPositions(block(original, name)).replace("<displayWeek>true</displayWeek>",
                    "<displayWeek>false</displayWeek>"), withoutPositions(block(xml, name)));
        }
        assertTrue(OverlayService.packingOverlayMatches(xml, xml));
    }

    @Test
    @DisplayName("下发时单号与前缀同行，不拆到 overlayId=2")
    void applyWritesSingleBlock() {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");

        OverlayTextRequest request = new OverlayTextRequest();
        request.setWaybillNo("11323232");
        request.setDurationSec(0);

        OverlayTextResponse response = service.apply("DLWH1xx", 2, request);

        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        verify(isapiClient).put(eq("DLWH1xx"),
                eq("/ISAPI/System/Video/inputs/channels/2/overlays"), xml.capture());

        String putXml = xml.getValue();
        assertEquals("快递单号：11323232", response.getDisplayText());
        assertTrue(putXml.contains("<id>1</id>"));
        assertTrue(putXml.contains("<displayText>快递单号：11323232</displayText>"));
        assertTrue(overlayBlock(putXml, 2).contains("<enabled>false</enabled>"));
        assertEquals(0, OverlayService.parseCoord(overlayBlock(putXml, 1), "positionX"), 0.01);
        assertFalse(putXml.contains("<displayText>123244848</displayText>"));
        assertFalse(response.isHideScheduled());
    }

    @Test
    @DisplayName("传入空单号只清数字，前缀保留，不自动倒计时")
    void emptyWaybillKeepsPrefix() {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");

        OverlayTextRequest request = new OverlayTextRequest();
        request.setWaybillNo("");

        OverlayTextResponse response = service.apply("DLWH1xx", 1, request);

        assertEquals("快递单号：", response.getDisplayText());
        assertFalse(response.isHideScheduled());
        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        verify(isapiClient).put(eq("DLWH1xx"),
                eq("/ISAPI/System/Video/inputs/channels/1/overlays"), xml.capture());
        String putXml = xml.getValue();
        assertTrue(putXml.contains("<displayText>快递单号：</displayText>"));
        assertFalse(putXml.contains("123244848"));
        assertEquals(0, OverlayService.parseCoord(overlayBlock(putXml, 1), "positionX"), 0.01);
        assertEquals(480, OverlayService.parseCoord(overlayBlock(putXml, 1), "positionY"), 0.01);
    }

    @Test
    @DisplayName("外部空单号只清数字，前缀保留且叠加块仍开启")
    void externalBlankWaybillKeepsPrefix() {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");

        OverlayTextResponse response = service.apply("DLWH1xx", 1, OverlayTextRequest.external(""));

        assertEquals("快递单号：", response.getDisplayText());
        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        verify(isapiClient).put(eq("DLWH1xx"),
                eq("/ISAPI/System/Video/inputs/channels/1/overlays"), xml.capture());
        String block = overlayBlock(xml.getValue(), 1);
        assertTrue(block.contains("<enabled>true</enabled>"));
        assertTrue(block.contains("<displayText>快递单号：</displayText>"));
    }

    @Test
    @DisplayName("只传单号时默认不自动清空")
    void waybillWithoutDurationDoesNotAutoHide() {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");

        OverlayTextRequest request = new OverlayTextRequest();
        request.setWaybillNo("11323232");

        OverlayTextResponse response = service.apply("DLWH1xx", 1, request);

        assertEquals("快递单号：11323232", response.getDisplayText());
        assertFalse(response.isHideScheduled());
        assertEquals(0, request.getDurationSec());
    }

    @Test
    @DisplayName("再次传入新单号会覆盖旧单号")
    void secondCallReplacesWaybill() {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");

        OverlayTextResponse first = service.apply("DLWH1xx", 1, waybillRequest("AAA", 0));
        OverlayTextResponse second = service.apply("DLWH1xx", 1, waybillRequest("BBB", 0));

        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        verify(isapiClient, timeout(1000).times(2)).put(eq("DLWH1xx"),
                eq("/ISAPI/System/Video/inputs/channels/1/overlays"), xml.capture());
        assertEquals("快递单号：AAA", first.getDisplayText());
        assertEquals("快递单号：BBB", second.getDisplayText());
        assertTrue(xml.getAllValues().get(0).contains("<displayText>快递单号：AAA</displayText>"));
        assertTrue(xml.getAllValues().get(1).contains("<displayText>快递单号：BBB</displayText>"));
    }

    @Test
    @DisplayName("带单号下发 1 秒后恢复成仅前缀")
    void waybillRestoresToEmptyPrefix() {
        String withNumber = OverlayService.rewrite(SPLIT_XML, waybillRequest("11323232", 0));
        when(isapiClient.get(eq("DLWH1xx"), anyString()))
                .thenReturn(SPLIT_XML, withNumber, withNumber);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");

        OverlayTextResponse response = service.apply("DLWH1xx", 1, waybillRequest("11323232", 1));

        assertEquals("快递单号：11323232", response.getDisplayText());
        assertTrue(response.isHideScheduled());

        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);
        // 恢复前缀也是一次真实写入，会受写入节流（默认 3 秒）约束，等待窗口需覆盖它
        verify(isapiClient, timeout(6000).times(2)).put(eq("DLWH1xx"),
                eq("/ISAPI/System/Video/inputs/channels/1/overlays"), xml.capture());
        assertTrue(xml.getAllValues().get(0).contains("<displayText>快递单号：11323232</displayText>"));
        assertTrue(xml.getAllValues().get(1).contains("<displayText>快递单号：</displayText>"));
        assertFalse(xml.getAllValues().get(1).contains("11323232"));
        assertEquals(0, OverlayService.parseCoord(overlayBlock(xml.getAllValues().get(0), 1), "positionX"), 0.01);
        assertEquals(0, OverlayService.parseCoord(overlayBlock(xml.getAllValues().get(1), 1), "positionX"), 0.01);
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<ResponseStatus/>", "<ResponseStatus><statusCode>1</statusCode>", "<other><statusCode>1</statusCode></other>", "<ResponseStatus><statusCode>invalid</statusCode></ResponseStatus>",
            "<ResponseStatus><statusCode>4</statusCode><statusString>Rejected</statusString></ResponseStatus>"})
    void unconfirmedOrRejectedWriteIsNotSuccessful(String reply) {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString())).thenReturn(reply);
        OverlayTextResponse result = service.apply("DLWH1xx", 1, waybillRequest("SF1", 0));
        assertFalse(result.isApplied());
        assertFalse(result.isWritten());
        assertFalse(result.isHideScheduled());
        verify(isapiClient, times(1)).put(eq("DLWH1xx"), anyString(), anyString());
    }

    @Test
    void writeTimeoutDoesNotTryAnotherPath() {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenThrow(IsupException.failed("ISAPI 透传", "DLWH1xx", null, null, "timeout"));
        assertThrows(IsupException.class, () -> service.apply("DLWH1xx", 1, waybillRequest("SF1", 0)));
        verify(isapiClient, times(1)).get(eq("DLWH1xx"), anyString());
        verify(isapiClient, times(1)).put(eq("DLWH1xx"), anyString(), anyString());
    }

    @Test
    void restoreTimeoutDoesNotWriteAnotherPath() {
        when(isapiClient.get(eq("DLWH1xx"), anyString())).thenReturn(SPLIT_XML);
        when(isapiClient.put(eq("DLWH1xx"), anyString(), anyString()))
                .thenThrow(IsupException.failed("ISAPI 透传", "DLWH1xx", null, null, "timeout"));
        assertThrows(IsupException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "restorePrefix", "DLWH1xx", 1, 1));
        verify(isapiClient, times(1)).get(eq("DLWH1xx"), anyString());
        verify(isapiClient, times(1)).put(eq("DLWH1xx"), anyString(), anyString());
    }

    @Test
    void readFailurePreservesCause() {
        when(isapiClient.get(eq("DLWH1xx"), anyString()))
                .thenThrow(IsupException.sdkFailed("ISAPI 透传", "DLWH1xx", null, null, 23));
        IsupException error = assertThrows(IsupException.class,
                () -> service.apply("DLWH1xx", 1, waybillRequest("SF1", 0)));
        assertTrue(error.getMessage().contains("errorCode=23"));
        verify(isapiClient, times(2)).get(eq("DLWH1xx"), anyString());
        verify(isapiClient, never()).put(anyString(), anyString(), anyString());
    }

    private static OverlayTextRequest waybillRequest(String waybillNo, int durationSec) {
        OverlayTextRequest request = new OverlayTextRequest();
        request.setWaybillNo(waybillNo);
        request.setDurationSec(durationSec);
        return request;
    }

    private static String block(String xml, String name) {
        int start = xml.indexOf("<" + name);
        int end = xml.indexOf("</" + name + ">", start);
        return xml.substring(start, end);
    }

    private static String overlayBlock(String xml, int overlayId) {
        int idIdx = xml.indexOf("<id>" + overlayId + "</id>");
        int start = xml.lastIndexOf("<TextOverlay", idIdx);
        int end = xml.indexOf("</TextOverlay>", idIdx) + "</TextOverlay>".length();
        return xml.substring(start, end);
    }

    private static String withoutPositions(String block) {
        return block.replaceAll("<(?:osd:)?position[XY]>.*?</(?:osd:)?position[XY]>", "");
    }
}
