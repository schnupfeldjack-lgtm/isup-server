package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaUrlSignerTest {

    private static final String SESSION_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final Instant NOW = Instant.parse("2026-09-22T08:00:00Z");

    @Test
    @DisplayName("签名绑定媒体类型、会话和过期时间")
    void signatureIsScopedAndExpires() {
        MediaUrlSigner signer = signer(NOW);
        String url = signer.sign("preview", SESSION_ID);
        String[] parts = url.split("/");

        assertTrue(signer.verify(parts[2], parts[3], parts[4], parts[5]));
        assertFalse(signer.verify(parts[2], parts[3], "playback", parts[5]));
        assertFalse(signer.verify(parts[2], parts[3], parts[4],
                "123e4567-e89b-12d3-a456-426614174001"));
        assertFalse(signer(NOW.plusSeconds(7201)).verify(parts[2], parts[3], parts[4], parts[5]));
    }

    @Test
    @DisplayName("畸形参数、非法类型与非法 sessionId 均被拒绝")
    void rejectsMalformedInput() {
        MediaUrlSigner signer = signer(NOW);

        assertFalse(signer.verify("not-a-number", "x", "preview", SESSION_ID));
        assertFalse(signer.verify("178", "***", "preview", SESSION_ID));
        assertThrows(IllegalArgumentException.class, () -> signer.sign("download", SESSION_ID));
        assertThrows(IllegalArgumentException.class, () -> signer.sign("preview", "../escape"));
    }

    @Test
    @DisplayName("媒体 URL 有效期被限制在 60 到 86400 秒")
    void ttlIsClamped() {
        IsupProperties low = properties();
        low.setMediaUrlTtlSeconds(1);
        assertTrue(new MediaUrlSigner(low, Clock.fixed(NOW, ZoneOffset.UTC))
                .sign("preview", SESSION_ID).startsWith("/media/" + (NOW.getEpochSecond() + 60) + "/"));

        IsupProperties high = properties();
        high.setMediaUrlTtlSeconds(999999);
        assertTrue(new MediaUrlSigner(high, Clock.fixed(NOW, ZoneOffset.UTC))
                .sign("preview", SESSION_ID).startsWith("/media/" + (NOW.getEpochSecond() + 86400) + "/"));
    }

    private MediaUrlSigner signer(Instant instant) {
        return new MediaUrlSigner(properties(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private IsupProperties properties() {
        IsupProperties properties = new IsupProperties();
        properties.setApiKey("test-api-key");
        properties.setMediaUrlTtlSeconds(7200);
        return properties;
    }
}
