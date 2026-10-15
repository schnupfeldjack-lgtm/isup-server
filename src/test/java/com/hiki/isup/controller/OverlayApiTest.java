package com.hiki.isup.controller;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.hiki.isup.service.ChannelService;
import com.hiki.isup.service.DeviceRegistry;
import com.hiki.isup.service.DownloadService;
import com.hiki.isup.service.IsapiProxyService;
import com.hiki.isup.service.IsupService;
import com.hiki.isup.service.MediaUrlSigner;
import com.hiki.isup.service.PlaybackService;
import com.hiki.isup.service.PreviewService;
import com.hiki.isup.service.SessionManager;
import com.hiki.isup.service.StationOverlayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.ArgumentCaptor;
import com.hiki.isup.model.request.OverlayRecipient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class OverlayApiTest {

    @Mock
    private IsupProperties properties;
    @Mock
    private IsupService isupService;
    @Mock
    private DeviceRegistry deviceRegistry;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private PreviewService previewService;
    @Mock
    private PlaybackService playbackService;
    @Mock
    private DownloadService downloadService;
    @Mock
    private ChannelService channelService;
    @Mock
    private StationOverlayService stationOverlayService;
    @Mock
    private IsapiProxyService isapiProxyService;
    @Mock
    private MediaUrlSigner mediaUrlSigner;

    private MockMvc mockMvc;

    @Test
    void forwardsPlatformOrderIndependentlyOfRecipientAvailability() throws Exception {
        mockMvc.perform(post("/api/v1/devices/D/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelCode\":\"打包台1\",\"waybillNo\":\"SF1\",\"platformOrderNo\":\"321123456789\"}"))
                .andExpect(status().isOk());
        verify(stationOverlayService).apply("D", "打包台1", "SF1", null, null, "321123456789");
    }

    @Test
    void forwardsFrozenRecipientAndGuardedClear() throws Exception {
        mockMvc.perform(post("/api/v1/devices/D/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelCode":"打包台1","waybillNo":"SF1","recipient":{
                                  "waybillNo":"SF1","applyRecordId":"R1","address":"Demo Road","country":"US"}}
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<OverlayRecipient> recipient = ArgumentCaptor.forClass(OverlayRecipient.class);
        verify(stationOverlayService).apply(eq("D"), eq("打包台1"), eq("SF1"), recipient.capture(), isNull());
        assertEquals("R1", recipient.getValue().applyRecordId());
        assertEquals("Demo Road", recipient.getValue().address());
        mockMvc.perform(post("/api/v1/devices/D/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelCode\":\"打包台1\",\"waybillNo\":\"\",\"expectedWaybillNo\":\"SF1\"}"))
                .andExpect(status().isOk());
        verify(stationOverlayService).apply("D", "打包台1", "", null, "SF1");
    }

    @BeforeEach
    void setUp() {
        IsupController controller = new IsupController(properties, isupService, deviceRegistry, sessionManager,
                previewService, playbackService, downloadService, channelService, stationOverlayService,
                isapiProxyService, mediaUrlSigner);
        mockMvc = standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    @DisplayName("两路字幕成功后返回成功回执")
    void stationWaybillReturnsAckOnly() throws Exception {
        mockMvc.perform(post("/api/v1/devices/DLWH1xx/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelCode\":\"打包台1\",\"waybillNo\":\"SF1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"msg\":\"成功\"}", true));

        verify(stationOverlayService).apply("DLWH1xx", "打包台1", "SF1234567890");
    }

    @Test
    @DisplayName("空单号也回成功")
    void blankWaybillStillAcks() throws Exception {
        mockMvc.perform(post("/api/v1/devices/DLWH1xx/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelCode\":\"打包台1\",\"waybillNo\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"code\":200,\"msg\":\"成功\"}", true));

        verify(stationOverlayService).apply("DLWH1xx", "打包台1", "");
    }

    @Test
    @DisplayName("叠加失败时返回真实错误")
    void failureReturnsError() throws Exception {
        doThrow(IsupException.failed("下发工位字幕", "DLWH1xx", 1, null, "设备未接受"))
                .when(stationOverlayService).apply("DLWH1xx", "打包台1", "SF1234567890");

        mockMvc.perform(post("/api/v1/devices/DLWH1xx/overlay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelCode\":\"打包台1\",\"waybillNo\":\"SF1234567890\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("ISUP_ERROR"))
                .andExpect(jsonPath("$.deviceId").value("DLWH1xx"));
    }
}
