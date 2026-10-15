package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.request.ChannelDetectRequest;
import com.hiki.isup.model.response.ChannelDetectResponse;
import com.hiki.isup.model.response.ChannelListResponse;
import com.hiki.isup.sdk.HCISUPCMS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通道查询测试：XML 解析（含 XXE 防护）与 ISAPI 透传的回退 / 失败路径。
 * （JNA 结构体与 Mockito 桩均为纯内存操作，不需要真实 SDK）
 */
@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

    private static final String DEVICE_ID = "DLWH1xx";
    private static final String VIDEO_INPUT_URL = "GET /ISAPI/System/Video/inputs/channels";
    private static final String INPUT_PROXY_URL = "GET /ISAPI/ContentMgmt/InputProxy/channels";

    @Mock
    private IsupService isupService;
    @Mock
    private DeviceRegistry deviceRegistry;
    @Mock
    private HCISUPCMS cms;

    private ChannelService service;

    @BeforeEach
    void setUp() {
        service = new ChannelService(isupService, deviceRegistry, new DeviceIsapiClient(isupService, deviceRegistry));
    }

    @Test
    @DisplayName("解析视频输入通道清单：按通道号升序，取到名称")
    void parseVideoInputChannels() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <VideoInputChannelList>
                  <VideoInputChannel>
                    <id>2</id>
                    <name>细节</name>
                  </VideoInputChannel>
                  <VideoInputChannel>
                    <id>1</id>
                    <name>全景</name>
                  </VideoInputChannel>
                </VideoInputChannelList>
                """;

        List<DeviceChannel> channels = ChannelService.parseChannels(xml);

        assertEquals(2, channels.size());
        assertEquals(1, channels.get(0).getChannel());
        assertEquals("全景", channels.get(0).getName());
        assertEquals(2, channels.get(1).getChannel());
        assertEquals("细节", channels.get(1).getName());
    }

    @Test
    @DisplayName("解析 IP 通道清单（标签名不同也能识别）")
    void parseInputProxyChannels() {
        String xml = """
                <InputProxyChannelList>
                  <InputProxyChannel>
                    <id>3</id>
                    <name>IP Camera 01</name>
                  </InputProxyChannel>
                </InputProxyChannelList>
                """;

        List<DeviceChannel> channels = ChannelService.parseChannels(xml);

        assertEquals(1, channels.size());
        assertEquals(3, channels.get(0).getChannel());
        assertEquals("IP Camera 01", channels.get(0).getName());
    }

    @Test
    @DisplayName("无 id 或无通道节点的 XML 返回空列表")
    void parseEmptyResult() {
        assertTrue(ChannelService.parseChannels("<VideoInputChannelList></VideoInputChannelList>").isEmpty());
        assertTrue(ChannelService.parseChannels(null).isEmpty());
        assertTrue(ChannelService.parseChannels("   ").isEmpty());
    }

    @Test
    @DisplayName("带外部实体的 XML 直接拒绝解析，防 XXE")
    void rejectExternalEntity() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE channel [<!ENTITY xxe SYSTEM "file:///C:/Windows/win.ini">]>
                <VideoInputChannelList>
                  <VideoInputChannel><id>1</id><name>&xxe;</name></VideoInputChannel>
                </VideoInputChannelList>
                """;

        assertThrows(IsupException.class, () -> ChannelService.parseChannels(xml));
    }

    @Test
    @DisplayName("首个 ISAPI 地址无通道时回退到第二个地址")
    void fallbackToSecondUrl() {
        stubPassthrough(Map.of(
                VIDEO_INPUT_URL, "<VideoInputChannelList></VideoInputChannelList>",
                INPUT_PROXY_URL, "<InputProxyChannelList><InputProxyChannel><id>2</id>"
                        + "<name>打包台1细节</name></InputProxyChannel></InputProxyChannelList>"));
        stubDevice("查询通道");

        ChannelListResponse response = service.queryChannels(DEVICE_ID, null);

        assertEquals(1, response.getChannels().size());
        assertEquals("打包台1", response.getChannels().get(0).getChannelCode());
        assertEquals(2, response.getChannels().get(0).getDetailChannel());
        assertEquals("GW4206623", response.getSerialNumber());
    }

    @Test
    @DisplayName("首个 ISAPI 地址命中时直接返回，不再回退")
    void firstUrlWins() {
        stubPassthrough(Map.of(VIDEO_INPUT_URL,
                "<VideoInputChannelList><VideoInputChannel><id>1</id><name>打包台1全景</name>"
                        + "</VideoInputChannel></VideoInputChannelList>"));
        stubDevice("查询通道");

        ChannelListResponse response = service.queryChannels(DEVICE_ID, null);

        assertEquals("打包台1", response.getChannels().get(0).getChannelCode());
        assertEquals(1, response.getChannels().get(0).getPanoramaChannel());
    }

    @Test
    @DisplayName("所有查询均失败时返回明确异常")
    void allUrlsFailThrows() {
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any(HCISUPCMS.NET_EHOME_PTXML_PARAM.class)))
                .thenReturn(false);
        when(cms.NET_ECMS_GetLastError()).thenReturn(23);
        when(deviceRegistry.requireLUserId(eq(DEVICE_ID), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(isupService.cms()).thenReturn(cms);

        assertThrows(IsupException.class, () -> service.queryChannels(DEVICE_ID, null));
    }

    @Test
    @DisplayName("deviceId 为空时直接拒绝，不发起透传")
    void rejectBlankDeviceId() {
        assertThrows(BadRequestException.class, () -> service.queryChannels("  ", null));
    }

    @Test
    @DisplayName("通道探测：只把检索到录像的通道列为可用，且不发起回放")
    void detectChannelsWithData() {
        stubFindFile(Set.of(1, 2));
        stubDevice("通道探测");

        ChannelDetectResponse response = service.detectChannels(DEVICE_ID, detectRequest(4, null));

        assertEquals(List.of(1, 2), response.getAvailableChannels());
        assertEquals(4, response.getProbes().size());
        assertEquals("检索到录像文件", response.getProbes().get(0).getMessage());
        assertEquals("无数据（通道不存在或该时段无录像）", response.getProbes().get(2).getMessage());
        verify(cms, never()).NET_ECMS_StartPlayBack(anyInt(), any(), any());
        verify(cms, never()).NET_ECMS_StartPushPlayBack(anyInt(), any(), any());
    }

    @Test
    @DisplayName("通道探测：检索起不来时带上 SDK 错误码")
    void detectChannelFindFails() {
        when(cms.NET_ECMS_StartFindFile_V11(anyInt(), anyInt(), any(), anyInt())).thenReturn(-1);
        when(cms.NET_ECMS_GetLastError()).thenReturn(3701);
        stubDevice("通道探测");

        ChannelDetectResponse response = service.detectChannels(DEVICE_ID, detectRequest(1, null));

        assertEquals(List.of(), response.getAvailableChannels());
        assertEquals(3701, response.getProbes().get(0).getErrorCode());
        verify(cms, never()).NET_ECMS_StopFindFile(anyInt());
    }

    @Test
    @DisplayName("透传失败后探测可用通道，但不虚构工位名称")
    void fallbackToProbe() {
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any(HCISUPCMS.NET_EHOME_PTXML_PARAM.class)))
                .thenReturn(false);
        stubFindFile(Set.of(1));
        stubDevice("查询通道");

        ChannelListResponse response = service.queryChannels(DEVICE_ID, 30);

        assertEquals(0, response.getChannels().size(), "探测拿不到「全景/细节」名称时不合成工位");
    }

    @Test
    @DisplayName("通道探测：maxChannel 超过上限直接拒绝")
    void detectRejectTooManyChannels() {
        assertThrows(BadRequestException.class,
                () -> service.detectChannels(DEVICE_ID, detectRequest(1, 33)));
    }

    private ChannelDetectRequest detectRequest(int defaultChannels, Integer maxChannel) {
        ChannelDetectRequest request = new ChannelDetectRequest();
        request.setStartTime("2026-09-21 09:53:06");
        request.setEndTime("2026-09-21 09:53:21");
        request.setMaxChannel(maxChannel == null ? defaultChannels : maxChannel);
        return request;
    }

    @Test
    @DisplayName("通道查询通过 ISUP 返回工位名称")
    void passthroughProvidesStationNames() {
        stubPassthrough(Map.of(VIDEO_INPUT_URL,
                "<VideoInputChannelList><VideoInputChannel><id>1</id><name>打包台1全景</name>"
                        + "</VideoInputChannel></VideoInputChannelList>"));
        stubDevice("查询通道");
        assertEquals(1, service.queryChannels(DEVICE_ID, null).getChannels().size());
    }

    @Test
    @DisplayName("读取 SDK 在 dwOutSize 返回的长度，dwReturnedXMLLen 为零")
    void readsLengthFromOutSize() {
        stubDevice("查询通道");
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM param = call.getArgument(1);
            byte[] xml = ("<InputProxyChannelList><InputProxyChannel><id>1</id>"
                    + "<name>打包台1全景</name></InputProxyChannel></InputProxyChannelList>")
                    .getBytes(StandardCharsets.UTF_8);
            param.pOutBuffer.write(0, xml, 0, xml.length);
            param.dwOutSize = xml.length;
            param.dwReturnedXMLLen = 0;
            param.write();
            return true;
        });
        assertEquals(1, service.queryChannels(DEVICE_ID, null).getChannels().get(0).getPanoramaChannel());
    }

    @Test
    @DisplayName("SDK 返回所需长度超出容量时，不读取截断的 XML")
    void rejectsOversizedResponse() {
        when(deviceRegistry.requireLUserId(eq(DEVICE_ID), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(isupService.cms()).thenReturn(cms);
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM param = call.getArgument(1);
            param.dwReturnedXMLLen = param.dwOutSize + 1;
            param.write();
            return true;
        });
        assertThrows(IsupException.class, () -> service.queryChannels(DEVICE_ID, null));
        verify(cms, never()).NET_ECMS_GetLastError();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1024 * 1024})
    void rejectsEmptyOrInvalidOutSize(int returnedOutSize) {
        when(deviceRegistry.requireLUserId(eq(DEVICE_ID), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(isupService.cms()).thenReturn(cms);
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM param = call.getArgument(1);
            param.dwOutSize = returnedOutSize;
            param.write();
            return true;
        });
        assertThrows(IsupException.class, () -> service.queryChannels(DEVICE_ID, null));
        verify(cms, never()).NET_ECMS_GetLastError();
    }

    @Test
    void unchangedCapacityWithoutDataIsNotAValidResponse() {
        when(deviceRegistry.requireLUserId(eq(DEVICE_ID), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(isupService.cms()).thenReturn(cms);
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any())).thenReturn(true);
        IsupException error = assertThrows(IsupException.class,
                () -> service.queryChannels(DEVICE_ID, null));
        assertTrue(error.getMessage().contains("响应内容为空"));
        verify(cms, never()).NET_ECMS_GetLastError();
    }

    @Test
    void unchangedCapacityReadsOnlyWrittenXml() {
        stubDevice("查询通道");
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM param = call.getArgument(1);
            byte[] xml = ("<InputProxyChannelList><InputProxyChannel><id>2</id>"
                    + "<name>打包台1细节</name></InputProxyChannel></InputProxyChannelList>")
                    .getBytes(StandardCharsets.UTF_8);
            param.pOutBuffer.write(0, xml, 0, xml.length);
            return true;
        });
        assertEquals(2, service.queryChannels(DEVICE_ID, null).getChannels().get(0).getDetailChannel());
    }

    @Test
    @DisplayName("两个 ISUP 地址均失败时终止查询并保留 SDK 错误码")
    void failedPassthroughDoesNotRetryOtherTransports() {
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any())).thenReturn(false);
        when(cms.NET_ECMS_GetLastError()).thenReturn(23);
        when(deviceRegistry.requireLUserId(eq(DEVICE_ID), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(isupService.cms()).thenReturn(cms);
        IsupException error = assertThrows(IsupException.class, () -> service.queryChannels(DEVICE_ID, null));
        assertTrue(error.getMessage().contains("errorCode=23"));
        verify(cms, times(2)).NET_ECMS_ISAPIPassThrough(eq(1), any());
        verify(cms, never()).NET_ECMS_StartFindFile_V11(anyInt(), anyInt(), any(), anyInt());
    }

    /** 按通道号返回检索结果：有录像的通道第一条即成功，其余取完即止。 */
    private void stubFindFile(Set<Integer> channelsWithData) {
        final int[] channel = {0};
        when(cms.NET_ECMS_StartFindFile_V11(anyInt(), anyInt(), any(HCISUPCMS.NET_EHOME_REC_FILE_COND.class), anyInt()))
                .thenAnswer(invocation -> {
                    HCISUPCMS.NET_EHOME_REC_FILE_COND cond = invocation.getArgument(2);
                    channel[0] = cond.dwChannel;
                    return 11;
                });
        when(cms.NET_ECMS_FindNextFile_V11(anyInt(), any(HCISUPCMS.NET_EHOME_REC_FILE.class), anyInt()))
                .thenAnswer(invocation -> channelsWithData.contains(channel[0])
                        ? HCISUPCMS.ENUM_GET_NEXT_STATUS_SUCCESS
                        : HCISUPCMS.ENUM_GET_NEXT_STATUS_FINISH);
        when(cms.NET_ECMS_StopFindFile(anyInt())).thenReturn(true);
    }

    private void stubDevice(String operation) {
        DeviceInfo info = new DeviceInfo(DEVICE_ID, 1, "192.168.22.210");
        info.setSerialNumber("GW4206623");
        when(deviceRegistry.requireLUserId(eq(DEVICE_ID), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        when(deviceRegistry.get(DEVICE_ID)).thenReturn(info);
        when(isupService.cms()).thenReturn(cms);
    }

    /**
     * 按请求 URL 返回对应的 XML：URL 不在映射里表示设备不响应该地址（返回 false）。
     */
    private void stubPassthrough(Map<String, String> responses) {
        when(cms.NET_ECMS_ISAPIPassThrough(anyInt(), any(HCISUPCMS.NET_EHOME_PTXML_PARAM.class)))
                .thenAnswer(invocation -> {
                    HCISUPCMS.NET_EHOME_PTXML_PARAM param = invocation.getArgument(1);
                    byte[] urlBytes = param.pRequestUrl.getByteArray(0, param.dwRequestUrlLen);
                    String url = new String(urlBytes, StandardCharsets.UTF_8);
                    String xml = responses.get(url);
                    if (xml == null) {
                        return false;
                    }
                    byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
                    param.pOutBuffer.write(0, xmlBytes, 0, xmlBytes.length);
                    param.dwReturnedXMLLen = xmlBytes.length;
                    param.write();
                    return true;
                });
    }
}
