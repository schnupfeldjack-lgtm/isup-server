package com.hiki.isup.controller;

import com.hiki.isup.config.ApiKeyInterceptor;
import com.hiki.isup.service.DeviceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP API 测试：鉴权、参数校验、状态查询。
 * 该测试不加载海康 SDK（isup.enabled=false），只验证接口契约。
 *
 * <p>这里的 properties 是<b>内联测试属性</b>，优先级高于一切 config data（包括
 * {@code src/test/resources/application.yml} 和开发者本地 {@code ./config/application.yml}）。
 * 必须写在这里：本机根目录的 config/application.yml 会被 Spring Boot 自动加载且优先级更高，
 * 若不内联覆盖，isup.api-key 会变成真实配置里的值，导致全部业务接口用例拿到 401。</p>
 */
@SpringBootTest(properties = {
        "isup.enabled=false",
        "isup.api-key=" + IsupApiWebTest.API_KEY,
        "isup.public-ip=203.0.113.10",
        "isup.ehome-key=test-ehome-key",
        "isup.media-dir=target/test-media"
})
@AutoConfigureMockMvc
class IsupApiWebTest {

    /** 包级可见：注解（位于类体作用域之外）需要引用它作常量表达式 */
    static final String API_KEY = "test-api-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceRegistry deviceRegistry;

    @Test
    @DisplayName("健康检查不需要鉴权")
    void healthNoAuth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.cmsPort").value(7660))
                .andExpect(jsonPath("$.previewPort").value(8003))
                .andExpect(jsonPath("$.playbackPort").value(8004));
    }

    @Test
    @DisplayName("缺少 X-API-Key 返回 401")
    void missingApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("错误的 X-API-Key 返回 401")
    void wrongApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/devices").header(ApiKeyInterceptor.API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("正确的 X-API-Key 可以访问业务接口")
    void devices() throws Exception {
        deviceRegistry.online(1001, "GW4206623", "1.2.3.4", "SN123", null);

        mockMvc.perform(get("/api/v1/devices").header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("GW4206623"))
                .andExpect(jsonPath("$[0].online").value(true));
    }

    @Test
    @DisplayName("预览请求缺少 deviceId 返回 400")
    void previewValidation() throws Exception {
        mockMvc.perform(post("/api/v1/preview")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("预览 channel 非法返回 400")
    void previewInvalidChannel() throws Exception {
        mockMvc.perform(post("/api/v1/preview")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"GW4206623\",\"channel\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("回放时间格式非法返回 400")
    void playbackInvalidTime() throws Exception {
        mockMvc.perform(post("/api/v1/playback")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"GW4206623\",\"channel\":1,"
                                + "\"startTime\":\"2026-09-19 14:10:00\",\"endTime\":\"2026-09-19 14:00:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("startTime 必须早于 endTime")));
    }

    @Test
    @DisplayName("SDK 未就绪时预览返回 500 且带 operation 信息")
    void previewWhenSdkNotReady() throws Exception {
        mockMvc.perform(post("/api/v1/preview")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"GW4206623\",\"channel\":1,\"streamType\":0}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ISUP_ERROR"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("NET_ECMS_StartGetRealStreamV11")))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("ISUP SDK 未就绪")));
    }

    @Test
    @DisplayName("sessionId 格式非法返回 400")
    void invalidSessionId() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/abc")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("格式非法")));
    }

    @Test
    @DisplayName("查询不存在的会话返回 404")
    void sessionNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/8f14e45f-ceea-467a-9f0b-3c2f9d0e1111")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("停止不存在的会话返回 404，不会抛内部错误")
    void stopNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/8f14e45f-ceea-467a-9f0b-3c2f9d0e1111")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("会话列表接口需要鉴权，有权限时返回数组（含 ERP 等外部发起的会话）")
    void sessionsList() throws Exception {
        mockMvc.perform(get("/api/v1/sessions"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/sessions").header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("请求体不是合法 JSON 时返回 400")
    void badJson() throws Exception {
        mockMvc.perform(post("/api/v1/preview")
                        .header(ApiKeyInterceptor.API_KEY_HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("健康检查接口展示在线设备数与会话数")
    void healthShowsCounters() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.onlineDevices").exists())
                .andExpect(jsonPath("$.activeSessions").exists());
    }
}
