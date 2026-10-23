package com.hiki.isup.service;

import com.hiki.isup.util.TimeRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Read-only, bounded index preflight for the verified DLWH firmware end-boundary fault.
 * The caller must explicitly opt in only devices with this verified timestamp convention:
 * their ISAPI index marks device-local wall times with Z; no UTC conversion is performed.
 * This does not validate/trim the business video or change native cleanup/retry semantics.
 */
@Service
public class RecordingBoundaryGuard {
    private static final String XML_NS = "http://www.hikvision.com/ver20/XMLSchema";
    private static final DateTimeFormatter DEVICE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'");
    private static final int MAX_MATCHES = 30;
    private final DeviceIsapiClient client;
    private final LongSupplier nanoTime;

    @Autowired
    public RecordingBoundaryGuard(DeviceIsapiClient client) {
        this(client, System::nanoTime);
    }

    RecordingBoundaryGuard(DeviceIsapiClient client, LongSupplier nanoTime) {
        this.client = client;
        this.nanoTime = nanoTime;
    }

    public LocalDateTime safeEnd(String deviceId, int channel, LocalDateTime start,
                                 LocalDateTime requestedEnd, long deadlineNanos) throws IOException {
        if (deviceId == null || deviceId.isBlank() || channel <= 0 || channel > (Integer.MAX_VALUE - 1) / 100
                || start == null || requestedEnd == null || !start.isBefore(requestedEnd)
                || requestedEnd.isAfter(start.plusHours(TimeRange.MAX_RANGE_HOURS))
                || start.getNano() != 0 || requestedEnd.getNano() != 0) {
            throw new IOException("Invalid recording boundary preflight range/device/channel");
        }
        int track = channel * 100 + 1;
        String searchId = UUID.randomUUID().toString();
        LocalDateTime searchStart = requestedEnd.minusSeconds(60);
        LocalDateTime searchEnd = requestedEnd.plusSeconds(60);
        String query = "<CMSearchDescription version=\"1.0\" xmlns=\"" + XML_NS + "\">"
                + "<searchID>" + searchId + "</searchID><trackIDList><trackID>" + track + "</trackID></trackIDList>"
                + "<timeSpanList><timeSpan><startTime>" + DEVICE_TIME.format(searchStart) + "</startTime>"
                + "<endTime>" + DEVICE_TIME.format(searchEnd) + "</endTime></timeSpan></timeSpanList>"
                // Postion is the device's deployed ISAPI spelling, verified by successful index queries.
                + "<maxResults>30</maxResults><searchResultPostion>0</searchResultPostion>"
                + "<metadataList><metadataDescriptor>//recordType.meta.std-cgi.com</metadataDescriptor></metadataList>"
                + "</CMSearchDescription>";
        String xml;
        try {
            int timeout = (int) Math.min(4000, remainingMillis(deadlineNanos));
            xml = client.request(deviceId, "POST", "/ISAPI/ContentMgmt/search", query, timeout);
        } catch (RuntimeException failure) {
            throw new IOException("Recording index query failed", failure);
        }
        remainingMillis(deadlineNanos);
        List<Span> spans = parse(xml, track, searchId, searchStart, searchEnd);
        List<Boundary> boundaries = new ArrayList<>();
        for (int i = 1; i < spans.size(); i++) {
            Span previous = spans.get(i - 1);
            Span next = spans.get(i);
            if (!previous.start.isBefore(next.start) || !previous.end.isBefore(next.end)) {
                throw new IOException("Ambiguous recording index spans");
            }
            if (!previous.end.isBefore(next.start)) {
                boundaries.add(new Boundary(next.start, previous.end));
            }
        }
        LocalDateTime candidate = requestedEnd;
        // At most one move per indexed boundary. No further device queries or download retries.
        for (int step = 0; step <= boundaries.size(); step++) {
            for (Span span : spans) {
                if (near(candidate, span.end)) {
                    // An unpaired tail is not evidence of a safe endpoint: require a continuous
                    // following recording before either keeping or extending this candidate.
                    requireCoverage(spans, span.end, span.end.plusSeconds(25));
                }
            }
            LocalDateTime moved = candidate;
            for (Boundary boundary : boundaries) {
                if (near(candidate, boundary.earliest) || near(candidate, boundary.latest)) {
                    // A successful real probe ended 22 seconds after the old file end; use 25 conservatively.
                    LocalDateTime safe = boundary.latest.plusSeconds(25);
                    if (safe.isAfter(moved)) moved = safe;
                }
            }
            if (moved.isAfter(searchEnd) || moved.isAfter(start.plusHours(TimeRange.MAX_RANGE_HOURS))) {
                throw new IOException("Safe recording end exceeds bounded extension/range");
            }
            requireCoverage(spans, requestedEnd, moved);
            if (moved.equals(candidate)) {
                remainingMillis(deadlineNanos);
                return candidate;
            }
            candidate = moved;
        }
        throw new IOException("Cannot find safe recording end in bounded index");
    }

    private long remainingMillis(long deadlineNanos) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoTime.getAsLong());
        if (remaining < 1000) throw new IOException("Recording deadline exhausted during boundary preflight");
        return remaining;
    }

    private static boolean near(LocalDateTime time, LocalDateTime boundary) {
        return !time.isBefore(boundary.minusSeconds(5)) && !time.isAfter(boundary.plusSeconds(5));
    }

    private static void requireCoverage(List<Span> spans, LocalDateTime from, LocalDateTime to) throws IOException {
        LocalDateTime covered = null;
        for (Span span : spans) {
            if (covered == null) {
                if (!span.start.isAfter(from) && !span.end.isBefore(from)) covered = span.end;
            } else if (!span.start.isAfter(covered) && span.end.isAfter(covered)) {
                covered = span.end;
            }
            if (covered != null && !covered.isBefore(to)) return;
        }
        throw new IOException("Recording index does not continuously cover requested/safe end");
    }

    private static List<Span> parse(String xml, int track, String searchId,
                                    LocalDateTime searchStart, LocalDateTime searchEnd) throws IOException {
        if (xml == null || xml.isBlank() || xml.length() > 65536) throw new IOException("Empty/oversized recording index");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"CMSearchResult".equals(root.getLocalName()) || !XML_NS.equals(root.getNamespaceURI())) {
                throw new IOException("Unsupported recording index root/namespace");
            }
            if (!"true".equals(value(root, "responseStatus")) || !"OK".equals(value(root, "responseStatusStrg"))) {
                throw new IOException("Recording index failed/incomplete (pagination is unsupported)");
            }
            List<Element> ids = children(root, "searchID");
            if (ids.size() > 1 || (ids.size() == 1 && !searchId.equals(
                    ids.getFirst().getTextContent().trim().replaceFirst("^\\{([^{}]+)}$", "$1")))) {
                throw new IOException("Recording index search ID mismatch");
            }
            int matches = Integer.parseInt(value(root, "numOfMatches"));
            List<Element> items = children(one(root, "matchList"), "searchMatchItem");
            if (matches < 1 || matches > MAX_MATCHES || matches != items.size()) {
                throw new IOException("Recording index match count invalid/incomplete");
            }
            List<Span> spans = new ArrayList<>();
            for (Element item : items) {
                if (!Integer.toString(track).equals(value(item, "trackID"))) throw new IOException("Foreign recording track");
                Element time = one(item, "timeSpan");
                LocalDateTime start = parseDeviceTime(value(time, "startTime"));
                LocalDateTime end = parseDeviceTime(value(time, "endTime"));
                if (!start.isBefore(end) || end.isBefore(searchStart) || start.isAfter(searchEnd)) {
                    throw new IOException("Invalid/out-of-query recording span");
                }
                if (!"video".equals(value(one(item, "mediaSegmentDescriptor"), "contentType"))) {
                    throw new IOException("Unsupported recording content type");
                }
                spans.add(new Span(start, end));
            }
            spans.sort(Comparator.comparing(Span::start).thenComparing(Span::end));
            return spans;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Invalid recording index XML", error);
        }
    }

    private static LocalDateTime parseDeviceTime(String text) throws IOException {
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")) {
            throw new IOException("Unsupported recording timestamp convention");
        }
        return LocalDateTime.parse(text.substring(0, text.length() - 1), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static String value(Element parent, String name) throws IOException {
        Element field = one(parent, name);
        for (Node node = field.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) throw new IOException("Nested recording index scalar: " + name);
        }
        return field.getTextContent().trim();
    }

    private static Element one(Element parent, String name) throws IOException {
        List<Element> found = children(parent, name);
        if (found.size() != 1) throw new IOException("Missing/duplicate recording index field: " + name);
        return found.getFirst();
    }

    private static List<Element> children(Element parent, String name) throws IOException {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                if (!XML_NS.equals(element.getNamespaceURI())) throw new IOException("Foreign recording index namespace");
                if (name.equals(element.getLocalName())) found.add(element);
            }
        }
        return found;
    }

    private record Span(LocalDateTime start, LocalDateTime end) { }
    private record Boundary(LocalDateTime earliest, LocalDateTime latest) { }
}
