package com.hiki.isup.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformOrderOverlayTest {
    private static final String XML = "<VideoOverlay><TextOverlayList size=\"8\"></TextOverlayList>"
            + "<DateTimeOverlay><enabled>true</enabled><positionX>16</positionX><positionY>544</positionY><dateStyle>CHR-YYYY-MM-DD</dateStyle><timeStyle>24hour</timeStyle><displayWeek>false</displayWeek></DateTimeOverlay>"
            + "<channelNameOverlay><enabled>true</enabled><positionX>32</positionX><positionY>512</positionY></channelNameOverlay></VideoOverlay>";
    private static final Map<String, String> RECIPIENT = Map.of(
            "waybillNo", "SF1", "applyRecordId", "R1", "name", "Jane Doe", "address", "100 Demo Road Apt 2",
            "city", "Los Angeles", "state", "CA", "postcode", "90001", "country", "US");

    @Test
    void platformOrderAppearsBelowWaybillWithContinuousAlignedRows() throws Exception {
        String xml = OverlayService.rewrite(XML, request("SF1", "321123456789", RECIPIENT));
        assertTrue(block(xml, 1).contains("快递单号：SF1"));
        assertTrue(block(xml, 2).contains("平台订单号：321123456789"));
        assertTrue(block(xml, 3).contains("收件人：Jane Doe"));
        assertTrue(block(xml, 6).contains("邮编：90001  国家：US"));
        for (int id = 1; id <= 6; id++) {
            assertEquals(32, OverlayService.parseCoord(block(xml, id), "positionX"));
            assertEquals(480 - 32 * (id - 1), OverlayService.parseCoord(block(xml, id), "positionY"));
        }
        assertNull(OverlayService.findChangedOverlayBlock(XML, xml));
    }

    @Test
    void packingLayoutNormalizesShiftedDeviceCoordinatesForVisualAlignment() throws Exception {
        String xml = OverlayService.rewrite(shiftedXml(), request("SF1", "321123456789", RECIPIENT));

        assertEquals(16, OverlayService.parseCoord(systemBlock(xml, "DateTimeOverlay"), "positionX"));
        assertEquals(544, OverlayService.parseCoord(systemBlock(xml, "DateTimeOverlay"), "positionY"));
        assertEquals(32, OverlayService.parseCoord(systemBlock(xml, "channelNameOverlay"), "positionX"));
        assertEquals(512, OverlayService.parseCoord(systemBlock(xml, "channelNameOverlay"), "positionY"));
        for (int id = 1; id <= 6; id++) {
            assertEquals(32, OverlayService.parseCoord(block(xml, id), "positionX"));
            assertEquals(480 - 32 * (id - 1), OverlayService.parseCoord(block(xml, id), "positionY"));
        }
    }

    @Test
    void applyKeepsAuthorizedVisualCoordinateChangesInTheDeviceRequest() throws Exception {
        AtomicReference<String> device = new AtomicReference<>(shiftedXml());
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenAnswer(ignored -> device.get());
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(invocation -> {
            device.set(invocation.getArgument(2));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextResponse response = service.apply("D", 1,
                    request("SF1", "321123456789", RECIPIENT));

            assertTrue(response.isApplied());
            assertEquals(16, OverlayService.parseCoord(systemBlock(device.get(), "DateTimeOverlay"), "positionX"));
            assertEquals(32, OverlayService.parseCoord(systemBlock(device.get(), "channelNameOverlay"), "positionX"));
            assertEquals(32, OverlayService.parseCoord(block(device.get(), 1), "positionX"));
        } finally {
            service.destroy();
        }
    }

    @Test
    void readBackRejectsDeviceThatDoesNotKeepTheVisualAlignmentCoordinates() throws Exception {
        String target = OverlayService.rewrite(shiftedXml(), request("SF1", "321123456789", RECIPIENT));
        String shiftedReadBack = target
                .replace("<positionX>16</positionX><positionY>544</positionY>",
                        "<positionX>96</positionX><positionY>544</positionY>")
                .replace("<positionX>32</positionX><positionY>512</positionY>",
                        "<positionX>0</positionX><positionY>512</positionY>");

        assertFalse(OverlayService.packingOverlayMatches(target, shiftedReadBack));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " 门牌：123"})
    void platformOrderAndLongGermanAddressStillFitWithoutDiscardingFields(String houseNumber) throws Exception {
        Map<String, String> recipient = Map.of("waybillNo", "SF1", "applyRecordId", "R1",
                "name", "Maximilian Alexander Mustermann", "country", "DE", "state", "Baden-Wurttemberg",
                "city", "Villingen-Schwenningen", "district", "Schwenningen", "postcode", "78054",
                "address", "Internationale Handelsgesellschaft Musterstrasse 123 Hinterhaus 4. Obergeschoss Wohnung 16" + houseNumber);
        List<String> lines = assertDoesNotThrow(() -> PackingOverlayLayout.lines(XML, request("SF1", "321123456789", recipient)));
        assertEquals(7, lines.size());
        assertEquals("平台订单号：321123456789", lines.getFirst());
        String displayed = String.join(" ", lines);
        assertTrue(displayed.contains(recipient.get("name")));
        assertTrue(displayed.contains(recipient.get("address")));
        assertTrue(displayed.contains("Schwenningen, Villingen-Schwenningen, Baden-Wurttemberg"));
        assertEquals("Baden-Wurttemberg  邮编：78054  国家：DE", lines.getLast());
    }

    @Test
    void missingAddressStillDisplaysPlatformOrderAndUnavailableStatus() throws Exception {
        assertEquals(List.of("平台订单号：321123456789", "地址未获取"),
                PackingOverlayLayout.lines(XML, request("SF1", "321123456789", null)));
    }

    @Test
    void longPlatformOrderIsWrappedCompletelyWithoutChangingTheWaybill() throws Exception {
        String number = "ORDER" + "1234567890".repeat(6);
        List<String> lines = PackingOverlayLayout.lines(XML, request("SF1", number, null));
        assertEquals("平台订单号：" + number, String.join("", lines.subList(0, lines.size() - 1)));
        assertEquals("地址未获取", lines.getLast());
        assertTrue(lines.size() > 2);
    }

    @Test
    void unsupportedPlatformEncodingIsRejectedInsteadOfReplacement() throws Exception {
        OverlayTextRequest request = request("SF1", "ORDER😀", RECIPIENT);
        assertThrows(IllegalArgumentException.class, () -> PackingOverlayLayout.lines(
                "<?xml version=\"1.0\" encoding=\"GB2312\"?>" + XML, request));
    }

    @Test
    void capacityCannotBeBypassedByAddingOrCompactingPlatformRows() throws Exception {
        OverlayTextRequest request = request("SF1", "321123456789", RECIPIENT);
        assertThrows(IllegalArgumentException.class,
                () -> PackingOverlayLayout.lines(XML.replace("size=\"8\"", "size=\"3\""), request));
        assertThrows(IllegalArgumentException.class,
                () -> PackingOverlayLayout.lines(XML, request("SF1", "ORDER".repeat(100), RECIPIENT)));
    }

    @Test
    void invalidRecipientFallbackKeepsValidPlatformOrderAndRemovesOldAddress() throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenReturn(XML);
        when(client.put(eq("D"), anyString(), anyString())).thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextResponse result = service.apply("D", 1,
                    request("SF1", "321123456789", Map.of("waybillNo", "WRONG", "applyRecordId", "R0", "address", "Wrong Road", "country", "US")));
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(client).put(eq("D"), anyString(), body.capture());
            assertTrue(body.getValue().contains("平台订单号：321123456789"));
            assertTrue(body.getValue().contains("地址未获取"));
            assertFalse(body.getValue().contains("Wrong Road"));
            assertFalse(result.isApplied());
        } finally { service.destroy(); }
    }

    @Test
    void outboundClearAndLegacyNextPackageRemoveTheOldPlatformOrder() throws Exception {
        String shown = OverlayService.rewrite(XML, request("SF1", "321123456789", RECIPIENT));
        OverlayTextRequest clear = request("", "321123456789", null);
        clear.setExpectedWaybillNo("SF1");
        String cleared = OverlayService.rewrite(shown, clear);
        assertFalse(cleared.contains("321123456789"));
        assertFalse(cleared.contains("Jane Doe"));
        assertFalse(cleared.contains("Demo Road"));
        assertTrue(cleared.contains("快递单号："));
        String legacyNext = OverlayService.rewrite(shown, request("SF2", null, null));
        assertFalse(legacyNext.contains("平台订单号"));
        assertFalse(legacyNext.contains("321123456789"));
    }

    @Test
    void unsupportedPlatformStillReplacesPreviousPackageAndCanBeCleared() throws Exception {
        assertSafeFallback("<?xml version=\"1.0\" encoding=\"GB2312\"?>" + previousPackage(), "ORDER😀", 8);
    }

    @Test
    void oversizedPlatformStillReplacesPreviousPackageAndCanBeCleared() throws Exception {
        assertSafeFallback(previousPackage(), "ORDER".repeat(100), 8);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void limitedCapacityStillRemovesAllPreviousPackageFields(int capacity) throws Exception {
        assertSafeFallback(previousPackage().replace("size=\"8\"", "size=\"" + capacity + "\""),
                "321123456789", capacity);
    }

    @Test
    void platformFailureReplacesAndClearsBothCameras() throws Exception {
        ChannelService channels = mock(ChannelService.class);
        when(channels.listChannels("D")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        String previous = previousPackage();
        Map<String, String> device = new HashMap<>();
        when(client.get(eq("D"), anyString())).thenAnswer(call -> device.getOrDefault(call.getArgument(1), previous));
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(call -> {
            device.put(call.getArgument(1), call.getArgument(2));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        OverlayService overlays = new OverlayService(client);
        ReflectionTestUtils.setField(overlays, "minWriteIntervalSec", 0);
        StationOverlayService station = new StationOverlayService(channels, overlays);
        try {
            assertThrows(IsupException.class, () -> station.apply("D", "打包台1", "SF1", null, null, "ORDER".repeat(100)));
            assertEquals(2, device.size());
            device.values().forEach(xml -> {
                assertTrue(xml.contains("快递单号：SF1"));
                assertTrue(xml.contains("地址未获取"));
                assertFalse(xml.contains("OLD"));
            });
            assertDoesNotThrow(() -> station.apply("D", "打包台1", "", null, "SF1", null));
            device.values().forEach(PlatformOrderOverlayTest::assertBusinessFieldsCleared);
            verify(client, times(4)).put(eq("D"), anyString(), anyString());
        } finally { overlays.destroy(); }
    }

    private static void assertSafeFallback(String xml, String platform, int capacity) throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        AtomicReference<String> device = new AtomicReference<>(xml);
        when(client.get(eq("D"), anyString())).thenAnswer(call -> device.get());
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(call -> {
            device.set(call.getArgument(2));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        OverlayService service = new OverlayService(client);
        ReflectionTestUtils.setField(service, "minWriteIntervalSec", 0);
        try {
            OverlayTextResponse result = assertDoesNotThrow(() -> service.apply("D", 1, request("SF1", platform, RECIPIENT)));
            assertTrue(result.isWritten());
            assertFalse(result.isApplied());
            assertNotNull(result.getDeviceStatusString());
            assertTrue(device.get().contains("快递单号：SF1"));
            assertEquals(capacity >= 2, device.get().contains("地址未获取"));
            assertFalse(device.get().contains("OLD"));
            assertFalse(device.get().contains("平台订单号"));
            assertFalse(device.get().contains("Demo Road"));
            OverlayTextRequest clear = request("", null, null);
            clear.setExpectedWaybillNo("SF1");
            assertTrue(service.apply("D", 1, clear).isApplied());
            assertBusinessFieldsCleared(device.get());
            verify(client, times(2)).put(eq("D"), anyString(), anyString());
        } finally { service.destroy(); }
    }

    private static String previousPackage() throws Exception {
        String xml = OverlayService.rewrite(XML, request("OLD", "OLD-PLATFORM", Map.of(
                "waybillNo", "OLD", "applyRecordId", "R0", "name", "OLD NAME", "address", "OLD ROAD", "country", "US")));
        return xml.replace("</TextOverlayList>", "<TextOverlay><id>8</id><enabled>true</enabled>"
                + "<positionX>32</positionX><positionY>256</positionY><displayText>OLD LAST LINE</displayText>"
                + "</TextOverlay></TextOverlayList>");
    }

    private static void assertBusinessFieldsCleared(String xml) {
        assertTrue(block(xml, 1).contains("<displayText>快递单号：</displayText>"));
        for (int id = 2; id <= 8; id++) {
            String line = block(xml, id);
            if (!line.isEmpty()) {
                assertTrue(line.contains("<enabled>false</enabled>"));
                assertTrue(line.contains("<displayText></displayText>"));
            }
        }
        assertFalse(xml.contains("OLD"));
        assertFalse(xml.contains("SF1"));
    }

    private static OverlayTextRequest request(String no, String platform, Map<String, String> recipient) throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var json = mapper.createObjectNode().put("packingOverlay", true).put("waybillNo", no);
        if (platform != null) json.put("platformOrderNo", platform);
        json.set("recipient", mapper.valueToTree(recipient));
        return mapper.treeToValue(json, OverlayTextRequest.class);
    }

    private static String block(String xml, int id) {
        Matcher matcher = Pattern.compile("<TextOverlay>.*?</TextOverlay>", Pattern.DOTALL).matcher(xml);
        while (matcher.find()) if (matcher.group().contains("<id>" + id + "</id>")) return matcher.group();
        return "";
    }

    private static String systemBlock(String xml, String name) {
        Matcher matcher = Pattern.compile("<" + name + ">.*?</" + name + ">", Pattern.DOTALL).matcher(xml);
        return matcher.find() ? matcher.group() : "";
    }

    private static String shiftedXml() {
        return XML
                .replace("<TextOverlayList size=\"8\"></TextOverlayList>", """
                        <TextOverlayList size="8"><TextOverlay>
                        <id>1</id><enabled>true</enabled><positionX>80</positionX><positionY>464</positionY>
                        <displayText>快递单号：OLD</displayText></TextOverlay></TextOverlayList>
                        """)
                .replace("<positionX>16</positionX><positionY>544</positionY>",
                        "<positionX>96</positionX><positionY>560</positionY>")
                .replace("<positionX>32</positionX><positionY>512</positionY>",
                        "<positionX>0</positionX><positionY>528</positionY>");
    }
}
