package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.util.MediaPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;

/** 为 HLS 会话目录签发和校验短期访问地址。 */
@Service
public class MediaUrlSigner {

    private static final Set<String> TYPES = Set.of("preview", "playback");
    private static final String VERSION = "v1";

    private final IsupProperties properties;
    private final Clock clock;

    @Autowired
    public MediaUrlSigner(IsupProperties properties) {
        this(properties, Clock.systemUTC());
    }

    MediaUrlSigner(IsupProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String sign(String type, String sessionId) {
        validateScope(type, sessionId);
        long expires = clock.instant().getEpochSecond() + properties.getMediaUrlTtlSeconds();
        String signature = signature(expires, type, sessionId);
        return "/media/" + expires + "/" + signature + "/" + type + "/" + sessionId + "/index.m3u8";
    }

    public boolean verify(String expiresText, String providedSignature, String type, String sessionId) {
        if (!TYPES.contains(type) || !MediaPaths.isSafeId(sessionId)
                || providedSignature == null || !providedSignature.matches("^[A-Za-z0-9_-]{32,64}$")) {
            return false;
        }
        final long expires;
        try {
            expires = Long.parseLong(expiresText);
        } catch (NumberFormatException e) {
            return false;
        }
        if (expires < clock.instant().getEpochSecond()) {
            return false;
        }
        String expected = signature(expires, type, sessionId);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                providedSignature.getBytes(StandardCharsets.US_ASCII));
    }

    private void validateScope(String type, String sessionId) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("媒体类型只支持 preview / playback：" + type);
        }
        if (!MediaPaths.isSafeId(sessionId)) {
            throw new IllegalArgumentException("sessionId 格式非法：" + sessionId);
        }
    }

    private String signature(long expires, String type, String sessionId) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 isup.api-key，无法签发媒体 URL");
        }
        try {
            byte[] derivedKey = MessageDigest.getInstance("SHA-256")
                    .digest(("isup-media-v1\u0000" + apiKey).getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derivedKey, "HmacSHA256"));
            String payload = VERSION + "\n" + expires + "\n" + type + "\n" + sessionId;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成媒体 URL 签名失败", e);
        }
    }
}
