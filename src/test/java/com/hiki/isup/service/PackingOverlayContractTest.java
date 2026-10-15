package com.hiki.isup.service;

import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.request.OverlayRecipient;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** SHOW 校验完整显示格式，CLEAR 只核验业务内容清空。 */
class PackingOverlayContractTest {
    private static final String DEVICE = "D";
    private static final String PREFIX = "快递单号：";
    private static final String ACCEPTED = "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>";
    private static final String SYSTEM = """
            <DateTimeOverlay custom="keep-time"><enabled>false</enabled><positionX>80</positionX><positionY>96</positionY>
            <dateStyle custom="keep-date">MM-DD-YYYY</dateStyle><timeStyle>12hour</timeStyle><displayWeek>true</displayWeek>
            <fontSize>16</fontSize><DeviceOption mode="keep">time-extension</DeviceOption></DateTimeOverlay>
            <channelNameOverlay custom="keep-channel"><enabled>false</enabled><positionX>112</positionX><positionY>128</positionY>
            <displayText>打包台1全景</displayText><DeviceOption mode="keep">channel-extension</DeviceOption></channelNameOverlay>
            """;


    @ParameterizedTest
    @ValueSource(strings = {"", "osd:"})
    void showRestoresCompleteSystemFormatAndPreservesBusinessOrderAndNamespace(String namespace) throws Exception {
        String original = namespaced(initialXml(), namespace);
        OverlayTextRequest show = OverlayTextRequest.external("NEXT");
        show.setPlatformOrderNo("321123456789");
        show.setRecipient(new OverlayRecipient("NEXT", "R1", "Jane Doe", "US", "CA",
                "Los Angeles", null, "90001", "100 Demo Road"));

        String rewritten = OverlayService.rewrite(original, show);

        assertSystemFormat(rewritten);
        List<String> expected = List.of(PREFIX + "NEXT", "平台订单号：321123456789", "收件人：Jane Doe",
                "地址：100 Demo Road", "地区：Los Angeles, CA", "邮编：90001  国家：US");
        for (int id = 1; id <= expected.size(); id++) {
            Element overlay = textOverlay(rewritten, id);
            assertEquals(expected.get(id - 1), field(overlay, "displayText"));
            assertEquals("true", field(overlay, "enabled"));
            assertEquals(32, Double.parseDouble(field(overlay, "positionX")));
            assertEquals(480 - 32 * (id - 1), Double.parseDouble(field(overlay, "positionY")));
        }
        for (int id = 7; id <= 8; id++) assertClearedBlock(rewritten, id);
        assertTrue(rewritten.contains("custom=\"keep-time\""));
        assertTrue(rewritten.contains("custom=\"keep-date\""));
        assertTrue(rewritten.contains("custom=\"keep-channel\""));
        assertEquals("16", field(element(rewritten, "DateTimeOverlay"), "fontSize"));
        assertEquals("打包台1全景", field(element(rewritten, "channelNameOverlay"), "displayText"));
        assertTrue(rewritten.contains("time-extension"));
        assertTrue(rewritten.contains("channel-extension"));
        assertTrue(rewritten.contains("root-extension"));
        Element root = parse(rewritten);
        var all = root.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            assertEquals(namespace.isEmpty() ? null : "urn:osd", all.item(i).getNamespaceURI());
        }
    }

    @ParameterizedTest
    @MethodSource("missingFormatFields")
    void showAddsMissingFormatFieldInsideExistingSystemBlock(String namespace, String blockName,
                                                             String fieldName, String expected) throws Exception {
        String original = replaceBlock(initialXml(), blockName,
                block -> block.replaceAll("<" + fieldName + "\\b[^>]*>.*?</" + fieldName + ">", ""));
        String rewritten = OverlayService.rewrite(namespaced(original, namespace), OverlayTextRequest.external("NEXT"));
        Element block = element(rewritten, blockName);
        assertEquals(expected, field(block, fieldName));
        Element added = (Element) block.getElementsByTagNameNS("*", fieldName).item(0);
        assertSame(block, added.getParentNode());
        assertEquals(block.getNamespaceURI(), added.getNamespaceURI());
        assertSystemFormat(rewritten);
    }

    static Stream<Arguments> missingFormatFields() {
        return Stream.of("", "osd:").flatMap(namespace -> Stream.of(
                Arguments.of(namespace, "DateTimeOverlay", "enabled", "true"),
                Arguments.of(namespace, "DateTimeOverlay", "dateStyle", "CHR-YYYY-MM-DD"),
                Arguments.of(namespace, "DateTimeOverlay", "timeStyle", "24hour"),
                Arguments.of(namespace, "DateTimeOverlay", "displayWeek", "false"),
                Arguments.of(namespace, "channelNameOverlay", "enabled", "true")));
    }

    @ParameterizedTest
    @MethodSource("missingFormatFields")
    void showRepairsSelfClosingSystemFormatFields(String namespace, String blockName,
                                                 String fieldName, String expected) throws Exception {
        String original = replaceBlock(initialXml(), blockName,
                block -> block.replaceAll("(<" + fieldName + "\\b[^>]*)>.*?</" + fieldName + ">",
                        "$1" + (namespace.isEmpty() ? "/>" : " />")));
        String rewritten = OverlayService.rewrite(namespaced(original, namespace), OverlayTextRequest.external("NEXT"));
        Element system = element(rewritten, blockName);
        assertEquals(expected, field(system, fieldName));
        Element repaired = (Element) system.getElementsByTagNameNS("*", fieldName).item(0);
        assertSame(system, repaired.getParentNode());
        assertEquals(system.getNamespaceURI(), repaired.getNamespaceURI());
        if ("dateStyle".equals(fieldName)) assertEquals("keep-date", repaired.getAttribute("custom"));
        assertSystemFormat(rewritten);
    }

    @ParameterizedTest
    @MethodSource("nestedSystemFields")
    void showRestoresDirectSystemFieldWithoutChangingTheNestedDeviceExtension(String namespace,
            String fieldName, String expected, String nestedValue) throws Exception {
        String original = namespaced(systemFieldOnlyInExtension(initialXml(), fieldName, nestedValue), namespace);
        String extension = block(block(original, "DateTimeOverlay"), "DeviceOption");
        try (DeviceMemory device = new DeviceMemory(original)) {
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertTrue(result.isWritten());
            assertTrue(result.isApplied());
            Element system = element(device.xml(1), "DateTimeOverlay");
            Element direct = directElement(system, fieldName);
            assertEquals(expected, direct.getTextContent());
            assertEquals(system.getNamespaceURI(), direct.getNamespaceURI());
            Element deviceOption = directElement(system, "DeviceOption");
            assertEquals(nestedValue, field(deviceOption, fieldName));
            assertEquals(extension, block(block(device.xml(1), "DateTimeOverlay"), "DeviceOption"));
        }
    }

    @ParameterizedTest
    @MethodSource("nestedSystemFields")
    void showRejectsReadbackWithARequiredSystemFieldOnlyInsideDeviceExtension(String namespace,
            String fieldName, String expected, String nestedValue) {
        String target = namespaced(shownXml(), namespace);
        String actual = namespaced(systemFieldOnlyInExtension(shownXml(), fieldName, expected), namespace);
        assertFalse(OverlayService.packingOverlayMatches(target, actual));
        try (DeviceMemory device = new DeviceMemory(namespaced(initialXml(), namespace))) {
            device.store = ignored -> actual;
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertTrue(result.isWritten());
            assertFalse(result.isApplied());
            assertTrue(result.getDeviceStatusString().contains("回读"));
        }
    }

    static Stream<Arguments> nestedSystemFields() {
        return Stream.of("", "osd:").flatMap(namespace -> Stream.of(
                Arguments.of(namespace, "enabled", "true", "false"),
                Arguments.of(namespace, "positionY", "544", "96"),
                Arguments.of(namespace, "dateStyle", "CHR-YYYY-MM-DD", "MM-DD-YYYY"),
                Arguments.of(namespace, "timeStyle", "24hour", "12hour"),
                Arguments.of(namespace, "displayWeek", "false", "true")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "osd:"})
    void showLocatesDirectEnabledAroundCommentsCdataProcessingInstructionsAndQuotedAngles(String namespace)
            throws Exception {
        String comment = "<!-- keep <enabled>comment-value</enabled> -->";
        String cdata = "<![CDATA[keep <enabled>cdata-value</enabled>]]>";
        String instruction = "<?device keep=\"enabled > unchanged\"?>";
        String extension = "<" + namespace + "DeviceOption mode=\"nested > keep\"><" + namespace
                + "enabled>false</" + namespace + "enabled><" + namespace + "VendorFlag>keep</" + namespace
                + "VendorFlag></" + namespace + "DeviceOption>";
        String enabled = "<" + namespace + "enabled source=\"direct > keep\">false</" + namespace + "enabled>";
        String original = replaceBlock(namespaced(initialXml(), namespace), "DateTimeOverlay",
                system -> system.replace("<" + namespace + "enabled>false</" + namespace + "enabled>",
                        comment + cdata + instruction + extension + enabled));

        try (DeviceMemory device = new DeviceMemory(original)) {
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertTrue(result.isWritten());
            assertTrue(result.isApplied());
            String stored = device.xml(1);
            assertTrue(stored.contains(comment));
            assertTrue(stored.contains(cdata));
            assertTrue(stored.contains(instruction));
            assertTrue(stored.contains(extension));
            assertTrue(stored.contains(enabled.replace(">false<", ">true<")));
            Element system = element(stored, "DateTimeOverlay");
            Element direct = directElement(system, "enabled");
            assertEquals("true", direct.getTextContent());
            assertEquals("direct > keep", direct.getAttribute("source"));
            assertEquals(system.getNamespaceURI(), direct.getNamespaceURI());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DateTimeOverlay", "channelNameOverlay"})
    void showWithoutARequiredSystemBlockUpdatesBusinessButNeverReportsSuccess(String missing) throws Exception {
        String incomplete = replaceBlock(initialXml(), missing, ignored -> "");
        try (DeviceMemory device = new DeviceMemory(incomplete)) {
            OverlayTextResponse first = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertTrue(first.isWritten());
            assertFalse(first.isApplied());
            assertEquals(PREFIX + "NEXT", field(textOverlay(device.xml(1), 1), "displayText"));
            assertEquals("地址未获取", field(textOverlay(device.xml(1), 2), "displayText"));
            assertFalse(device.xml(1).contains("OLD"));
            for (int id = 3; id <= 8; id++) assertClearedBlock(device.xml(1), id);
            assertEquals(0, parse(device.xml(1)).getElementsByTagNameNS("*", missing).getLength());

            OverlayTextResponse repeated = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertFalse(repeated.isApplied());
            assertFalse(repeated.isWritten());
            verify(device.client, times(1)).put(eq(DEVICE), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidShowState")
    void showReadbackMustConfirmFormatEnabledStateAndCoordinates(String blockName, String fieldName,
                                                                String wrongValue) {
        String target = shownXml();
        String actual = replaceBlockField(target, blockName, fieldName, wrongValue);
        assertFalse(OverlayService.packingOverlayMatches(target, actual));
    }

    static Stream<Arguments> invalidShowState() {
        return Stream.of(
                Arguments.of("DateTimeOverlay", "enabled", "false"),
                Arguments.of("DateTimeOverlay", "dateStyle", "YYYY-MM-DD"),
                Arguments.of("DateTimeOverlay", "timeStyle", "12hour"),
                Arguments.of("DateTimeOverlay", "displayWeek", "true"),
                Arguments.of("DateTimeOverlay", "positionX", "32"),
                Arguments.of("DateTimeOverlay", "positionY", "528"),
                Arguments.of("channelNameOverlay", "enabled", "false"),
                Arguments.of("channelNameOverlay", "positionX", "16"),
                Arguments.of("channelNameOverlay", "positionY", "528"));
    }

    @ParameterizedTest
    @MethodSource("invalidShowState")
    void showDeviceAcceptanceDoesNotHideRejectedSystemFormat(String blockName, String fieldName,
                                                            String wrongValue) {
        try (DeviceMemory device = new DeviceMemory(initialXml())) {
            device.store = xml -> replaceBlockField(xml, blockName, fieldName, wrongValue);
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertTrue(result.isWritten());
            assertFalse(result.isApplied());
            assertTrue(result.getDeviceStatusString().contains("回读"));
        }
    }

    @Test
    void unchangedBusinessContentStillRepairsInvalidSystemFormat() throws Exception {
        String invalid = replaceBlockField(shownXml(), "DateTimeOverlay", "enabled", "false");
        try (DeviceMemory device = new DeviceMemory(invalid)) {
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertTrue(result.isWritten());
            assertTrue(result.isApplied());
            assertSystemFormat(device.xml(1));
            verify(device.client, times(1)).put(eq(DEVICE), anyString(), anyString());
        }
    }

    @Test
    void validUnchangedShowSkipsPutAfterConfirmingItsCurrentState() {
        String current = shownXml();
        assertTrue(OverlayService.packingOverlayMatches(current, current));
        try (DeviceMemory device = new DeviceMemory(current)) {
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external("NEXT"));
            assertTrue(result.isApplied());
            assertFalse(result.isWritten());
            verify(device.client, never()).put(anyString(), anyString(), anyString());
        }
    }

    @Test
    void clearPreservesSystemBlocksAndLeavesOnlyTheFixedPrefix() throws Exception {
        String original = namespaced(initialXml(), "osd:");
        String cleared = OverlayService.rewrite(original, OverlayTextRequest.external(""));
        assertEquals(block(original, "DateTimeOverlay"), block(cleared, "DateTimeOverlay"));
        assertEquals(block(original, "channelNameOverlay"), block(cleared, "channelNameOverlay"));
        assertCleared(cleared);
    }

    @Test
    void invalidWaybillFallbackClearsBusinessWithoutChangingSystemAndKeepsTheOriginalError() throws Exception {
        try (DeviceMemory device = new DeviceMemory(initialXml())) {
            String invalidWaybill = "X".repeat(100);
            OverlayTextResponse first = device.service.apply(DEVICE, 1, OverlayTextRequest.external(invalidWaybill));
            assertTrue(first.isWritten());
            assertFalse(first.isApplied());
            assertEquals(PREFIX, first.getDisplayText());
            assertTrue(first.getDeviceStatusString().contains("快递单号"));
            assertTrue(first.getDeviceStatusString().contains("超出单行"));
            assertEquals(block(initialXml(), "DateTimeOverlay"), block(device.xml(1), "DateTimeOverlay"));
            assertEquals(block(initialXml(), "channelNameOverlay"), block(device.xml(1), "channelNameOverlay"));
            assertCleared(device.xml(1));

            OverlayTextResponse repeated = device.service.apply(DEVICE, 1, OverlayTextRequest.external(invalidWaybill));
            assertFalse(repeated.isApplied());
            assertFalse(repeated.isWritten());
            assertTrue(repeated.getDeviceStatusString().contains("快递单号"));
            assertTrue(repeated.getDeviceStatusString().contains("超出单行"));
            verify(device.client, times(1)).put(eq(DEVICE), anyString(), anyString());
        }
    }

    @Test
    void clearSucceedsAfterCoordinateNormalizationAndRepeatedClearDoesNotPutAgain() throws Exception {
        try (DeviceMemory device = new DeviceMemory(initialXml())) {
            device.store = xml -> xml.replaceAll("<(position[XY])>.*?</position[XY]>", "<$1>0.000000</$1>");
            OverlayTextResponse first = device.service.apply(DEVICE, 1, OverlayTextRequest.external(""));
            assertTrue(first.isApplied());
            assertTrue(first.isWritten());
            assertEquals(block(initialXml(), "DateTimeOverlay"), block(device.writes.getFirst(), "DateTimeOverlay"));
            assertEquals(block(initialXml(), "channelNameOverlay"), block(device.writes.getFirst(), "channelNameOverlay"));
            assertEquals("0.000000", field(textOverlay(device.xml(1), 1), "positionX"));
            assertCleared(device.xml(1));
            OverlayTextResponse repeated = device.service.apply(DEVICE, 1, OverlayTextRequest.external(""));
            assertTrue(repeated.isApplied());
            assertFalse(repeated.isWritten());
            verify(device.client, times(1)).put(eq(DEVICE), anyString(), anyString());
        }
    }

    @Test
    void clearSucceedsWithoutSystemBlocksAndIgnoresTheirReadbackFormat() throws Exception {
        String noSystem = replaceBlock(replaceBlock(initialXml(), "DateTimeOverlay", ignored -> ""),
                "channelNameOverlay", ignored -> "");
        try (DeviceMemory device = new DeviceMemory(noSystem)) {
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external(""));
            assertTrue(result.isApplied());
            assertCleared(device.xml(1));
            String withUnexpectedSystem = device.xml(1).replace("</VideoOverlay>", SYSTEM + "</VideoOverlay>");
            assertTrue(OverlayService.packingOverlayMatches(device.xml(1), withUnexpectedSystem));
        }
    }

    @Test
    void clearReadbackIgnoresAllCoordinatesIncludingTheVisiblePrefix() {
        String target = clearedXml();
        String actual = target.replaceAll("<(position[XY])>.*?</position[XY]>", "<$1>7.500000</$1>");
        actual = replaceBlockField(actual, "DateTimeOverlay", "dateStyle", "ANY-DEVICE-FORMAT");
        assertTrue(OverlayService.packingOverlayMatches(target, actual));
    }

    @Test
    void matchingWaybillClearWorksWithoutAnyCoordinateFields() throws Exception {
        String noCoordinates = initialXml().replaceAll("<position[XY]>.*?</position[XY]>", "");
        try (DeviceMemory device = new DeviceMemory(noCoordinates)) {
            OverlayTextRequest clear = OverlayTextRequest.external("");
            clear.setExpectedWaybillNo("OLD");
            OverlayTextResponse result = device.service.apply(DEVICE, 1, clear);
            assertTrue(result.isApplied());
            assertTrue(result.isWritten());
            assertFalse(result.isSuperseded());
            assertCleared(device.xml(1));
            assertEquals(block(noCoordinates, "DateTimeOverlay"), block(device.xml(1), "DateTimeOverlay"));
            assertEquals(block(noCoordinates, "channelNameOverlay"), block(device.xml(1), "channelNameOverlay"));
            verify(device.client, times(1)).put(eq(DEVICE), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8})
    void clearRequiresEveryOldBusinessBlockToBeDisabledAndEmpty(int id) {
        String target = clearedXml();
        String enabled = replaceTextBlockField(target, id, "enabled", "true");
        String staleText = replaceTextBlockField(target, id, "displayText", "OLD ADDRESS");
        assertFalse(OverlayService.packingOverlayMatches(target, enabled));
        assertFalse(OverlayService.packingOverlayMatches(target, staleText));
    }

    @Test
    void clearRequiresAnEnabledFixedPrefix() {
        String target = clearedXml();
        assertFalse(OverlayService.packingOverlayMatches(target,
                replaceTextBlockField(target, 1, "enabled", "false")));
        assertFalse(OverlayService.packingOverlayMatches(target,
                replaceTextBlockField(target, 1, "displayText", PREFIX + "OLD")));
        assertFalse(OverlayService.packingOverlayMatches(target,
                replaceTextBlockField(target, 1, "displayText", "")));
    }

    @Test
    void clearDeviceAcceptanceWithoutRemovingOldBusinessContentIsFailure() {
        try (DeviceMemory device = new DeviceMemory(initialXml())) {
            device.store = ignored -> initialXml();
            OverlayTextResponse result = device.service.apply(DEVICE, 1, OverlayTextRequest.external(""));
            assertTrue(result.isWritten());
            assertFalse(result.isApplied());
            assertTrue(result.getDeviceStatusString().contains("回读"));
            verify(device.client, times(1)).put(eq(DEVICE), anyString(), anyString());
        }
    }

    @Test
    void previousWaybillClearStillCannotEraseTheCurrentPackage() {
        try (DeviceMemory device = new DeviceMemory(initialXml())) {
            OverlayTextRequest clear = OverlayTextRequest.external("");
            clear.setExpectedWaybillNo("PREVIOUS");
            OverlayTextResponse result = device.service.apply(DEVICE, 1, clear);
            assertTrue(result.isSuperseded());
            assertTrue(result.isApplied());
            assertFalse(result.isWritten());
            assertEquals(initialXml(), device.xml(1));
            verify(device.client, never()).put(anyString(), anyString(), anyString());
        }
    }

    @Test
    void realStationServiceClearsPanoramaAndDetailEvenWhenTheirSystemLayoutsDiffer() throws Exception {
        try (DeviceMemory device = new DeviceMemory(initialXml())) {
            String detail = replaceBlock(replaceBlock(initialXml(), "DateTimeOverlay", ignored -> ""),
                    "channelNameOverlay", ignored -> "");
            device.devices.put(path(2), detail);
            device.store = xml -> xml.replaceAll("<(position[XY])>.*?</position[XY]>", "<$1>0</$1>");
            ChannelService channels = mock(ChannelService.class);
            when(channels.listChannels(DEVICE)).thenReturn(List.of(
                    new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
            StationOverlayService stations = new StationOverlayService(channels, device.service);

            stations.apply(DEVICE, "打包台1", "", null, "OLD");
            assertCleared(device.xml(1));
            assertCleared(device.xml(2));
            stations.apply(DEVICE, "打包台1", "", null, "OLD");
            verify(device.client, times(1)).put(eq(DEVICE), eq(path(1)), anyString());
            verify(device.client, times(1)).put(eq(DEVICE), eq(path(2)), anyString());
        }
    }

    private static String initialXml() {
        StringBuilder xml = new StringBuilder("<VideoOverlay><TextOverlayList size=\"8\">");
        for (int id = 1; id <= 8; id++) {
            xml.append("<TextOverlay><id>").append(id).append("</id><enabled>true</enabled>")
                    .append("<positionX>160</positionX><positionY>").append(80 + id * 16)
                    .append("</positionY><displayText>").append(id == 1 ? PREFIX + "OLD" : "OLD LINE " + id)
                    .append("</displayText></TextOverlay>");
        }
        return xml.append("</TextOverlayList>").append(SYSTEM)
                .append("<DeviceOption mode=\"keep\">root-extension</DeviceOption></VideoOverlay>").toString();
    }

    /** 独立的设备状态样本，不依赖 rewrite 来构造校验期望。 */
    private static String shownXml() {
        String xml = clearedXml();
        xml = replaceTextBlockField(xml, 1, "displayText", PREFIX + "NEXT");
        xml = replaceTextBlockField(xml, 2, "displayText", "地址未获取");
        xml = replaceTextBlockField(xml, 2, "enabled", "true");
        for (int id = 1; id <= 8; id++) {
            xml = replaceTextBlockField(xml, id, "positionX", "32");
            xml = replaceTextBlockField(xml, id, "positionY", Integer.toString(480 - 32 * (id - 1)));
        }
        xml = replaceBlockField(xml, "DateTimeOverlay", "enabled", "true");
        xml = replaceBlockField(xml, "DateTimeOverlay", "dateStyle", "CHR-YYYY-MM-DD");
        xml = replaceBlockField(xml, "DateTimeOverlay", "timeStyle", "24hour");
        xml = replaceBlockField(xml, "DateTimeOverlay", "displayWeek", "false");
        xml = replaceBlockField(xml, "DateTimeOverlay", "positionX", "16");
        xml = replaceBlockField(xml, "DateTimeOverlay", "positionY", "544");
        xml = replaceBlockField(xml, "channelNameOverlay", "enabled", "true");
        xml = replaceBlockField(xml, "channelNameOverlay", "positionX", "32");
        return replaceBlockField(xml, "channelNameOverlay", "positionY", "512");
    }

    private static String clearedXml() {
        String xml = replaceTextBlockField(initialXml(), 1, "displayText", PREFIX);
        for (int id = 2; id <= 8; id++) {
            xml = replaceTextBlockField(xml, id, "displayText", "");
            xml = replaceTextBlockField(xml, id, "enabled", "false");
        }
        return xml;
    }

    private static void assertCleared(String xml) throws Exception {
        assertEquals(PREFIX, field(textOverlay(xml, 1), "displayText"));
        assertEquals("true", field(textOverlay(xml, 1), "enabled"));
        for (int id = 2; id <= 8; id++) assertClearedBlock(xml, id);
    }

    private static void assertClearedBlock(String xml, int id) throws Exception {
        assertEquals("false", field(textOverlay(xml, id), "enabled"), "enabled, id=" + id);
        assertEquals("", field(textOverlay(xml, id), "displayText"), "text, id=" + id);
    }

    private static void assertSystemFormat(String xml) throws Exception {
        Element time = element(xml, "DateTimeOverlay");
        assertEquals("true", field(time, "enabled"));
        assertEquals("CHR-YYYY-MM-DD", field(time, "dateStyle"));
        assertEquals("24hour", field(time, "timeStyle"));
        assertEquals("false", field(time, "displayWeek"));
        assertEquals(16, Double.parseDouble(field(time, "positionX")));
        assertEquals(544, Double.parseDouble(field(time, "positionY")));
        Element channel = element(xml, "channelNameOverlay");
        assertEquals("true", field(channel, "enabled"));
        assertEquals(32, Double.parseDouble(field(channel, "positionX")));
        assertEquals(512, Double.parseDouble(field(channel, "positionY")));
    }

    private static String namespaced(String xml, String prefix) {
        if (prefix.isEmpty()) return xml;
        return xml.replaceAll("<(/?)([A-Za-z][A-Za-z0-9]*)", "<$1" + prefix + "$2")
                .replace("<" + prefix + "VideoOverlay>", "<" + prefix + "VideoOverlay xmlns:osd=\"urn:osd\">");
    }

    private static String block(String xml, String name) {
        Matcher matcher = Pattern.compile("<(?:\\w+:)?" + name + "\\b[^>]*>.*?</(?:\\w+:)?" + name + ">",
                Pattern.DOTALL).matcher(xml);
        assertTrue(matcher.find(), "missing block: " + name);
        return matcher.group();
    }

    private static String replaceBlock(String xml, String name, UnaryOperator<String> replacement) {
        String old = block(xml, name);
        return xml.replace(old, replacement.apply(old));
    }

    private static String replaceBlockField(String xml, String name, String field, String value) {
        return replaceBlock(xml, name, old -> replaceField(old, field, value));
    }

    private static String systemFieldOnlyInExtension(String xml, String field, String value) {
        return replaceBlock(xml, "DateTimeOverlay", system -> system
                .replaceAll("<" + field + "\\b[^>]*>.*?</" + field + ">", "")
                .replace("<DeviceOption mode=\"keep\">time-extension</DeviceOption>",
                        "<DeviceOption mode=\"keep\">time-extension<" + field + " custom=\"nested\">"
                                + value + "</" + field + "></DeviceOption>"));
    }

    private static String replaceTextBlockField(String xml, int id, String field, String value) {
        Matcher blocks = Pattern.compile("<TextOverlay>.*?</TextOverlay>", Pattern.DOTALL).matcher(xml);
        while (blocks.find()) {
            if (blocks.group().contains("<id>" + id + "</id>")) {
                return xml.substring(0, blocks.start()) + replaceField(blocks.group(), field, value)
                        + xml.substring(blocks.end());
            }
        }
        throw new AssertionError("missing text block: " + id);
    }

    private static String replaceField(String block, String field, String value) {
        return block.replaceAll("(<" + field + "\\b[^>]*>).*?(</" + field + ">)",
                "$1" + Matcher.quoteReplacement(value) + "$2");
    }

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    private static Element element(String xml, String name) throws Exception {
        var nodes = parse(xml).getElementsByTagNameNS("*", name);
        assertEquals(1, nodes.getLength(), name);
        return (Element) nodes.item(0);
    }

    private static Element directElement(Element parent, String name) {
        List<Element> matching = new ArrayList<>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) matching.add(element);
        }
        assertEquals(1, matching.size(), "direct field: " + name);
        return matching.getFirst();
    }

    private static Element textOverlay(String xml, int id) throws Exception {
        var nodes = parse(xml).getElementsByTagNameNS("*", "TextOverlay");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (Integer.toString(id).equals(field(element, "id"))) return element;
        }
        throw new AssertionError("missing text overlay: " + id);
    }

    private static String field(Element element, String name) {
        var nodes = element.getElementsByTagNameNS("*", name);
        assertEquals(1, nodes.getLength(), name);
        return nodes.item(0).getTextContent();
    }

    private static String path(int channel) {
        return "/ISAPI/System/Video/inputs/channels/" + channel + "/overlays";
    }

    private static final class DeviceMemory implements AutoCloseable {
        final DeviceIsapiClient client = mock(DeviceIsapiClient.class);
        final OverlayService service = new OverlayService(client);
        final Map<String, String> devices = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        UnaryOperator<String> store = UnaryOperator.identity();

        DeviceMemory(String xml) {
            devices.put(path(1), xml);
            when(client.get(eq(DEVICE), anyString())).thenAnswer(call -> devices.get(call.<String>getArgument(1)));
            when(client.put(eq(DEVICE), anyString(), anyString())).thenAnswer(call -> {
                String body = call.getArgument(2);
                writes.add(body);
                devices.put(call.getArgument(1), store.apply(body));
                return ACCEPTED;
            });
            ReflectionTestUtils.setField(service, "minWriteIntervalSec", 0);
        }

        String xml(int channel) { return devices.get(path(channel)); }

        @Override
        public void close() { service.destroy(); }
    }
}
