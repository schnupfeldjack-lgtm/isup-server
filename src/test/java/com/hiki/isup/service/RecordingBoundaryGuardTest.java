package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RecordingBoundaryGuardTest {
    private final AtomicLong clock = new AtomicLong();
    private final FakeClient client = new FakeClient();
    private final RecordingBoundaryGuard guard = new RecordingBoundaryGuard(client, clock::get);
    private final LocalDateTime start = at("09:57:50");
    private final LocalDateTime end = at("09:59:06");

    @ParameterizedTest
    @CsvSource({
            "2026-10-17,2,09:57:50,09:59:06,09:45:21,09:59:08,09:59:05,10:12:52,09:59:33",
            "2026-10-17,2,10:52:59,10:54:07,10:40:19,10:54:06,10:54:04,11:07:55,10:54:31",
            "2026-10-20,1,11:55:08,11:56:17,10:58:49,11:56:16,11:56:16,13:32:52,11:56:41"
    })
    void extendsThreeObservedFailures(String day, int channel, String from, String to,
                                     String a, String b, String c, String d, String expected) throws Exception {
        client.xml = response(item(channel * 100 + 1, day, a, b), item(channel * 100 + 1, day, c, d));
        assertEquals(LocalDateTime.parse(day + "T" + expected), guard.safeEnd("DLWH", channel,
                LocalDateTime.parse(day + "T" + from), LocalDateTime.parse(day + "T" + to), budget()));
        assertEquals(1, client.calls);
        assertEquals("DLWH", client.device);
        assertEquals("POST", client.method);
        assertEquals("/ISAPI/ContentMgmt/search", client.path);
        assertTrue(client.timeout >= 1000 && client.timeout <= 4000);
        assertTrue(client.body.contains("<trackIDList><trackID>" + (channel * 100 + 1) + "</trackID></trackIDList>"));
        assertTrue(client.body.contains("<maxResults>30</maxResults>"));
        assertTrue(client.body.contains("<searchResultPostion>0</searchResultPostion>"));
        assertTrue(client.body.contains(LocalDateTime.parse(day + "T" + to).minusSeconds(60).toString() + "Z"));
        assertTrue(client.body.contains(LocalDateTime.parse(day + "T" + to).plusSeconds(60).toString() + "Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:58:40", "09:59:30", "10:00:00"})
    void preservesSafeEndIncludingCrossFileRequest(String to) throws Exception {
        standard();
        assertEquals(at(to), guard.safeEnd("DLWH", 2, start, at(to), budget()));
    }

    @Test
    void preservesEndInLongOverlapWhenFarFromBothActualBoundaries() throws Exception {
        client.xml = response(item("09:45:21", "09:59:50"), item("09:58:20", "10:12:52"));
        assertEquals(end, safe());
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:59:00", "09:59:13"})
    void includesBothFiveSecondBoundaryEdges(String requested) throws Exception {
        standard();
        assertEquals(at("09:59:33"), guard.safeEnd("DLWH", 2, start, at(requested), budget()));
    }

    @Test
    void rejectsMoreThanThirtyMatchesEvenWithOkStatus() {
        String[] items = new String[31];
        java.util.Arrays.fill(items, item("09:45:21", "09:59:08"));
        client.xml = response(items);
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void rejectsNonVideoIndexItems() {
        standard();
        client.xml = client.xml.replace("<contentType>video", "<contentType>audio");
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void rejectsDuplicateRequiredFields() {
        standard();
        client.xml = client.xml.replace("<trackID>201</trackID>", "<trackID>201</trackID><trackID>201</trackID>");
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void acceptsUnsortedSpansAndBraceWrappedMatchingSearchId() throws Exception {
        client.xml = response(item("09:59:05", "10:12:52"), item("09:45:21", "09:59:08"));
        client.echoSearchId = true;
        assertEquals(at("09:59:33"), safe());
    }

    @Test
    void movesPastSecondNearbyBoundaryAndConfirmsCoverage() throws Exception {
        client.xml = response(item("09:45:21", "09:59:08"), item("09:59:05", "09:59:35"),
                item("09:59:34", "10:12:52"));
        assertEquals(at("10:00:00"), safe());
    }

    @Test
    void refusesExtensionOverSixtySeconds() {
        client.xml = response(item("09:45:21", "09:59:08"), item("09:59:05", "09:59:35"),
                item("09:59:34", "10:00:02"), item("10:00:01", "10:12:52"));
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void refusesExtensionOverMaximumRecordingRange() {
        standard();
        assertThrows(IOException.class, () -> guard.safeEnd("DLWH", 2, end.minusHours(24), end, budget()));
    }

    @Test
    void rejectsGapAtRequestedEnd() {
        client.xml = response(item("09:45:21", "09:59:05"), item("09:59:07", "10:12:52"));
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void rejectsExtensionThroughGap() {
        client.xml = response(item("09:45:21", "09:59:08"), item("09:59:05", "09:59:20"),
                item("09:59:22", "10:12:52"));
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void refusesUnconfirmedEndEvenWithoutKnownBoundary() {
        client.xml = response(item("09:45:21", "09:59:05"));
        assertThrows(IOException.class, this::safe);
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:59:01", "09:59:06", "09:59:11"})
    void rejectsUnpairedFileEndWithinFiveSeconds(String requested) {
        client.xml = response(item("09:45:21", "09:59:06"));
        assertThrows(IOException.class, () -> guard.safeEnd("DLWH", 2, start, at(requested), budget()));
    }

    @Test
    void rejectsFileEndBeforeGapEvenWhenRequestedEndIsCovered() {
        client.xml = response(item("09:45:21", "09:59:08"), item("09:59:10", "10:12:52"));
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void preservesEndNearIsolatedFileStartWithSufficientRecordingAfterIt() throws Exception {
        client.xml = response(item("09:59:04", "10:12:52"));
        assertEquals(end, safe());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<CMSearchResult>", "<ResponseStatus><statusCode>4</statusCode></ResponseStatus>",
            "<!DOCTYPE CMSearchResult [<!ENTITY x SYSTEM 'file:///nonexistent'>]><CMSearchResult>&x;</CMSearchResult>"})
    void rejectsMalformedErrorTruncatedAndXxeXml(String xml) {
        client.xml = xml;
        assertThrows(IOException.class, this::safe);
        assertEquals(1, client.calls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "more", "count", "foreign", "time", "empty", "reverse", "namespace", "id"})
    void rejectsUnsupportedOrMismatchedMetadata(String mutation) {
        standard();
        client.xml = switch (mutation) {
            case "status" -> client.xml.replace("<responseStatus>true", "<responseStatus>false");
            case "more" -> client.xml.replace(">OK<", ">MORE<");
            case "count" -> client.xml.replace("<numOfMatches>2", "<numOfMatches>3");
            case "foreign" -> client.xml.replace("<trackID>201", "<trackID>101");
            case "time" -> client.xml.replace("09:59:08Z", "09:59:08+08:00");
            case "empty" -> response();
            case "reverse" -> response(item("10:00:00", "09:59:08"));
            case "namespace" -> client.xml.replace("http://www.hikvision.com/ver20/XMLSchema", "urn:foreign");
            default -> client.xml.replace("<responseStatus>", "<searchID>wrong</searchID><responseStatus>");
        };
        assertThrows(IOException.class, this::safe);
    }

    @Test
    void checksDeadlineBeforeQuery() {
        standard();
        assertThrows(IOException.class, () -> guard.safeEnd("DLWH", 2, start, end, 999_000_000L));
        assertEquals(0, client.calls);
    }

    @Test
    void checksRemainingBudgetAfterQuery() {
        standard();
        client.afterRequest = () -> clock.set(9_001_000_000L);
        assertThrows(IOException.class, this::safe);
        assertEquals(1, client.calls);
    }

    @Test
    void capsQueryTimeoutByRemainingBudget() throws Exception {
        standard();
        guard.safeEnd("DLWH", 2, start, end, 1_500_000_000L);
        assertEquals(1500, client.timeout);
    }

    @Test
    void wrapsClientFailureWithoutRetry() {
        client.failure = new IllegalStateException("offline");
        assertThrows(IOException.class, this::safe);
        assertEquals(1, client.calls);
    }

    private LocalDateTime safe() throws IOException { return guard.safeEnd("DLWH", 2, start, end, budget()); }
    private long budget() { return 10_000_000_000L; }
    private void standard() { client.xml = response(item("09:45:21", "09:59:08"), item("09:59:05", "10:12:52")); }
    private static LocalDateTime at(String time) { return LocalDateTime.parse("2026-10-17T" + time); }
    private static String item(String from, String to) { return item(201, "2026-10-17", from, to); }
    private static String item(int track, String day, String from, String to) {
        return "<searchMatchItem><trackID>" + track + "</trackID><timeSpan><startTime>" + day + "T" + from
                + "Z</startTime><endTime>" + day + "T" + to + "Z</endTime></timeSpan>"
                + "<mediaSegmentDescriptor><contentType>video</contentType></mediaSegmentDescriptor></searchMatchItem>";
    }
    private static String response(String... items) {
        return "<CMSearchResult xmlns=\"http://www.hikvision.com/ver20/XMLSchema\"><responseStatus>true</responseStatus>"
                + "<responseStatusStrg>OK</responseStatusStrg><numOfMatches>" + items.length + "</numOfMatches>"
                + "<matchList>" + String.join("", items) + "</matchList></CMSearchResult>";
    }
    private static class FakeClient extends DeviceIsapiClient {
        String xml, device, method, path, body;
        int timeout, calls;
        boolean echoSearchId;
        RuntimeException failure;
        Runnable afterRequest = () -> { };
        FakeClient() { super(null, null); }
        @Override public String request(String device, String method, String path, String body, Integer timeout) {
            calls++;
            this.device = device; this.method = method; this.path = path; this.body = body; this.timeout = timeout;
            afterRequest.run();
            if (failure != null) throw failure;
            if (echoSearchId) {
                String id = body.substring(body.indexOf("<searchID>") + 10, body.indexOf("</searchID>"));
                return xml.replace("<responseStatus>", "<searchID>{" + id + "}</searchID><responseStatus>");
            }
            return xml;
        }
    }
}
