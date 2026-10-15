package com.hiki.isup.model.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviewRequestTest {

    @Test
    @DisplayName("未指定码流时默认子码流")
    void defaultStreamTypeIsSubstream() {
        assertEquals(1, new PreviewRequest().getStreamType());
    }

    @Test
    @DisplayName("显式传入 null 时 getter 返回 null，由服务层回落到子码流")
    void explicitNullRemainsNull() {
        PreviewRequest request = new PreviewRequest();
        request.setStreamType(null);
        assertNull(request.getStreamType());
    }
}
