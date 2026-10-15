package com.hiki.isup.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 XML 声明判断报文字符集。
 *
 * <p>海康设备返回的 XML 声明里带 {@code encoding}，国内型号常见 GBK / GB2312，也有 UTF-8。
 * 不按声明解码会把中文读成乱码，改写后回写就等于把乱码写进设备。</p>
 */
public final class XmlCharset {

    private static final Pattern ENCODING =
            Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private XmlCharset() {
    }

    /** 取报文字节使用的字符集，声明缺失或不认识时按 UTF-8 */
    public static Charset ofBytes(byte[] data) {
        int head = Math.min(data.length, 256);
        Matcher matcher = ENCODING.matcher(new String(data, 0, head, StandardCharsets.ISO_8859_1));
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1).trim());
            } catch (Exception ignored) {
                // 声明了不认识的编码，按默认处理
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 取报文文本自身声明的字符集，用于把改写后的 XML 还原成字节 */
    public static Charset ofXml(String xml) {
        Matcher matcher = ENCODING.matcher(xml == null ? "" : xml.substring(0, Math.min(xml.length(), 256)));
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1).trim());
            } catch (Exception ignored) {
                // 声明了不认识的编码，按默认处理
            }
        }
        return StandardCharsets.UTF_8;
    }
}
