package com.hiki.isup.service;

import com.hiki.isup.model.request.OverlayRecipient;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.util.XmlCharset;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 打包字幕最多使用 1~8 号字符块，完整排版失败时不截断收件地址。 */
final class PackingOverlayLayout {
    static final int LAST_OVERLAY_ID = 8;
    private static final Pattern SIZE = Pattern.compile("<(?:\\w+:)?TextOverlayList\\b[^>]*\\bsize\\s*=\\s*[\"'](\\d+)[\"']");

    private PackingOverlayLayout() { }

    static List<String> lines(String xml, OverlayTextRequest request) {
        validateWaybill(xml, request);
        if (clean(request.getWaybillNo()).isEmpty()) return List.of();
        OverlayRecipient recipient = request.getRecipient();
        if (recipient != null && (!clean(request.getWaybillNo()).equals(clean(recipient.waybillNo()))
                || clean(recipient.applyRecordId()).isEmpty())) {
            throw new IllegalArgumentException("收件地址与当前面单申请身份不一致");
        }
        if (recipient != null && (clean(recipient.address()).isEmpty() || clean(recipient.country()).isEmpty())) {
            throw new IllegalArgumentException("面单申请地址不完整");
        }
        Charset charset = XmlCharset.ofXml(xml);
        int width = request.getAddressLineWidth();
        int bytes = request.getAddressLineMaxBytes();
        if (width < 16 || bytes < 24) throw new IllegalArgumentException("字幕行宽配置过小");
        List<String> result = new ArrayList<>();
        append(result, "平台订单号：", request.getPlatformOrderNo(), charset, width, bytes);
        if (recipient == null) {
            if (capacity(xml) >= 2) append(result, "", "地址未获取", charset, width, bytes);
        } else {
            append(result, "收件人：", recipient.name(), charset, width, bytes);
            append(result, "地址：", recipient.address(), charset, width, bytes);
            List<String> regions = new ArrayList<>();
            append(regions, "地区：", join(recipient.district(), recipient.city(), recipient.state()), charset, width, bytes);
            List<String> footer = new ArrayList<>();
            appendPostcodeAndCountry(footer, recipient, charset, width, bytes);
            if (result.size() + regions.size() + footer.size() > capacity(xml) - 1
                    && !regions.isEmpty() && footer.size() == 1) {
                // 行数紧张时才合并末行地区与完整邮编/国家，保留原字段及阅读顺序。
                String combined = regions.getLast() + "  " + footer.getFirst();
                if (fitsLine(combined, charset, width, bytes)) {
                    regions.set(regions.size() - 1, combined);
                    footer.clear();
                }
            }
            result.addAll(regions);
            result.addAll(footer);
        }
        if (result.size() > capacity(xml) - 1) {
            throw new IllegalArgumentException("完整收件地址超出设备字幕行数，未下发截断地址");
        }
        return List.copyOf(result);
    }

    /** 单号必须完整显示在第一块，不能由字符编码替换或设备自行截断。 */
    static void validateWaybill(String xml, OverlayTextRequest request) {
        String text = request.resolveDisplayText().trim();
        validateXmlText(text, "快递单号");
        if (!fitsLine(text, XmlCharset.ofXml(xml), request.getAddressLineWidth(), request.getAddressLineMaxBytes())) {
            throw new IllegalArgumentException("快递单号无法完整编码或超出单行显示限制，未下发截断单号");
        }
    }

    private static int capacity(String xml) {
        Matcher matcher = SIZE.matcher(xml);
        if (!matcher.find()) return LAST_OVERLAY_ID;
        try {
            return Math.min(LAST_OVERLAY_ID, Math.max(1, Integer.parseInt(matcher.group(1))));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("设备字幕行数无效");
        }
    }

    private static String join(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            String part = clean(value);
            // 仅合并完全相同的行政字段，保留第一次出现的写法和顺序，不处理地址子串。
            if (!part.isEmpty() && parts.stream().noneMatch(part::equalsIgnoreCase)) parts.add(part);
        }
        return String.join(", ", parts);
    }

    private static void appendPostcodeAndCountry(List<String> lines, OverlayRecipient recipient,
                                                 Charset charset, int width, int maxBytes) {
        String postcode = clean(recipient.postcode());
        String country = clean(recipient.country());
        if (!postcode.isEmpty()) {
            String combined = "邮编：" + postcode + "  国家：" + country;
            if (fitsLine(combined, charset, width, maxBytes)) {
                lines.add(combined);
                return;
            }
        }
        append(lines, "邮编：", postcode, charset, width, maxBytes);
        append(lines, "国家：", country, charset, width, maxBytes);
    }

    private static boolean fitsLine(String text, Charset charset, int width, int maxBytes) {
        validateXmlText(text, "字幕");
        int columns = text.codePoints().map(cp -> cp <= 0x7f ? 1 : 2).sum();
        return columns <= width && charset.newEncoder().canEncode(text)
                && text.getBytes(charset).length <= maxBytes;
    }

    private static void append(List<String> lines, String prefix, String value, Charset charset, int width, int maxBytes) {
        String remaining = clean(value);
        if (remaining.isEmpty()) return;
        remaining = prefix + remaining;
        validateXmlText(remaining, "字幕");
        if (!charset.newEncoder().canEncode(remaining)) {
            throw new IllegalArgumentException("设备字符集无法完整表示收件地址");
        }
        while (!remaining.isEmpty()) {
            int end = 0;
            int columns = 0;
            int lastSpace = -1;
            while (end < remaining.length()) {
                int cp = remaining.codePointAt(end);
                int next = end + Character.charCount(cp);
                int nextColumns = columns + (cp <= 0x7f ? 1 : 2);
                if (nextColumns > width || remaining.substring(0, next).getBytes(charset).length > maxBytes) break;
                if (cp == ' ') lastSpace = end;
                end = next;
                columns = nextColumns;
            }
            if (end == 0) throw new IllegalArgumentException("设备字幕无法容纳地址字符");
            if (end < remaining.length() && lastSpace > 0) end = lastSpace;
            lines.add(remaining.substring(0, end).trim());
            if (lines.size() >= LAST_OVERLAY_ID) {
                throw new IllegalArgumentException("完整收件地址超出设备字幕行数，未下发截断地址");
            }
            remaining = remaining.substring(end).trim();
        }
    }

    private static void validateXmlText(String text, String field) {
        if (text.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException(field + "含控制字符或无效 XML 字符，未下发错误内容");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cc}\\p{Z}\\s]+", " ").trim();
    }
}
