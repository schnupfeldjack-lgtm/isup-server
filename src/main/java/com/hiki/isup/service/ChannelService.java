package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.ChannelProbe;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.request.ChannelDetectRequest;
import com.hiki.isup.model.response.ChannelDetectResponse;
import com.hiki.isup.model.response.ChannelListResponse;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.util.TimeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import org.xml.sax.InputSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 查询设备（录像机）下的通道列表。
 *
 * <p>通过 SDK 的 ISUP 透传查询真实通道信息，失败时报告原因。
 * 录像探测独立走 ISUP 文件检索，不依赖通道名称查询。</p>
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    /**
     * 取通道列表的 ISAPI 地址，按序尝试。
     *
     * <p>必须带 HTTP 方法前缀（{@code GET }）：SDK 把这一整串当请求行透传给设备，
     * 少了方法前缀设备不认，直接返回错误码。</p>
     */
    private static final List<String> CHANNEL_URLS = List.of(
            "GET /ISAPI/System/Video/inputs/channels",
            "GET /ISAPI/ContentMgmt/InputProxy/channels");

    /** 探测默认探到第几通道 */
    private static final int DEFAULT_MAX_CHANNEL = 8;

    /** 探测最多允许探到第几通道，避免一次起太多会话 */
    private static final int MAX_CHANNEL_LIMIT = 32;

    /** 单通道检索等待最长时间（毫秒）。SDK 返回需等待时在此时限内重试 */
    private static final long PROBE_WAIT_MILLIS = 6000;

    /** GET 通道查询降级探测时，允许回看的最大分钟数 */
    private static final int MAX_PROBE_MINUTES = 1440;

    /** 检索结果未就绪时的重试间隔（毫秒） */
    private static final long PROBE_POLL_MILLIS = 200;

    /** 单次检索最多取回的文件条数。探测只需要知道有没有，1 条就够 */
    private static final int PROBE_MAX_FILES = 1;

    private final IsupService isupService;
    private final DeviceRegistry deviceRegistry;
    private final DeviceIsapiClient isapiClient;

    /** 单通道检索等待时长（毫秒）。包内可见，便于测试调小。 */
    private long probeWaitMillis = PROBE_WAIT_MILLIS;

    public ChannelService(IsupService isupService,
                          DeviceRegistry deviceRegistry,
                          DeviceIsapiClient isapiClient) {
        this.isupService = isupService;
        this.deviceRegistry = deviceRegistry;
        this.isapiClient = isapiClient;
    }

    /**
     * 查询某台设备的通道列表。
     *
     * <p>仅走 ISUP 透传。两条 ISAPI 路径都问不到、
     * 且调用方带了 {@code probeMinutes} 时，降级为逐通道探测。</p>
     *
     * @param deviceId 设备注册时上报的 DeviceID
     * @param probeMinutes 透传失败时的降级探测时段（最近多少分钟）；null 表示不降级
     */
    public ChannelListResponse queryChannels(String deviceId, Integer probeMinutes) {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty()) {
            throw new BadRequestException("deviceId 不能为空");
        }
        ChannelListResponse response = newResponse(id);
        response.setChannels(ChannelStations.group(fetchChannels(id, probeMinutes)));
        return response;
    }

    /**
     * 设备原始通道（通道号 + 设备端名称），给出库录像按 {@code channel_code} 匹配用。
     */
    public List<DeviceChannel> listChannels(String deviceId) {
        String id = deviceId == null ? "" : deviceId.trim();
        return fetchChannels(id, null);
    }

    private List<DeviceChannel> fetchChannels(String id, Integer probeMinutes) {
        if (id.isEmpty()) {
            throw new BadRequestException("deviceId 不能为空");
        }
        isupService.requireReady("NET_ECMS_ISAPIPassThrough");
        int lUserId = deviceRegistry.requireLUserId(id, "查询通道");

        StringBuilder attempts = new StringBuilder();
        for (String url : CHANNEL_URLS) {
            String xml;
            try {
                xml = isapiClient.get(id, url.substring("GET ".length()));
            } catch (IsupException | IllegalStateException e) {
                appendAttempt(attempts, url + " " + e.getMessage());
                continue;
            }
            List<DeviceChannel> channels;
            try {
                channels = parseChannels(xml);
            } catch (RuntimeException e) {
                appendAttempt(attempts, url + " 解析失败 " + e.getMessage());
                continue;
            }
            if (channels.isEmpty()) {
                appendAttempt(attempts, url + " 未返回通道");
                continue;
            }
            log.debug("查询设备通道成功：deviceId={}, url={}, 通道数={}", id, url, channels.size());
            return channels;
        }

        // 尝试过的路径、错误码这类排查信息只进日志；响应里给调用方一句能照做的话
        log.warn("查询设备通道失败：deviceId={}，已尝试 {}（{}）", id, String.join("、", CHANNEL_URLS), attempts);
        String suggestion = "可改用逐通道探测 POST /api/v1/devices/" + id + "/channels/detect，"
                + "或到录像机网页「通道管理」核对通道号";

        if (probeMinutes != null) {
            if (probeMinutes < 1 || probeMinutes > MAX_PROBE_MINUTES) {
                throw new BadRequestException("probeMinutes 取值 1~" + MAX_PROBE_MINUTES + "，当前值=" + probeMinutes);
            }
            LocalDateTime end = LocalDateTime.now();
            LocalDateTime start = end.minusMinutes(probeMinutes);
            List<ChannelProbe> probes = probeChannels(id, lUserId, start, end, DEFAULT_MAX_CHANNEL, Map.of());
            List<DeviceChannel> channels = new ArrayList<>();
            for (ChannelProbe probe : probes) {
                if (probe.isHasRecording()) {
                    channels.add(new DeviceChannel(probe.getChannel(), probe.getName()));
                }
            }
            if (!channels.isEmpty()) {
                return channels;
            }
            // 设备能力缺失属于调用方换个方式就能绕过的场景，不是服务端故障：
            // 走 400 + WARN（不带堆栈），避免被当成 500 内部错误。
            throw new BadRequestException("设备 " + id + " 在最近 " + probeMinutes
                    + " 分钟内没有任何通道的录像，请换更长的时间段或确认录像机正在录像。" + suggestion);
        }
        throw IsupException.failed("查询设备通道", id, null, null,
                "未取得有效通道列表，ISUP 查询详情：" + attempts + "。" + suggestion);
    }

    /**
     * 逐通道探测：对 1..maxChannel 各检索一小段录像，看哪些通道有文件。
     *
     * <p>走 {@code NET_ECMS_StartFindFile_V11}，不拉码流、不启 ffmpeg。
     * 只能反映该时段有录像的通道，没录过像的通道判为无数据。</p>
     */
    public ChannelDetectResponse detectChannels(String deviceId, ChannelDetectRequest request) {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty()) {
            throw new BadRequestException("deviceId 不能为空");
        }
        if (request == null) {
            throw new BadRequestException("请求体不能为空");
        }
        LocalDateTime[] range = TimeRange.validate(request.getStartTime(), request.getEndTime());
        int maxChannel = request.getMaxChannel() == null ? DEFAULT_MAX_CHANNEL : request.getMaxChannel();
        if (maxChannel > MAX_CHANNEL_LIMIT) {
            throw new BadRequestException("maxChannel 不能超过 " + MAX_CHANNEL_LIMIT + "，当前值=" + maxChannel);
        }

        isupService.requireReady("NET_ECMS_StartFindFile_V11");
        int lUserId = deviceRegistry.requireLUserId(id, "通道探测");

        List<ChannelProbe> probes = probeChannels(id, lUserId, range[0], range[1], maxChannel, Map.of());
        List<Integer> available = new ArrayList<>();
        for (ChannelProbe probe : probes) {
            if (probe.isHasRecording()) {
                available.add(probe.getChannel());
            }
        }

        ChannelDetectResponse response = new ChannelDetectResponse();
        response.setDeviceId(id);
        DeviceInfo info = deviceRegistry.get(id);
        if (info != null && info.getSerialNumber() != null) {
            response.setSerialNumber(info.getSerialNumber());
        }
        response.setStartTime(request.getStartTime());
        response.setEndTime(request.getEndTime());
        response.setMaxChannel(maxChannel);
        response.setProbes(probes);
        response.setAvailableChannels(available);
        log.info("通道探测完成：deviceId={}, 时段={}~{}，可用通道={}", id, range[0], range[1], available);
        return response;
    }

    /**
     * 逐通道回放探测，返回每个通道是否有录像数据。
     *
     * @param deviceNames 设备返回的通道名（通道号 → 名称），取不到时传空 Map，名称留空
     */
    private List<ChannelProbe> probeChannels(String deviceId, int lUserId,
                                             LocalDateTime start, LocalDateTime end,
                                             int maxChannel, Map<Integer, String> deviceNames) {
        HCISUPCMS cms = isupService.cms();
        List<ChannelProbe> probes = new ArrayList<>();
        for (int channel = 1; channel <= maxChannel; channel++) {
            ChannelProbe probe = probeChannel(cms, deviceId, lUserId, channel, start, end);
            String name = deviceNames == null ? null : deviceNames.get(channel);
            if (name != null) {
                probe.setName(name);
            }
            probes.add(probe);
        }
        return probes;
    }

    /** 构造响应骨架：填好 deviceId 与序列号 */
    private ChannelListResponse newResponse(String deviceId) {
        ChannelListResponse response = new ChannelListResponse();
        DeviceInfo info = deviceRegistry.get(deviceId);
        if (info != null && info.getSerialNumber() != null) {
            response.setSerialNumber(info.getSerialNumber());
        }
        return response;
    }

    /**
     * 探测单个通道：检索该时段是否有录像文件，不发起回放、不收码流。
     */
    private ChannelProbe probeChannel(HCISUPCMS cms, String deviceId, int lUserId,
                                      int channel, LocalDateTime start, LocalDateTime end) {
        HCISUPCMS.NET_EHOME_REC_FILE_COND cond = new HCISUPCMS.NET_EHOME_REC_FILE_COND();
        cond.dwChannel = channel;
        cond.dwRecType = HCISUPCMS.REC_TYPE_ALL;
        cond.dwStartIndex = 0;
        cond.dwMaxFileCountPer = PROBE_MAX_FILES;
        PlaybackService.fillTimeForTest(cond.struStartTime, start);
        PlaybackService.fillTimeForTest(cond.struStopTime, end);
        cond.byLocalOrUTC = 0;
        cond.write();

        int handle = cms.NET_ECMS_StartFindFile_V11(lUserId, HCISUPCMS.ENUM_SEARCH_RECORD_FILE, cond, cond.size());
        if (handle < 0) {
            int code = cms.NET_ECMS_GetLastError();
            log.info("通道检索未开始：deviceId={}, channel={}, errorCode={}", deviceId, channel, code);
            return new ChannelProbe(channel, false, code, "NET_ECMS_StartFindFile_V11 失败");
        }
        try {
            boolean hasFile = findHasFile(cms, handle);
            return new ChannelProbe(channel, hasFile, null,
                    hasFile ? "检索到录像文件" : "无数据（通道不存在或该时段无录像）");
        } finally {
            cms.NET_ECMS_StopFindFile(handle);
        }
    }

    /** 单通道检索等待时长。包内可见，便于测试调小。 */
    void setProbeWaitMillis(long probeWaitMillis) {
        this.probeWaitMillis = probeWaitMillis;
    }

    private boolean findHasFile(HCISUPCMS cms, int handle) {
        long deadline = System.currentTimeMillis() + probeWaitMillis;
        while (true) {
            HCISUPCMS.NET_EHOME_REC_FILE file = new HCISUPCMS.NET_EHOME_REC_FILE();
            file.dwSize = file.size();
            file.write();
            int status = cms.NET_ECMS_FindNextFile_V11(handle, file, file.size());
            if (status == HCISUPCMS.ENUM_GET_NEXT_STATUS_SUCCESS) {
                return true;
            }
            if (status == HCISUPCMS.ENUM_GET_NEXT_STATUS_NOFIND
                    || status == HCISUPCMS.ENUM_GET_NEXT_STATUS_FINISH) {
                return false;
            }
            if (status != HCISUPCMS.ENUM_GET_NEXT_STATUS_NEEDWAIT
                    || System.currentTimeMillis() >= deadline) {
                log.info("通道检索结束：handle={}, status={}", handle, status);
                return false;
            }
            try {
                Thread.sleep(Math.min(PROBE_POLL_MILLIS, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 从通道清单 XML 里提取通道号与名称，按通道号升序去重。
     *
     * <p>不同型号返回的标签名不同（{@code VideoInputChannel} / {@code InputProxyChannel}），
     * 统一按“标签以 Channel 结尾”匹配，再取其 {@code id} 与 {@code name} 子元素。</p>
     *
     * <p>响应来自设备，按不可信输入处理：关闭 DTD 与外部实体，防 XML 外部实体注入。</p>
     *
     * <p>包内可见，便于测试。</p>
     */
    static List<DeviceChannel> parseChannels(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        try {
            Document document = newSecureDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList nodes = document.getElementsByTagName("*");
            Map<Integer, String> found = new TreeMap<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element element) || !localName(element).endsWith("Channel")) {
                    continue;
                }
                int channel = childInt(element, "id");
                if (channel <= 0) {
                    continue;
                }
                String name = childText(element, "name");
                if (name.isEmpty()) {
                    name = childText(element, "channelName");
                }
                found.putIfAbsent(channel, name);
            }
            List<DeviceChannel> result = new ArrayList<>();
            found.forEach((channel, name) -> result.add(new DeviceChannel(channel, name)));
            return result;
        } catch (Exception e) {
            throw IsupException.wrap("解析通道列表 XML", null, null, null, e);
        }
    }

    private static DocumentBuilder newSecureDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }

    /** 取元素标签名（忽略命名空间前缀） */
    private static String localName(Element element) {
        String name = element.getLocalName();
        if (name == null) {
            name = element.getNodeName();
        }
        int colon = name == null ? -1 : name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    /** 取指定名称的直接子元素的文本，不存在返回空串 */
    private static String childText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child && tag.equals(localName(child))) {
                return child.getTextContent() == null ? "" : child.getTextContent().trim();
            }
        }
        return "";
    }

    /** 取指定名称的直接子元素的整数值，非法返回 -1 */
    private static int childInt(Element parent, String tag) {
        String text = childText(parent, tag);
        if (text.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void appendAttempt(StringBuilder attempts, String text) {
        if (attempts.length() > 0) {
            attempts.append("；");
        }
        attempts.append(text);
    }
}
