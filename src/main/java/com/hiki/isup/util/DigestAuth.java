package com.hiki.isup.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP Digest 认证（RFC 2617）计算。
 *
 * <p>海康设备的 ISAPI 走 Digest，不支持 Basic。JDK 的 HttpClient 不会自动完成握手，
 * 这里手工算 {@code Authorization} 头，便于控制重试与排查。</p>
 */
public final class DigestAuth {

    /** 从 401 响应头里取一个属性值，值可带引号 */
    private static final Pattern ATTR = Pattern.compile("(\\w+)=[\"]?([^\",]*)[\"]?");

    private DigestAuth() {
    }

    /** 解析 {@code WWW-Authenticate} 头里的指定属性，取不到返回空串 */
    public static String challengeAttr(String header, String name) {
        if (header == null) {
            return "";
        }
        Matcher matcher = ATTR.matcher(header);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(name)) {
                return matcher.group(2).trim();
            }
        }
        return "";
    }

    /**
     * 生成 {@code Authorization} 头的值（不含头名）。
     *
     * @param qop 质询里的 qop（如 {@code auth}），为空时按 RFC 2069 计算
     */
    public static String authorization(String username, String password, String method, String uri,
                                       String realm, String nonce, String opaque, String qop) {
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String cnonce = cnonce();
        String response;
        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(username).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        if (qop != null && !qop.isBlank()) {
            response = md5(ha1 + ":" + nonce + ":00000001:" + cnonce + ":" + qop + ":" + ha2);
            sb.append("qop=").append(qop).append(", ");
            sb.append("nc=00000001, ");
            sb.append("cnonce=\"").append(cnonce).append("\", ");
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        sb.append("response=\"").append(response).append("\"");
        if (opaque != null && !opaque.isBlank()) {
            sb.append(", opaque=\"").append(opaque).append("\"");
        }
        return sb.toString();
    }

    static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    private static String cnonce() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
