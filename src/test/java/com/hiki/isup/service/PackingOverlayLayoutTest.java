package com.hiki.isup.service;

import com.hiki.isup.model.request.OverlayRecipient;
import com.hiki.isup.model.request.OverlayTextRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackingOverlayLayoutTest {
    private static final String XML = "<TextOverlayList size=\"8\"></TextOverlayList>";

    @Test
    void postcodeAndCountryShareOneClearlySeparatedLine() {
        List<String> lines = lines(recipient("US", "90001", "", "Los Angeles", "CA", "100 Demo Road Apt 2"));
        assertEquals(List.of("收件人：Jane Doe", "地址：100 Demo Road Apt 2", "地区：Los Angeles, CA",
                "邮编：90001  国家：US"), lines);
    }

    @Test
    void previouslyOverflowingGermanAddressFitsWithoutLosingAnyField() {
        OverlayRecipient recipient = new OverlayRecipient("TEST001", "R1", "Maximilian Alexander Mustermann",
                "DE", "Baden-Wurttemberg", "Villingen-Schwenningen", "Schwenningen", "78054",
                "Internationale Handelsgesellschaft Musterstrasse 123 Hinterhaus 4. Obergeschoss Wohnung 16");
        List<String> lines = assertDoesNotThrow(() -> lines(recipient));
        assertEquals(7, lines.size());
        String displayed = String.join(" ", lines);
        assertTrue(displayed.contains(recipient.name()));
        assertTrue(displayed.contains(recipient.address()));
        assertTrue(displayed.contains("地区：Schwenningen, Villingen-Schwenningen, Baden-Wurttemberg"));
        assertEquals("邮编：78054  国家：DE", lines.getLast());
    }

    @Test
    void duplicateMunicipalityIsDisplayedOnceAfterWhitespaceNormalization() {
        List<String> lines = lines(recipient("CN", "100020", "朝阳区", " 北京市　", "北京市", "示例路108号"));
        assertTrue(lines.contains("地区：朝阳区, 北京市"));
        assertFalse(String.join(" ", lines).contains("北京市, 北京市"));
    }

    @Test
    void completeRegionMatchesIgnoreCaseButKeepFirstSpelling() {
        List<String> lines = lines(recipient("GB", "YO1", "yOrK", " YORK ", "York", "12 York Road"));
        assertTrue(lines.contains("地区：yOrK"));
        assertTrue(lines.contains("地址：12 York Road"));
    }

    @Test
    void regionSubstringsAreNeverRemoved() {
        List<String> lines = lines(recipient("US", "10001", "York", "New York", "NY", "York Road"));
        assertTrue(lines.contains("地区：York, New York, NY"));
    }

    @Test
    void mergedFooterMustFitWholeDisplayWidthOtherwiseUsesSeparateLines() {
        String atLimit = "X".repeat(25);
        assertEquals("邮编：12345  国家：" + atLimit,
                lines(recipient(atLimit, "12345", "", "", "", "Road")).getLast());
        List<String> overflow = lines(recipient(atLimit + "X", "12345", "", "", "", "Road"));
        assertEquals(List.of("邮编：12345", "国家：" + atLimit + "X"), overflow.subList(overflow.size() - 2, overflow.size()));
    }

    @Test
    void mergedFooterMustFitWholeEncodedByteLimit() {
        String atLimit = "国".repeat(13);
        OverlayTextRequest request = request(recipient(atLimit, "12345", "", "", "", "Road"));
        request.setAddressLineWidth(100);
        String footer = PackingOverlayLayout.lines(XML, request).getLast();
        assertEquals("邮编：12345  国家：" + atLimit, footer);
        assertEquals(64, footer.getBytes(StandardCharsets.UTF_8).length);
        request.setRecipient(recipient(atLimit + "国", "12345", "", "", "", "Road"));
        List<String> overflow = PackingOverlayLayout.lines(XML, request);
        assertEquals(List.of("邮编：12345", "国家：" + atLimit + "国"), overflow.subList(overflow.size() - 2, overflow.size()));
    }

    @Test
    void missingPostcodeStillShowsCountryWithoutAnEmptyLabel() {
        List<String> lines = lines(recipient("US", " ", "", "", "", "Road"));
        assertEquals("国家：US", lines.getLast());
        assertFalse(String.join(" ", lines).contains("邮编："));
    }

    @Test
    void footerCannotUseAnUnrepresentableCharacter() {
        OverlayTextRequest request = request(recipient("US 😀", "12345", "", "", "", "Road"));
        assertThrows(IllegalArgumentException.class,
                () -> PackingOverlayLayout.lines("<?xml version=\"1.0\" encoding=\"GB2312\"?>" + XML, request));
    }

    private static OverlayRecipient recipient(String country, String postcode, String district,
                                               String city, String state, String address) {
        return new OverlayRecipient("TEST001", "R1", "Jane Doe", country, state, city, district, postcode, address);
    }

    private static OverlayTextRequest request(OverlayRecipient recipient) {
        OverlayTextRequest request = OverlayTextRequest.external("TEST001");
        request.setRecipient(recipient);
        return request;
    }

    private static List<String> lines(OverlayRecipient recipient) {
        return PackingOverlayLayout.lines(XML, request(recipient));
    }
}
