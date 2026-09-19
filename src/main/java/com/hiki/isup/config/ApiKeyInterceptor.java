package com.hiki.isup.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * ERP 接口鉴权：X-API-Key 必须与 isup.api-key 一致。
 *
 * <p>只保护 /api/v1/**，/api/health 不鉴权。</p>
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-API-Key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyInterceptor.class);

    private final IsupProperties properties;

    public ApiKeyInterceptor(IsupProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String expected = properties.getApiKey();
        if (expected == null || expected.isBlank()) {
            log.error("未配置 isup.api-key，拒绝请求：uri={} remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            writeUnauthorized(response, "服务端未配置 isup.api-key，拒绝访问");
            return false;
        }

        String provided = request.getHeader(API_KEY_HEADER);
        if (provided == null || provided.isBlank()) {
            log.warn("缺少 {} 请求头：uri={} remoteAddr={}", API_KEY_HEADER, request.getRequestURI(), request.getRemoteAddr());
            writeUnauthorized(response, "缺少 " + API_KEY_HEADER + " 请求头");
            return false;
        }

        if (!constantTimeEquals(expected, provided)) {
            log.warn("API Key 校验失败：uri={} remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            writeUnauthorized(response, "API Key 校验失败");
            return false;
        }
        return true;
    }

    private static void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}
