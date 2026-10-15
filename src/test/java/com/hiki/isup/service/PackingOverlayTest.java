package com.hiki.isup.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PackingOverlayTest {
    private static final String XML = """
            <VideoOverlay><TextOverlayList size="8">
            <TextOverlay><id>1</id><enabled>true</enabled><positionX>32</positionX><positionY>480</positionY><displayText>快递单号：OLD</displayText></TextOverlay>
            <TextOverlay><id>2</id><enabled>true</enabled><positionX>80</positionX><positionY>480</positionY><displayText>OLD NAME</displayText></TextOverlay>
            <TextOverlay><id>3</id><enabled>true</enabled><positionX>32</positionX><positionY>416</positionY><displayText>OLD ADDRESS</displayText></TextOverlay>
            <TextOverlay><id>8</id><enabled>true</enabled><positionX>32</positionX><positionY>256</positionY><displayText>OLD LAST LINE</displayText></TextOverlay>
            </TextOverlayList><DateTimeOverlay><enabled>true</enabled><positionX>16</positionX><positionY>544</positionY><dateStyle>CHR-YYYY-MM-DD</dateStyle><timeStyle>24hour</timeStyle><displayWeek>false</displayWeek></DateTimeOverlay>
            <channelNameOverlay><enabled>true</enabled><positionX>32</positionX><positionY>512</positionY></channelNameOverlay></VideoOverlay>
            """;

    @ParameterizedTest
    @ValueSource(strings = {"<displayText/>", "<displayText />"})
    void deviceCanNormalizeClearedTextToEmptyElementsBeforeTheNextPackage(String emptyText) {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        AtomicReference<String> device = new AtomicReference<>(XML);
        when(client.get(eq("D"), anyString())).thenAnswer(call -> device.get());
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(call -> {
            device.set(call.<String>getArgument(2).replace("<displayText></displayText>", emptyText));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        OverlayService service = new OverlayService(client);
        ReflectionTestUtils.setField(service, "minWriteIntervalSec", 0);
        try {
            assertTrue(service.apply("D", 1, OverlayTextRequest.external("")).isApplied());
            assertTrue(device.get().contains(emptyText));
            OverlayTextResponse next = assertDoesNotThrow(() -> service.apply("D", 1, OverlayTextRequest.external("NEXT")));
            assertTrue(next.isApplied());
            assertTrue(device.get().contains("快递单号：NEXT"));
            assertTrue(device.get().contains("地址未获取"));
            assertFalse(device.get().contains("OLD"));
            verify(client, times(2)).put(eq("D"), anyString(), anyString());
        } finally { service.destroy(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "osd:"})
    void insertsFirstPackageInsideSelfClosingListAndPreservesItsNamespace(String prefix) throws Exception {
        String empty = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<VideoOverlay xmlns:osd=\"urn:osd\"><" + prefix + "TextOverlayList size=\"8\" custom=\"keep\" />"
                + "<DeviceOption mode=\"keep\">unchanged</DeviceOption></VideoOverlay>";
        String result = OverlayService.rewrite(empty, OverlayTextRequest.external("NEXT"));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(result)));
        var list = document.getElementsByTagNameNS("*", "TextOverlayList").item(0);
        var overlays = document.getElementsByTagNameNS("*", "TextOverlay");
        assertEquals(2, overlays.getLength());
        for (int i = 0; i < overlays.getLength(); i++) {
            assertSame(list, overlays.item(i).getParentNode());
            assertEquals(list.getNamespaceURI(), overlays.item(i).getNamespaceURI());
            var fields = overlays.item(i).getChildNodes();
            for (int j = 0; j < fields.getLength(); j++) {
                if (fields.item(j) instanceof org.w3c.dom.Element) {
                    assertEquals(list.getNamespaceURI(), fields.item(j).getNamespaceURI());
                }
            }
        }
        assertTrue(result.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(result.contains("custom=\"keep\""));
        assertTrue(result.contains("<DeviceOption mode=\"keep\">unchanged</DeviceOption>"));
    }

    @ParameterizedTest
    @MethodSource("invalidPackingHierarchy")
    void misplacedBusinessBlocksAndDuplicateListsNeverReachDevice(String invalid) {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenReturn(invalid);
        OverlayService service = new OverlayService(client);
        try {
            assertThrows(RuntimeException.class, () -> service.apply("D", 1, OverlayTextRequest.external("NEXT")));
            verify(client, never()).put(anyString(), anyString(), anyString());
        } finally { service.destroy(); }
    }

    @ParameterizedTest
    @MethodSource("invalidPackingHierarchy")
    void readbackDoesNotAcceptMisplacedBusinessBlocksOrDuplicateLists(String invalid) {
        assertFalse(OverlayService.packingOverlayMatches(XML, invalid));
    }

    static Stream<String> invalidPackingHierarchy() {
        String second = block(XML, 2);
        return Stream.of(
                XML.replace(second, "").replace("</VideoOverlay>", second + "</VideoOverlay>"),
                XML.replace(second, "<UnexpectedWrapper>" + second + "</UnexpectedWrapper>"),
                XML.replace("</VideoOverlay>", "<TextOverlayList size=\"8\"/></VideoOverlay>"));
    }

    @Test
    void writesCompleteFrozenRecipientInSeparateAlignedBlocks() throws Exception {
        String xml = OverlayService.rewrite(XML, request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","name":"Jane Doe","address":"100 Demo Road & Unit 2","city":"Los Angeles","state":"CA","postcode":"90001","country":"US"}
                """));
        assertTrue(xml.contains("快递单号：SF1"));
        assertTrue(xml.contains("收件人：Jane Doe"));
        assertTrue(xml.contains("100 Demo Road &amp; Unit 2"));
        assertTrue(xml.contains("Los Angeles"));
        assertTrue(xml.contains("90001"));
        assertTrue(xml.contains("US"));
        assertEquals(32, OverlayService.parseCoord(block(xml, 2), "positionX"));
        assertEquals(448, OverlayService.parseCoord(block(xml, 2), "positionY"));
        assertEquals(416, OverlayService.parseCoord(block(xml, 3), "positionY"));
        assertFalse(xml.contains("OLD"));
        assertTrue(block(xml, 8).contains("<enabled>false</enabled>"));
        assertNull(OverlayService.findChangedOverlayBlock(XML, xml));
    }

    @Test
    void clearingRemovesAllAddressLinesEvenFromAnOldClient() {
        String xml = OverlayService.rewrite(XML, OverlayTextRequest.external(""));
        assertFalse(xml.contains("OLD"));
        assertTrue(xml.contains("快递单号："));
        for (int id : new int[]{2, 3, 8}) {
            assertTrue(block(xml, id).contains("<enabled>false</enabled>"));
        }
    }

    @Test
    void missingRecipientShowsUnavailableAndNeverRetainsOldAddress() {
        String xml = OverlayService.rewrite(XML, OverlayTextRequest.external("NEW"));
        assertTrue(xml.contains("地址未获取"));
        assertFalse(xml.contains("OLD"));
    }

    @Test
    void rejectsRecipientBelongingToAnotherWaybill() throws Exception {
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"SF2","applyRecordId":"R1","address":"Other Road","country":"US"}
                """);
        assertThrows(IllegalArgumentException.class, () -> OverlayService.rewrite(XML, request));
    }

    @Test
    void rejectsAddressWithoutApplicationEvidence() throws Exception {
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"SF1","address":"Current OMS Road","country":"US"}
                """);
        assertThrows(IllegalArgumentException.class, () -> OverlayService.rewrite(XML, request));
    }

    @Test
    void tooLongAddressIsRejectedInsteadOfSilentlyTruncated() throws Exception {
        OverlayTextRequest request = request("SF1", "{\"waybillNo\":\"SF1\",\"applyRecordId\":\"R1\",\"country\":\"US\",\"address\":\"" + "LONG ROAD ".repeat(100) + "\"}");
        assertThrows(IllegalArgumentException.class, () -> OverlayService.rewrite(XML, request));
    }

    @Test
    void deviceWithTooFewBlocksRejectsFullAddress() throws Exception {
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","name":"Jane","address":"Demo Road","country":"US"}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> OverlayService.rewrite(XML.replace("size=\"8\"", "size=\"2\""), request));
    }

    @Test
    void clearForPreviousWaybillLeavesNewWaybillUntouched() throws Exception {
        OverlayTextRequest clear = request("", "null", "\"expectedWaybillNo\":\"PREVIOUS\",");
        assertEquals(XML, OverlayService.rewrite(XML, clear));
    }

    @Test
    void matchingClearRemovesWholeGroup() throws Exception {
        OverlayTextRequest clear = request("", "null", "\"expectedWaybillNo\":\"OLD\",");
        assertFalse(OverlayService.rewrite(XML, clear).contains("OLD"));
    }

    @Test
    void matchingClearDecodesNumericEntitiesAndCdata() throws Exception {
        OverlayTextRequest clear = request("", "null", "\"expectedWaybillNo\":\"OLD\",");
        for (String equivalent : new String[]{"&#x5FEB;&#x9012;&#x5355;&#x53F7;&#xFF1A;OLD",
                "<![CDATA[快递单号：OLD]]>"}) {
            String encoded = XML.replace("快递单号：OLD", equivalent);
            assertFalse(OverlayService.rewrite(encoded, clear).contains("OLD"));
        }
    }

    @Test
    void malformedCurrentNumberCannotBeReportedAsSuccessfulSkippedClear() throws Exception {
        OverlayTextRequest clear = request("", "null", "\"expectedWaybillNo\":\"OLD\",");
        String malformed = XML.replace("<displayText>快递单号：OLD</displayText>", "");
        assertThrows(IllegalStateException.class, () -> OverlayService.rewrite(malformed, clear));
    }

    @Test
    void guardedClearNeverWritesAnotherPackageEvenWhenSkipUnchangedIsDisabled() throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenReturn(XML);
        OverlayService service = new OverlayService(client);
        ReflectionTestUtils.setField(service, "skipUnchanged", false);
        try {
            OverlayTextResponse result = service.apply("D", 1,
                    request("", "null", "\"expectedWaybillNo\":\"PREVIOUS\","));
            assertTrue(result.isApplied());
            assertFalse(result.isWritten());
            assertTrue(result.isSuperseded());
            verify(client, never()).put(anyString(), anyString(), anyString());
        } finally { service.destroy(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void matchingWaybillClearRemovesAddressAndRequiresReadback(boolean deviceStoresWrite) {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        AtomicReference<String> device = new AtomicReference<>(XML);
        when(client.get(eq("D"), anyString())).thenAnswer(call -> device.get());
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(call -> {
            if (deviceStoresWrite) device.set(call.getArgument(2));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextRequest clear = OverlayTextRequest.external("");
            clear.setExpectedWaybillNo("OLD");
            OverlayTextResponse result = service.apply("D", 1, clear);
            assertFalse(result.isSuperseded());
            assertTrue(result.isWritten());
            assertEquals(deviceStoresWrite, result.isApplied());
            assertFalse(result.getRequestXml().contains("OLD"));
            assertTrue(block(result.getRequestXml(), 1).contains("<displayText>快递单号：</displayText>"));
            for (int id : new int[]{2, 3, 8}) {
                assertTrue(block(result.getRequestXml(), id).contains("<enabled>false</enabled>"));
                assertTrue(block(result.getRequestXml(), id).contains("<displayText></displayText>"));
            }
            if (!deviceStoresWrite) assertTrue(result.getDeviceStatusString().contains("回读与目标不一致"));
            verify(client, times(1)).put(anyString(), anyString(), anyString());
            verify(client, times(2)).get(eq("D"), anyString());
        } finally { service.destroy(); }
    }

    @Test
    void successfulWriteIsVerifiedAndRepeatedContentSkipsPut() throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        AtomicReference<String> device = new AtomicReference<>(XML);
        when(client.get(eq("D"), anyString())).thenAnswer(call -> device.get());
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(call -> {
            device.set(call.getArgument(2));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextRequest request = request("SF1", """
                    {"waybillNo":"SF1","applyRecordId":"R1","name":"Jane","address":"Demo Road","country":"US"}
                    """);
            assertTrue(service.apply("D", 1, request).isApplied());
            OverlayTextResponse repeated = service.apply("D", 1, request);
            assertTrue(repeated.isApplied());
            assertFalse(repeated.isWritten());
            verify(client, times(1)).put(anyString(), anyString(), anyString());
        } finally { service.destroy(); }
    }

    @Test
    void shorterRecipientRemovesPreviousLongLines() throws Exception {
        String longAddress = OverlayService.rewrite(XML, request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","name":"Jane","address":"100 Demonstration Road Building Two Unit Six","city":"Long City","state":"CA","postcode":"90001","country":"US"}
                """));
        String shortAddress = OverlayService.rewrite(longAddress, request("SF2", """
                {"waybillNo":"SF2","applyRecordId":"R2","address":"New Road","country":"US"}
                """));
        assertFalse(shortAddress.contains("Demonstration"));
        assertFalse(shortAddress.contains("Jane"));
        assertFalse(shortAddress.contains("90001"));
        assertTrue(block(shortAddress, 4).contains("<enabled>false</enabled>"));
    }

    @Test
    void wrappingPreservesEveryAddressCharacterIncludingNonAscii() throws Exception {
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","address":"北京市朝阳区示例路第一大厦二单元一百零八号","country":"CN"}
                """);
        request.setAddressLineWidth(24);
        String xml = OverlayService.rewrite(XML, request);
        StringBuilder address = new StringBuilder();
        for (int id = 2; id <= 8; id++) {
            Matcher text = Pattern.compile("<displayText>(.*?)</displayText>").matcher(block(xml, id));
            if (text.find()) address.append(text.group(1));
        }
        assertTrue(address.toString().contains("北京市朝阳区示例路第一大厦二单元一百零八号"));
    }

    @Test
    void unsupportedDeviceEncodingCannotSilentlyReplaceAddressCharacters() throws Exception {
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","address":"Demo 😀 Road","country":"US"}
                """);
        assertThrows(IllegalArgumentException.class, () -> OverlayService.rewrite(
                "<?xml version=\"1.0\" encoding=\"GB2312\"?>" + XML, request));
    }

    @Test
    void readbackAcceptsNumericCoordinateNormalizationButRejectsLostCharacters() throws Exception {
        String target = OverlayService.rewrite(XML, request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","address":"Demo & Road","country":"US"}
                """));
        assertTrue(OverlayService.packingOverlayMatches(target, target.replace("<positionX>32<", "<positionX>32.000000<")));
        assertFalse(OverlayService.packingOverlayMatches(target, target.replace("Demo &amp; Road", "Demo")));
        assertFalse(OverlayService.packingOverlayMatches(target, ""));
    }

    @Test
    void malformedExistingBlocksCannotMixNewWaybillWithOldAddress() throws Exception {
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","address":"New Road","country":"US"}
                """);
        for (int id : new int[]{1, 2}) {
            String malformed = XML.replace(block(XML, id),
                    block(XML, id).replace("<enabled>true</enabled>", ""));
            assertThrows(IllegalStateException.class, () -> OverlayService.rewrite(malformed, request));
        }
    }

    @Test
    void duplicateAddressBlocksAreRejectedBeforeWriting() throws Exception {
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"SF1","applyRecordId":"R1","address":"New Road","country":"US"}
                """);
        String malformed = XML.replace("</TextOverlayList>", block(XML, 2) + "</TextOverlayList>");
        assertThrows(IllegalStateException.class, () -> OverlayService.rewrite(malformed, request));
    }

    @Test
    void malformedRecipientStillClearsStaleAddressButReportsFailure() throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenReturn(XML);
        when(client.put(eq("D"), anyString(), anyString())).thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextResponse response = service.apply("D", 1, request("SF1", """
                    {"waybillNo":"WRONG","applyRecordId":"R1","address":"WRONG ADDRESS","country":"US"}
                    """));
            ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
            verify(client).put(eq("D"), anyString(), written.capture());
            assertFalse(written.getValue().contains("OLD"));
            assertFalse(written.getValue().contains("WRONG ADDRESS"));
            assertTrue(written.getValue().contains("地址未获取"));
            assertFalse(response.isApplied());
        } finally {
            service.destroy();
        }
    }

    @Test
    void deviceAcceptanceWithoutMatchingReadbackIsNotAddressSuccess() throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenReturn(XML);
        when(client.put(eq("D"), anyString(), anyString())).thenReturn("<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextResponse response = service.apply("D", 1, request("SF1", """
                    {"waybillNo":"SF1","applyRecordId":"R1","address":"Demo Road","country":"US"}
                    """));
            assertFalse(response.isApplied());
            verify(client, times(1)).put(eq("D"), anyString(), anyString());
        } finally {
            service.destroy();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidWaybills")
    void invalidWaybillClearsAllOldBusinessTextWithoutTruncation(String xml, String number, int width) {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        AtomicReference<String> device = new AtomicReference<>(xml);
        when(client.get(eq("D"), anyString())).thenAnswer(call -> device.get());
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(call -> {
            device.set(call.getArgument(2));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        OverlayService service = new OverlayService(client);
        ReflectionTestUtils.setField(service, "addressLineWidth", width);
        try {
            OverlayTextResponse response = assertDoesNotThrow(() -> service.apply("D", 1,
                    OverlayTextRequest.external(number)));
            assertFalse(response.isApplied());
            assertTrue(response.isWritten());
            assertTrue(response.getDeviceStatusString().contains("快递单号"));
            assertEquals("快递单号：", response.getDisplayText());
            assertTrue(block(device.get(), 1).contains("<displayText>快递单号：</displayText>"));
            for (int id : new int[]{2, 3, 8}) {
                assertTrue(block(device.get(), id).contains("<enabled>false</enabled>"));
                assertTrue(block(device.get(), id).contains("<displayText></displayText>"));
            }
            assertFalse(device.get().contains("OLD"));
            assertFalse(device.get().contains(number));
            verify(client, times(1)).put(eq("D"), anyString(), anyString());
        } finally { service.destroy(); }
    }

    private static Stream<Arguments> invalidWaybills() {
        return Stream.of(
                Arguments.of("<?xml version=\"1.0\" encoding=\"GB2312\"?>" + XML, "SF😀", 44),
                Arguments.of(XML, "1".repeat(35), 44),
                Arguments.of(XML, "国".repeat(17), 100),
                Arguments.of(XML, "SF" + (char) 1 + "1", 44),
                Arguments.of(XML, "SF" + (char) 0xd800, 44),
                Arguments.of(XML, "SF1\n2", 44));
    }

    @Test
    void completeWaybillAtDisplayAndByteBoundariesRemainsUnchanged() {
        OverlayTextRequest widthBoundary = OverlayTextRequest.external("1".repeat(34));
        assertTrue(OverlayService.rewrite(XML, widthBoundary).contains("快递单号：" + "1".repeat(34)));
        OverlayTextRequest byteBoundary = OverlayTextRequest.external("国".repeat(16) + "1");
        byteBoundary.setAddressLineWidth(100);
        assertTrue(OverlayService.rewrite(XML, byteBoundary).contains("快递单号：" + "国".repeat(16) + "1"));
    }

    @ParameterizedTest
    @MethodSource("invalidXmlBusinessFields")
    void invalidXmlInBusinessFieldsClearsOldAddressThroughFallback(String field, char invalid) throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        AtomicReference<String> device = new AtomicReference<>(XML);
        when(client.get(eq("D"), anyString())).thenAnswer(call -> device.get());
        when(client.put(eq("D"), anyString(), anyString())).thenAnswer(call -> {
            device.set(call.getArgument(2));
            return "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
        });
        String recipient = "{\"waybillNo\":\"SF1\",\"applyRecordId\":\"R1\",\"name\":\"Jane\","
                + "\"address\":\"Demo Road\",\"postcode\":\"123\",\"country\":\"DE\"}";
        var data = new ObjectMapper().readTree(recipient);
        if (!field.equals("platformOrderNo")) {
            ((ObjectNode) data).put(field, "Invalid" + invalid);
        }
        OverlayTextRequest request = request("SF1", data.toString());
        request.setPlatformOrderNo(field.equals("platformOrderNo") ? "Order" + invalid : "ORDER1");
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextResponse result = assertDoesNotThrow(() -> service.apply("D", 1, request));
            assertFalse(result.isApplied());
            assertTrue(result.isWritten());
            assertTrue(result.getDeviceStatusString().contains("XML"));
            assertFalse(device.get().contains("OLD"));
            assertFalse(device.get().contains(String.valueOf(invalid)));
            assertTrue(device.get().contains("快递单号：SF1"));
            assertTrue(device.get().contains("地址未获取"));
            verify(client, times(1)).put(eq("D"), anyString(), anyString());
        } finally { service.destroy(); }
    }

    static Stream<Arguments> invalidXmlBusinessFields() {
        return Stream.of("name", "address", "platformOrderNo", "postcode", "country")
                .flatMap(field -> Stream.of(Arguments.of(field, (char) 0xfffe), Arguments.of(field, (char) 0xffff)));
    }

    @Test
    void xmlSpecialCharactersInWaybillAreEscapedAndStillMatchGuardedClear() {
        String number = "SF<&1";
        String shown = OverlayService.rewrite(XML, OverlayTextRequest.external(number));
        assertTrue(block(shown, 1).contains("快递单号：SF&lt;&amp;1"));
        OverlayTextRequest clear = OverlayTextRequest.external("");
        clear.setExpectedWaybillNo(number);
        assertTrue(block(OverlayService.rewrite(shown, clear), 1).contains("<displayText>快递单号：</displayText>"));
    }

    @Test
    void invalidWaybillDoesNotBypassMalformedDeviceXmlValidation() {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenReturn(XML.replace("</TextOverlayList>",
                block(XML, 2) + "</TextOverlayList>"));
        OverlayService service = new OverlayService(client);
        try {
            assertThrows(RuntimeException.class, () -> service.apply("D", 1,
                    OverlayTextRequest.external("1".repeat(35))));
            verify(client, never()).put(anyString(), anyString(), anyString());
        } finally { service.destroy(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"设备字幕回读与目标不一致", "设备字幕回读失败", "Device Busy", "Device Busy + readback"})
    void fallbackKeepsLayoutFailureAndActualDeviceFailure(String deviceFailure) throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        boolean readbackFails = deviceFailure.equals("设备字幕回读失败") || deviceFailure.endsWith(" + readback");
        if (readbackFails) {
            when(client.get(eq("D"), anyString())).thenReturn(XML).thenThrow(new IllegalStateException("readback unavailable"));
        } else {
            when(client.get(eq("D"), anyString())).thenReturn(XML);
        }
        when(client.put(eq("D"), anyString(), anyString())).thenReturn(deviceFailure.startsWith("Device Busy")
                ? "<ResponseStatus><statusCode>4</statusCode><statusString>Device Busy</statusString></ResponseStatus>"
                : "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>");
        OverlayService service = new OverlayService(client);
        try {
            OverlayTextResponse response = service.apply("D", 1, request("SF1", """
                    {"waybillNo":"WRONG","applyRecordId":"R1","address":"Wrong Road","country":"US"}
                    """));
            assertFalse(response.isApplied());
            assertTrue(response.getDeviceStatusString().contains("身份不一致"));
            assertTrue(response.getDeviceStatusString().contains(deviceFailure.replace(" + readback", "")));
            if (readbackFails) {
                assertTrue(response.getDeviceStatusString().contains("设备字幕回读失败"));
                assertTrue(response.getDeviceStatusString().contains("readback unavailable"));
            }
            verify(client, times(1)).put(eq("D"), anyString(), anyString());
        } finally { service.destroy(); }
    }

    @Test
    void fallbackPutExceptionKeepsBothCausesWithoutRetrying() throws Exception {
        DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        when(client.get(eq("D"), anyString())).thenReturn(XML);
        when(client.put(eq("D"), anyString(), anyString())).thenThrow(new IllegalStateException("SDK PUT timeout"));
        OverlayService service = new OverlayService(client);
        OverlayTextRequest request = request("SF1", """
                {"waybillNo":"WRONG","applyRecordId":"R1","address":"Wrong Road","country":"US"}
                """);
        try {
            RuntimeException error = assertThrows(RuntimeException.class, () -> service.apply("D", 1, request));
            assertTrue(error.getMessage().contains("身份不一致"));
            assertTrue(error.getMessage().contains("SDK PUT timeout"));
            verify(client, times(1)).put(eq("D"), anyString(), anyString());
            verify(client, times(1)).get(eq("D"), anyString());
        } finally { service.destroy(); }
    }

    private static OverlayTextRequest request(String no, String recipient) throws Exception {
        return request(no, recipient, "");
    }

    private static OverlayTextRequest request(String no, String recipient, String extra) throws Exception {
        return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue("{" + extra + "\"packingOverlay\":true,\"waybillNo\":\"" + no + "\",\"recipient\":" + recipient + "}", OverlayTextRequest.class);
    }

    private static String block(String xml, int id) {
        Matcher matcher = Pattern.compile("<TextOverlay>.*?</TextOverlay>", Pattern.DOTALL).matcher(xml);
        while (matcher.find()) {
            if (matcher.group().contains("<id>" + id + "</id>")) return matcher.group();
        }
        return "";
    }
}
