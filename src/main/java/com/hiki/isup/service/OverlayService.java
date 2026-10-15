package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;

/**
 * 通道字符叠加（OSD）：把一段文字烧到设备输出的画面上。
 *
 * <p>采用「读—改—写」：先 GET 通道的 overlays 配置，在设备返回的原文上只替换叠加文字，
 * 再 PUT 回去。打包显示只归一化约定的开关、时间格式及行位置；清除保留系统水印配置，
 * 其余字段沿用设备原文，无需猜各型号的完整 XML 结构。</p>
 *
 * <p>叠加由设备端完成，设备本地存的录像和实时预览都会带上这段文字，
 * 服务端不做二次转码，CPU 开销可忽略。</p>
 *
 * <p>字幕读写均通过 ISUP 会话透传（见 {@link DeviceIsapiClient}）；写入失败不自动重发。</p>
 */
@Service
public class OverlayService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OverlayService.class);

    /**
     * overlays 的 ISAPI 地址模板，按序尝试。
     *
     * <p>录像机（NVR）挂的 IP 通道走 ContentMgmt/InputProxy，自带模拟通道走 System/Video/inputs，
     * 不同型号支持情况不同，取到非空响应即采用。</p>
     */
    private static final List<String> OVERLAY_PATH_TEMPLATES = List.of(
            "/ISAPI/System/Video/inputs/channels/%d/overlays",
            "/ISAPI/ContentMgmt/InputProxy/channels/%d/overlays");

    /** 前缀+单号写在同一块，避免两块同 Y 时设备把单号挤到通道名那一行 */
    private static final int PREFIX_OVERLAY_ID = 1;

    /** 旧版拆出去的单号块：下发时关闭，防止画面上再出现错位数字 */
    private static final int WAYBILL_OVERLAY_ID = 2;

    /** 常显前缀。duration 到期后 overlay 1 恢复成这一句 */
    static final String WAYBILL_PREFIX = "快递单号：";

    /** 时间水印固件自带左边距。时间块 X=16 时，通道名和业务字幕用 32 才能齐左缘。 */
    private static final int TIME_POSITION_X = 16;
    private static final int DEFAULT_POSITION_X = 32;
    private static final int DEFAULT_POSITION_Y = 480;

    /**
     * 三行固定行距，数值越小越靠下，且必须是 16 的倍数。
     * 时间 544、通道名 512、单号 480，间距都是 32。
     * 通道名若落在 528，和上面的时间只隔 16（贴在一起），和下面的单号却隔 48。
     */
    static final int LINE_SPACING = 32;
    static final int WAYBILL_POSITION_Y = 480;
    static final int CHANNEL_NAME_POSITION_Y = WAYBILL_POSITION_Y + LINE_SPACING;
    static final int TIME_POSITION_Y = CHANNEL_NAME_POSITION_Y + LINE_SPACING;

    /** 纵坐标：{@code <positionY>528</positionY>} */
    private static final Pattern POSITION_Y =
            Pattern.compile("<(?:\\w+:)?positionY\\b[^>]*>.*?</(?:\\w+:)?positionY>", Pattern.DOTALL);

    private static final Pattern POSITION_X =
            Pattern.compile("<(?:\\w+:)?positionX\\b[^>]*>.*?</(?:\\w+:)?positionX>", Pattern.DOTALL);

    /** 单块叠加区：{@code <TextOverlay> ... </TextOverlay>} */
    private static final Pattern OVERLAY_BLOCK =
            Pattern.compile("<(?:\\w+:)?TextOverlay\\b[^>]*>.*?</(?:\\w+:)?TextOverlay>", Pattern.DOTALL);

    /** 叠加区列表开标签：{@code <TextOverlayList size="8">} */
    private static final Pattern OVERLAY_LIST_OPEN =
            Pattern.compile("<((?:\\w+:)?TextOverlayList)\\b[^>]*>");

    /** 叠加文字：{@code <displayText>...</displayText>} */
    private static final Pattern DISPLAY_TEXT =
            Pattern.compile("<(?:\\w+:)?displayText\\b[^>]*?(?:\\s*/>|>.*?</(?:\\w+:)?displayText>)", Pattern.DOTALL);

    private static final Pattern ELEMENT_OPEN = Pattern.compile("^<([^\\s/>]+)([^>]*?)(?:\\s*/>|>)");

    /** 原文定位用：注释、CDATA、处理指令和属性中的 > 都不能改变元素深度。 */
    private static final Pattern XML_TOKEN = Pattern.compile(
            "<!--.*?-->|<!\\[CDATA\\[.*?\\]\\]>|<\\?.*?\\?>|<(?:[^<>\"']|\"[^\"]*\"|'[^']*')*>", Pattern.DOTALL);

    /** 叠加开关：{@code <enabled>...</enabled>} */
    private static final Pattern ENABLED =
            Pattern.compile("<(?:\\w+:)?enabled\\b[^>]*>.*?</(?:\\w+:)?enabled>", Pattern.DOTALL);

    /** 叠加区 id：{@code <id>...</id>} */
    private static final Pattern ID = Pattern.compile("<(?:\\w+:)?id\\b[^>]*>(.*?)</(?:\\w+:)?id>", Pattern.DOTALL);

    private final DeviceIsapiClient isapiClient;

    /** 自动关闭叠加的调度器，单线程够用；daemon 线程随 JVM 退出 */
    private final ScheduledExecutorService executor;

    /** 同一（deviceId, channel, overlayId）只保留一个待执行任务，新的会取消旧的 */
    private final Map<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /** 每个（deviceId, channel）最后一次真实写入设备的时间戳，用于写入节流 */
    private final Map<String, Long> lastWriteAt = new ConcurrentHashMap<>();

    private final Map<String, Object> channelLocks = new ConcurrentHashMap<>();

    @Value("${isup.overlay.address-line-width:44}")
    private int addressLineWidth = 44;

    @Value("${isup.overlay.address-line-max-bytes:64}")
    private int addressLineMaxBytes = 64;

    /**
     * 同一通道两次真实写入之间的最小间隔（秒）。0 表示不限流。
     *
     * <p>叠加配置每次 PUT 都会落到设备 flash，写入过密会触发设备的 flash 高频写保护，
     * 期间设备拒收配置写入。业务频率高时用这个参数把写入摊平。</p>
     */
    @Value("${isup.overlay.min-write-interval-sec:3}")
    private int minWriteIntervalSec = 3;

    /**
     * 目标内容与设备现状一致时跳过 PUT。重复下发同一个单号是常见场景，
     * 跳过可省掉大量无效写入。
     */
    @Value("${isup.overlay.skip-unchanged:true}")
    private boolean skipUnchanged = true;

    public OverlayService(DeviceIsapiClient isapiClient) {
        this.isapiClient = isapiClient;
        this.executor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "overlay-auto-close");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 下发通道叠加文字。
     *
     * @param deviceId 设备编号
     * @param channel  通道号，从 1 开始
     */
    public OverlayTextResponse apply(String deviceId, int channel, OverlayTextRequest request) {
        String key = (deviceId == null ? "" : deviceId.trim()) + "|" + channel;
        synchronized (channelLocks.computeIfAbsent(key, ignored -> new Object())) {
            return applyLocked(deviceId, channel, request);
        }
    }

    private OverlayTextResponse applyLocked(String deviceId, int channel, OverlayTextRequest request) {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty()) {
            throw new BadRequestException("deviceId 不能为空");
        }
        if (channel < 1) {
            throw new BadRequestException("channel 必须 ≥ 1，当前值=" + channel);
        }
        if (request == null) {
            throw new BadRequestException("请求体不能为空");
        }
        if (request.getWaybillNo() == null && request.getText() == null) {
            throw new BadRequestException("waybillNo（或兼容字段 text）不能为 null；"
                    + "要清空单号请传 waybillNo=\"\"（前缀「快递单号：」会保留）");
        }
        // 最终显示文本：优先 waybillNo + prefix，否则用兼容字段 text。
        // 空串是合法值，表示把这一块叠加区的文字清空
        String text = request.resolveDisplayText().trim();
        request.setAddressLineWidth(addressLineWidth);
        request.setAddressLineMaxBytes(addressLineMaxBytes);

        List<String> tried = new ArrayList<>();
        for (String template : OVERLAY_PATH_TEMPLATES) {
            String path = String.format(template, channel);

            String currentXml;
            try {
                currentXml = getIsapi(id, path, request.getTimeoutMs());
            } catch (RuntimeException e) {
                tried.add(path + "：" + e.getMessage());
                log.debug("叠加配置读取失败，换下一条路径：deviceId={}, path={}, 原因={}", id, path, e.getMessage());
                continue;
            }
            if (currentXml == null || currentXml.isBlank()) {
                tried.add(path + "：响应为空");
                continue;
            }
            if (!OVERLAY_LIST_OPEN.matcher(currentXml).find()) {
                tried.add(path + "：未返回 TextOverlayList");
                // 响应里没有叠加区列表：这条地址不是 OSD 配置（可能是设备的错误响应），换下一条
                continue;
            }

            OverlayTextResponse response = new OverlayTextResponse();
            response.setDeviceId(id);
            response.setChannel(channel);
            response.setIsapiPath(path);
            response.setCurrentXml(currentXml);

            if (outdatedPackingClear(currentXml, request)) {
                response.setApplied(true);
                response.setWritten(false);
                response.setSuperseded(true);
                response.setDisplayText(currentDisplayText(currentXml, PREFIX_OVERLAY_ID));
                response.setDeviceStatusString("当前画面已切换单号，跳过旧包裹清除");
                return response;
            }

            if (request.getOverlayId() == null) {
                request.setOverlayId(PREFIX_OVERLAY_ID);
            }

            String requestXml;
            String addressError = null;
            try {
                requestXml = rewrite(currentXml, request);
            } catch (IllegalArgumentException e) {
                if (!request.isPackingOverlay()) throw e;
                // 显示当前单号和缺失提示，并清掉前一单地址；不能把截断或错误地址写入画面。
                addressError = e.getMessage();
                OverlayTextRequest fallback = OverlayTextRequest.external(request.getWaybillNo());
                fallback.setPlatformOrderNo(request.getPlatformOrderNo());
                fallback.setAddressLineWidth(request.getAddressLineWidth());
                fallback.setAddressLineMaxBytes(request.getAddressLineMaxBytes());
                try {
                    PackingOverlayLayout.validateWaybill(currentXml, fallback);
                } catch (IllegalArgumentException waybillError) {
                    // 单号本身不可信时只保留固定前缀，不能留下上一包裹的业务字幕。
                    fallback.setWaybillNo("");
                    fallback.setPlatformOrderNo(null);
                }
                try {
                    requestXml = rewrite(currentXml, fallback);
                } catch (IllegalArgumentException platformError) {
                    // 平台号也无法完整显示时仍清掉上一单；保留失败回执，不写截断内容。
                    fallback.setPlatformOrderNo(null);
                    requestXml = rewrite(currentXml, fallback);
                }
                text = fallback.resolveDisplayText().trim();
            } catch (IllegalStateException e) {
                throw IsupException.failed("改写叠加文字", id, channel, null,
                        e.getMessage() + "；原始配置见 currentXml，可先用 POST /api/v1/devices/{deviceId}/isapi 探测");
            }
            response.setRequestXml(requestXml);

            if (request.isDryRun()) {
                response.setApplied(false);
                log.info("叠加文字 dryRun：deviceId={}, channel={}, path={}, text={}", id, channel, path, text);
                return response;
            }

            boolean packingMatches = request.isPackingOverlay() && packingOverlayMatches(requestXml, currentXml);
            if (skipUnchanged && (requestXml.equals(currentXml) || packingMatches)) {
                // XML 相同也要验收完整显示；设备规范化空元素/坐标时按语义跳过重复写入。
                response.setApplied(addressError == null && (!request.isPackingOverlay() || packingMatches));
                response.setWritten(false);
                response.setDeviceStatusString(addressError);
                if (request.isPackingOverlay() && !packingMatches) {
                    appendDeviceStatus(response, "设备字幕回读与目标不一致，未确认完整显示");
                }
                response.setDisplayText(text);
                String block = locateOverlayBlock(currentXml, request.getOverlayId());
                if (block != null) {
                    response.setDeviceActualPositionX(parseCoord(block, "positionX"));
                    response.setDeviceActualPositionY(parseCoord(block, "positionY"));
                }
                log.info("叠加内容未变化，跳过写入：deviceId={}, channel={}, text={}", id, channel, text);
                return response;
            }

            if (!request.isPackingOverlay()) requestXml = pinLineSpacing(requestXml);
            boolean packingShow = request.isPackingOverlay() && !WAYBILL_PREFIX.equals(text);
            String changedBlock = findChangedOverlayBlock(currentXml, requestXml, packingShow);
            if (changedBlock != null) {
                // 兜底回滚：避免坏值 PUT 到设备
                log.error("rewrite 误改了 {}，回滚到 currentXml 中的原块后重发", changedBlock);
                int blkStart = requestXml.indexOf("<" + changedBlock);
                if (blkStart >= 0) {
                    int blkEnd = requestXml.indexOf("</" + changedBlock + ">", blkStart);
                    if (blkEnd > 0) {
                        int closeLen = changedBlock.length() + 3;
                        Matcher mb = Pattern.compile("<" + changedBlock + "\\b[^>]*>.*?</" + changedBlock + ">", Pattern.DOTALL).matcher(currentXml);
                        if (mb.find()) {
                            requestXml = requestXml.substring(0, blkStart) + mb.group() + requestXml.substring(blkEnd + closeLen);
                        }
                    }
                }
                if (!request.isPackingOverlay()) requestXml = pinLineSpacing(requestXml);
            }

            reserveWriteSlot(id, channel);
            String putResponse;
            try {
                putResponse = putIsapi(id, path, requestXml, request.getTimeoutMs());
            } catch (RuntimeException e) {
                if (addressError == null) throw e;
                throw IsupException.failed("下发降级叠加文字", id, channel, null,
                        addressError + "；设备写入失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            boolean accepted = checkDeviceAccepted(id, channel, putResponse, response);
            response.setResponseXml(putResponse);
            response.setApplied(accepted && addressError == null);
            response.setWritten(accepted);
            response.setDisplayText(accepted ? text : currentDisplayText(currentXml, request.getOverlayId()));

            // 回读设备确认实际存储的坐标（设备 SDK 会归一化，如 y=520→528）
            try {
                String afterXml = getIsapi(id, path, request.getTimeoutMs());
                if (accepted && request.isPackingOverlay() && !packingOverlayMatches(requestXml, afterXml)) {
                    response.setApplied(false);
                    appendDeviceStatus(response, "设备字幕回读与目标不一致，未确认单号和完整地址");
                }
                if (afterXml != null && !afterXml.isBlank()) {
                    String block = locateOverlayBlock(afterXml, request.getOverlayId());
                    if (block != null) {
                        response.setDeviceActualPositionX(parseCoord(block, "positionX"));
                        response.setDeviceActualPositionY(parseCoord(block, "positionY"));
                    }
                }
            } catch (RuntimeException e) {
                log.debug("回读设备坐标失败（不影响下发）：{}", e.getMessage());
                if (request.isPackingOverlay()) {
                    response.setApplied(false);
                    appendDeviceStatus(response, "设备字幕回读失败，未确认单号和完整地址："
                            + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
            if (addressError != null) {
                String deviceStatus = response.getDeviceStatusString();
                response.setDeviceStatusString(deviceStatus == null || deviceStatus.isBlank()
                        ? addressError : addressError + "；设备结果：" + deviceStatus);
            }

            int overlayId = request.getOverlayId();
            cancelPending(id, channel, overlayId);
            if (accepted && hasWaybill(request) && request.getDurationSec() > 0) {
                LocalDateTime closeAt = LocalDateTime.now().plusSeconds(request.getDurationSec());
                scheduleHideWaybill(id, channel, overlayId, request.getDurationSec());
                response.setHideScheduled(true);
                response.setHideAt(closeAt);
            }
            log.info("叠加文字下发结果：deviceId={}, channel={}, path={}, accepted={}, applied={}",
                    id, channel, path, accepted, response.isApplied());
            return response;
        }

        throw IsupException.failed("读取通道叠加配置", id, channel, null,
                "已尝试 " + String.join("、", tried) + "；设备未返回含叠加文字的配置。"
                        + "可先用 POST /api/v1/devices/{deviceId}/isapi 探测该设备实际的 overlays 地址");
    }

    private String getIsapi(String deviceId, String path, Integer timeoutMs) {
        return timeoutMs == null
                ? isapiClient.get(deviceId, path)
                : isapiClient.get(deviceId, path, timeoutMs);
    }

    private String putIsapi(String deviceId, String path, String body, Integer timeoutMs) {
        return timeoutMs == null
                ? isapiClient.put(deviceId, path, body)
                : isapiClient.put(deviceId, path, body, timeoutMs);
    }

    /**
     * 在设备原文上改写叠加文字与开关，其余节点保持原样。
     *
     * <p>按文本替换而非重新序列化，避免丢掉设备特有的命名空间与字段顺序。
     * 已存在叠加区时只替换其文字与开关；不存在时新增一块。</p>
     */
    static String rewrite(String currentXml, OverlayTextRequest request) {
        if (request.isPackingOverlay()) {
            if (outdatedPackingClear(currentXml, request)) {
                return currentXml;
            }
            return rewritePacking(currentXml, request);
        }
        String result = rewriteBlock(currentXml, request);
        int id = request.getOverlayId() == null ? PREFIX_OVERLAY_ID : request.getOverlayId();
        if (id == PREFIX_OVERLAY_ID) {
            result = disableOverlayBlock(result, WAYBILL_OVERLAY_ID);
        }
        return pinLineSpacing(result);
    }

    private static void appendDeviceStatus(OverlayTextResponse response, String status) {
        String previous = response.getDeviceStatusString();
        response.setDeviceStatusString(previous == null || previous.isBlank() ? status : previous + "；" + status);
    }

    private static boolean outdatedPackingClear(String currentXml, OverlayTextRequest request) {
        if (!request.isPackingOverlay() || hasWaybill(request) || request.getExpectedWaybillNo() == null
                || request.getExpectedWaybillNo().isBlank()) return false;
        try {
            String current = packingBlocks(currentXml, false).get(PREFIX_OVERLAY_ID).get(1);
            return !current.equals(WAYBILL_PREFIX + request.getExpectedWaybillNo().trim());
        } catch (Exception e) {
            throw new IllegalStateException("无法核验画面当前单号，未执行清除", e);
        }
    }

    private static String rewritePacking(String currentXml, OverlayTextRequest request) {
        List<String> lines = PackingOverlayLayout.lines(currentXml, request);
        String result = rewriteBlock(currentXml, request);
        String waybill = locateOverlayBlock(result, PREFIX_OVERLAY_ID);
        if (waybill == null) throw new IllegalStateException("设备字幕结构异常，缺少单号字幕块");
        if (hasWaybill(request)) {
            String positionedWaybill = replaceElementText(waybill, POSITION_X, Integer.toString(DEFAULT_POSITION_X));
            if (positionedWaybill == null) throw new IllegalStateException("单号字幕块缺少横坐标");
            int waybillStart = result.indexOf(waybill);
            result = result.substring(0, waybillStart) + positionedWaybill
                    + result.substring(waybillStart + waybill.length());
        }
        for (int id = 2; id <= PackingOverlayLayout.LAST_OVERLAY_ID; id++) {
            if (id - 2 >= lines.size()) {
                result = disableOverlayBlock(result, id);
                continue;
            }
            OverlayTextRequest line = new OverlayTextRequest();
            line.setOverlayId(id);
            line.setText(lines.get(id - 2));
            result = rewriteBlock(result, line);
            String block = locateOverlayBlock(result, id);
            String positioned = replaceElementText(block, POSITION_X, Integer.toString(DEFAULT_POSITION_X));
            if (positioned == null) throw new IllegalStateException("地址字幕块缺少横坐标");
            positioned = replaceElementText(positioned, POSITION_Y,
                    Integer.toString(WAYBILL_POSITION_Y - LINE_SPACING * (id - 1)));
            if (positioned == null) throw new IllegalStateException("地址字幕块缺少纵坐标");
            int start = result.indexOf(block);
            result = result.substring(0, start) + positioned + result.substring(start + block.length());
        }
        if (hasWaybill(request)) result = pinPackingVisualLayout(result);
        validatePackingTarget(result, request, lines);
        return result;
    }

    /** 打包显示采用视觉对齐坐标、32 连续行距及无星期的 24 小时日期时间。 */
    private static String pinPackingVisualLayout(String xml) {
        String pinned = pinWaybillPositionY(xml);
        pinned = configurePackingSystemOverlay(pinned, "DateTimeOverlay");
        return configurePackingSystemOverlay(pinned, "channelNameOverlay");
    }

    private static String configurePackingSystemOverlay(String xml, String blockName) {
        Matcher matcher = Pattern.compile(
                "<((?:\\w+:)?" + blockName + ")\\b[^>]*>.*?</\\1>", Pattern.DOTALL).matcher(xml);
        // 整块缺失不猜设备结构；回读及跳过写入均不能把这种 SHOW 判为完整成功。
        if (!matcher.find()) return xml;
        boolean time = "DateTimeOverlay".equals(blockName);
        String block = matcher.group();
        block = setSystemField(block, matcher.group(1), "enabled", "true");
        block = setSystemField(block, matcher.group(1), "positionX", Integer.toString(time ? TIME_POSITION_X : DEFAULT_POSITION_X));
        block = setSystemField(block, matcher.group(1), "positionY", Integer.toString(time ? TIME_POSITION_Y : CHANNEL_NAME_POSITION_Y));
        if (time) {
            block = setSystemField(block, matcher.group(1), "dateStyle", "CHR-YYYY-MM-DD");
            block = setSystemField(block, matcher.group(1), "timeStyle", "24hour");
            block = setSystemField(block, matcher.group(1), "displayWeek", "false");
        }
        return xml.substring(0, matcher.start()) + block + xml.substring(matcher.end());
    }

    /** 仅在现有系统块内补字段，保留字段属性及原有命名空间。 */
    private static String setSystemField(String block, String blockQName, String name, String value) {
        String updated = replaceDirectSystemField(block, name, value);
        if (updated != null) return updated;
        String prefix = blockQName.contains(":") ? blockQName.substring(0, blockQName.indexOf(':') + 1) : "";
        String addition = "<" + prefix + name + ">" + value + "</" + prefix + name + ">";
        int end = block.lastIndexOf("</" + blockQName + ">");
        return block.substring(0, end) + addition + block.substring(end);
    }

    /** 只替换直属字段，设备扩展节点内的同名字段保持原文；整份 XML 仍由 DOM 做结构校验。 */
    private static String replaceDirectSystemField(String block, String name, String value) {
        Matcher tokens = XML_TOKEN.matcher(block);
        int depth = 0;
        int start = -1;
        String opening = null;
        String qName = null;
        while (tokens.find()) {
            String token = tokens.group();
            if (token.startsWith("<!") || token.startsWith("<?")) continue;
            if (token.startsWith("</")) {
                if (depth == 2 && start >= 0) {
                    return block.substring(0, start) + opening + value + "</" + qName + ">"
                            + block.substring(tokens.end());
                }
                depth--;
                continue;
            }
            Matcher element = ELEMENT_OPEN.matcher(token);
            if (!element.find()) continue;
            String tokenName = element.group(1);
            String localName = tokenName.substring(tokenName.indexOf(':') + 1);
            if (depth == 1 && name.equals(localName)) {
                if (token.endsWith("/>")) {
                    return block.substring(0, tokens.start()) + token.substring(0, token.length() - 2) + ">"
                            + value + "</" + tokenName + ">" + block.substring(tokens.end());
                }
                start = tokens.start();
                opening = token;
                qName = tokenName;
            }
            if (!token.endsWith("/>")) depth++;
        }
        return null;
    }

    private static void validatePackingTarget(String xml, OverlayTextRequest request, List<String> lines) {
        try {
            Map<Integer, List<String>> blocks = packingBlocks(xml, false);
            for (int id = 1; id <= PackingOverlayLayout.LAST_OVERLAY_ID; id++) {
                boolean enabled = id == PREFIX_OVERLAY_ID ? request.isEnabled() : id - 2 < lines.size();
                String text = id == PREFIX_OVERLAY_ID ? request.resolveDisplayText().trim()
                        : enabled ? lines.get(id - 2) : "";
                List<String> block = blocks.getOrDefault(id, List.of("false", ""));
                if (!Boolean.toString(enabled).equals(block.get(0)) || !text.equals(block.get(1))) {
                    throw new IllegalArgumentException("字幕块未完整改写，编号=" + id);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("设备字幕结构异常，不能安全写入单号和完整地址", e);
        }
    }

    /** SHOW 验收完整格式和行位置；CLEAR 只验收业务块，不能因水印位置误报清除失败。 */
    static boolean packingOverlayMatches(String expected, String actual) {
        try {
            Map<Integer, List<String>> wanted = packingBlocks(expected, false);
            boolean clearing = isPackingClearTarget(wanted);
            if (!clearing && (!packingSystemOverlaysMatch(expected) || !packingSystemOverlaysMatch(actual))) return false;
            if (!clearing) wanted = packingBlocks(expected);
            Map<Integer, List<String>> stored = packingBlocks(actual, !clearing);
            for (int id = 1; id <= PackingOverlayLayout.LAST_OVERLAY_ID; id++) {
                List<String> left = wanted.getOrDefault(id, List.of("false", ""));
                List<String> right = stored.getOrDefault(id, List.of("false", ""));
                if (!left.equals(right)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPackingClearTarget(Map<Integer, List<String>> blocks) {
        if (!List.of("true", WAYBILL_PREFIX).equals(blocks.get(PREFIX_OVERLAY_ID))) return false;
        for (int id = 2; id <= PackingOverlayLayout.LAST_OVERLAY_ID; id++) {
            if (!List.of("false", "").equals(blocks.getOrDefault(id, List.of("false", "")))) return false;
        }
        return true;
    }

    private static boolean packingSystemOverlaysMatch(String xml) throws Exception {
        Element root = parseXml(xml);
        for (String name : List.of("DateTimeOverlay", "channelNameOverlay")) {
            var nodes = root.getElementsByTagNameNS("*", name);
            if (nodes.getLength() != 1 || nodes.item(0).getParentNode() != root) return false;
            Element block = (Element) nodes.item(0);
            boolean time = "DateTimeOverlay".equals(name);
            if (!"true".equals(directSystemFieldText(block, "enabled"))
                    || Double.parseDouble(directSystemFieldText(block, "positionX")) != (time ? TIME_POSITION_X : DEFAULT_POSITION_X)
                    || Double.parseDouble(directSystemFieldText(block, "positionY")) != (time ? TIME_POSITION_Y : CHANNEL_NAME_POSITION_Y)) return false;
            if (time && (!"CHR-YYYY-MM-DD".equals(directSystemFieldText(block, "dateStyle"))
                    || !"24hour".equals(directSystemFieldText(block, "timeStyle"))
                    || !"false".equals(directSystemFieldText(block, "displayWeek")))) return false;
        }
        return true;
    }

    private static String directSystemFieldText(Element parent, String name) {
        Element field = null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                if (field != null) throw new IllegalArgumentException("系统水印字段重复");
                field = element;
            }
        }
        if (field == null) throw new IllegalArgumentException("系统水印字段缺失");
        for (Node child = field.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) throw new IllegalArgumentException("系统水印字段必须为文本");
        }
        return field.getTextContent().trim();
    }

    private static Map<Integer, List<String>> packingBlocks(String xml) throws Exception {
        return packingBlocks(xml, true);
    }

    private static Map<Integer, List<String>> packingBlocks(String xml, boolean includePositions) throws Exception {
        if (xml == null || xml.isBlank()) throw new IllegalArgumentException("字幕回读为空");
        Element root = parseXml(xml);
        var lists = root.getElementsByTagNameNS("*", "TextOverlayList");
        boolean rootIsList = "TextOverlayList".equals(root.getLocalName());
        if (lists.getLength() != (rootIsList ? 0 : 1)) {
            throw new IllegalArgumentException("字幕配置必须有且仅有一个 TextOverlayList");
        }
        Element list = rootIsList ? root : (Element) lists.item(0);
        Map<Integer, List<String>> result = new HashMap<>();
        var blocks = root.getElementsByTagNameNS("*", "TextOverlay");
        for (int i = 0; i < blocks.getLength(); i++) {
            Element block = (Element) blocks.item(i);
            if (block.getParentNode() != list) {
                throw new IllegalArgumentException("字幕块必须直属 TextOverlayList");
            }
            int id = Integer.parseInt(elementText(block, "id"));
            String enabled = elementText(block, "enabled");
            String text = elementText(block, "displayText");
            List<String> state = includePositions && "true".equals(enabled)
                    ? List.of(enabled, text, Double.toString(Double.parseDouble(elementText(block, "positionX"))),
                    Double.toString(Double.parseDouble(elementText(block, "positionY"))))
                    : List.of(enabled, text);
            if (result.put(id, state) != null) throw new IllegalArgumentException("字幕块编号重复");
        }
        if (!result.containsKey(PREFIX_OVERLAY_ID)) throw new IllegalArgumentException("字幕回读缺少单号块");
        return result;
    }

    private static String elementText(Element parent, String name) {
        var nodes = parent.getElementsByTagNameNS("*", name);
        if (nodes.getLength() != 1) throw new IllegalArgumentException("字幕字段缺失或重复");
        return nodes.item(0).getTextContent().trim();
    }

    private static Element parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    /**
     * 把时间、通道名、单号三行的纵坐标收成固定行距。横坐标和其他字段不动。
     */
    static String pinLineSpacing(String xml) {
        if (xml == null || xml.isBlank()) {
            return xml;
        }
        String pinned = replaceBlockPositionY(xml, "DateTimeOverlay", TIME_POSITION_Y);
        pinned = replaceBlockPositionY(pinned, "channelNameOverlay", CHANNEL_NAME_POSITION_Y);
        return pinWaybillPositionY(pinned);
    }

    private static String pinWaybillPositionY(String pinned) {
        String waybill = locateOverlayBlock(pinned, PREFIX_OVERLAY_ID);
        if (waybill == null) {
            return pinned;
        }
        String updated = replaceElementText(waybill, POSITION_Y, Integer.toString(WAYBILL_POSITION_Y));
        if (updated == null) {
            return pinned;
        }
        int start = pinned.indexOf(waybill);
        return pinned.substring(0, start) + updated + pinned.substring(start + waybill.length());
    }

    private static String replaceBlockPositionY(String xml, String blockName, int y) {
        Matcher matcher = Pattern.compile(
                "<(?:\\w+:)?" + blockName + "\\b[^>]*>.*?</(?:\\w+:)?" + blockName + ">", Pattern.DOTALL).matcher(xml);
        if (!matcher.find()) {
            return xml;
        }
        String block = matcher.group();
        String updated = replaceElementText(block, POSITION_Y, Integer.toString(y));
        if (updated == null) {
            return xml;
        }
        return xml.substring(0, matcher.start()) + updated + xml.substring(matcher.end());
    }

    private static String rewriteBlock(String currentXml, OverlayTextRequest request) {
        String text = request.resolveDisplayText().trim();
        String escaped = escapeXml(text);
        String target = locateOverlayBlock(currentXml, request.getOverlayId());

        if (target != null) {
            String rewritten = replaceElementText(target, DISPLAY_TEXT, escaped);
            if (rewritten == null) {
                throw new IllegalStateException("叠加区块里没有 displayText 节点，无法改写文字");
            }
            rewritten = replaceElementText(rewritten, ENABLED, Boolean.toString(request.isEnabled()));
            if (rewritten == null) {
                rewritten = target;
            }
            // 坐标沿用设备现状：外部只改单号文字，避免每次下发把对齐好的位置写坏
            int start = currentXml.indexOf(target);
            return currentXml.substring(0, start) + rewritten + currentXml.substring(start + target.length());
        }

        if (text.isEmpty()) {
            // 设备本来就没配过叠加区，没有可清空的内容
            return currentXml;
        }
        return insertOverlay(currentXml, request, escaped);
    }

    /**
     * 设备尚未配置过文字叠加区时，构造一块插进 {@code TextOverlayList}。
     *
     * <p>录像机出厂时 {@code TextOverlayList} 常常是空的（只带 size 属性），
     * 此时没有可替换的节点，只能新增。</p>
     */
    private static String insertOverlay(String xml, OverlayTextRequest request, String escaped) {
        Matcher matcher = OVERLAY_LIST_OPEN.matcher(xml);
        if (!matcher.find()) {
            throw new IllegalStateException("设备响应里没有 TextOverlayList 节点，无法新增叠加区");
        }
        int id = request.getOverlayId() == null ? 1 : request.getOverlayId();
        int x = DEFAULT_POSITION_X;
        int y = DEFAULT_POSITION_Y;
        String block = "<TextOverlay>\n<id>" + id + "</id>\n"
                + "<enabled>" + (request.isEnabled() ? "true" : "false") + "</enabled>\n"
                + "<positionX>" + x + "</positionX>\n"
                + "<positionY>" + y + "</positionY>\n"
                + "<displayText>" + escaped + "</displayText>\n</TextOverlay>";
        String listName = matcher.group(1);
        int colon = listName.indexOf(':');
        if (colon >= 0) {
            String prefix = listName.substring(0, colon + 1);
            block = block.replaceAll("<(/?)([A-Za-z][A-Za-z0-9]*)", "<$1" + prefix + "$2");
        }
        String opening = matcher.group();
        if (opening.endsWith("/>")) {
            return xml.substring(0, matcher.start()) + opening.substring(0, opening.length() - 2)
                    + ">\n" + block + "</" + listName + ">" + xml.substring(matcher.end());
        }
        return xml.substring(0, matcher.end()) + "\n" + block + xml.substring(matcher.end());
    }

    /**
     * 定位要改写的叠加区块。指定 id 时按块内 {@code <id>} 匹配，否则取第一块。
     */
    private static String locateOverlayBlock(String xml, Integer overlayId) {
        Matcher matcher = OVERLAY_BLOCK.matcher(xml);
        String first = null;
        while (matcher.find()) {
            String block = matcher.group();
            if (first == null) {
                first = block;
            }
            if (overlayId == null) {
                return first;
            }
            Matcher idMatcher = ID.matcher(block);
            if (idMatcher.find()) {
                try {
                    if (Integer.parseInt(idMatcher.group(1).trim()) == overlayId) {
                        return block;
                    }
                } catch (NumberFormatException ignored) {
                    // id 非数字，跳过该块
                }
            }
        }
        return overlayId == null ? first : null;
    }

    private static boolean hasWaybill(OverlayTextRequest request) {
        return request.getWaybillNo() != null && !request.getWaybillNo().trim().isEmpty();
    }

    /** 替换元素内容，保留原有命名空间前缀和属性；合法空元素按需展开。 */
    private static String replaceElementText(String source, Pattern pattern, String escapedText) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return null;
        }
        Matcher opening = ELEMENT_OPEN.matcher(matcher.group());
        if (!opening.find()) return null;
        String replacement = "<" + opening.group(1) + opening.group(2) + ">" + escapedText
                + "</" + opening.group(1) + ">";
        return source.substring(0, matcher.start()) + replacement + source.substring(matcher.end());
    }


    /**
     * 安全校验：rewrite 后、PUT 前确认系统水印仅有约定的变更。
     * 普通兼容请求仅允许固定纵向行距；打包显示的字段白名单见下方重载。
     *
     * @return null 一致；否则返回哪一块被改了
     */
    static String findChangedOverlayBlock(String currentXml, String newXml) {
        return findChangedOverlayBlock(currentXml, newXml, false);
    }

    /** 打包显示只允许约定的水印开关、格式和坐标变化，其他字段仍须原样保留。 */
    private static String findChangedOverlayBlock(String currentXml, String newXml, boolean packingVisualLayout) {
        String[] nodes = {"DateTimeOverlay", "channelNameOverlay"};
        for (String node : nodes) {
            Pattern blockPattern = Pattern.compile(
                    "<(?:\\w+:)?" + node + "\\b[^>]*>.*?</(?:\\w+:)?" + node + ">", Pattern.DOTALL);
            Matcher m1 = blockPattern.matcher(currentXml);
            Matcher m2 = blockPattern.matcher(newXml);
            if (!m1.find() || !m2.find()) continue;
            String before = m1.group();
            String after = m2.group();
            String comparableBefore = packingVisualLayout ? configurePackingSystemOverlay(before, node) : stripPositionY(before);
            String comparableAfter = packingVisualLayout ? after : stripPositionY(after);
            if (!comparableBefore.equals(comparableAfter)) {
                return node;
            }
        }
        return null;
    }

    /** 比较时间块、通道名块时忽略纵坐标，固定行距不算误改。 */
    private static String stripPositionY(String block) {
        return POSITION_Y.matcher(block).replaceAll("<positionY></positionY>");
    }

    /** XML 文本转义，避免业务文字里的特殊字符破坏报文 */
    static String escapeXml(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 从叠加区块里取坐标（设备回读格式如 {@code <positionX>528.000000</positionX>}）。
     */
    static double parseCoord(String block, String tag) {
        if (block == null || tag == null) {
            return 0;
        }
        Matcher m = Pattern.compile("<(?:\\w+:)?" + tag + "\\b[^>]*>(.*?)</(?:\\w+:)?" + tag + ">").matcher(block);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String pendingKey(String deviceId, int channel, int overlayId) {
        return deviceId + "|" + channel + "|" + overlayId;
    }

    private void cancelPending(String deviceId, int channel, int overlayId) {
        ScheduledFuture<?> old = pending.remove(pendingKey(deviceId, channel, overlayId));
        if (old != null) {
            old.cancel(false);
        }
    }

    /**
     * 调度：delaySec 秒后把 overlay 1 恢复成「快递单号：」（单号从同一行消失，前缀仍在）。
     */
    private void scheduleHideWaybill(String deviceId, int channel, int overlayId, int delaySec) {
        cancelPending(deviceId, channel, overlayId);
        String key = pendingKey(deviceId, channel, overlayId);
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                restorePrefix(deviceId, channel, overlayId);
            } catch (Throwable t) {
                log.warn("恢复默认叠加文字异常：deviceId={}, channel={}, overlayId={}",
                        deviceId, channel, overlayId, t.toString());
            } finally {
                pending.remove(key);
            }
        }, delaySec, TimeUnit.SECONDS);
        pending.put(key, future);
        log.info("已调度 {} 秒后恢复前缀：deviceId={}, channel={}, overlayId={}",
                delaySec, deviceId, channel, overlayId);
    }

    /**
     * 到期后 overlay 1 只留「快递单号：」，并关掉旧的单号块。
     */
    private void restorePrefix(String deviceId, int channel, int overlayId) {
        OverlayTextRequest restore = new OverlayTextRequest();
        restore.setOverlayId(overlayId);
        restore.setText(WAYBILL_PREFIX);
        restore.setPrefix("");
        restore.setDurationSec(0);
        restore.setEnabled(true);
        for (String template : OVERLAY_PATH_TEMPLATES) {
            String path = String.format(template, channel);
            String currentXml;
            try {
                currentXml = isapiClient.get(deviceId, path);
            } catch (RuntimeException e) {
                log.debug("读取恢复前缀配置失败：deviceId={}, path={}, 原因={}", deviceId, path, e.getMessage());
                continue;
            }
            if (currentXml == null || !OVERLAY_LIST_OPEN.matcher(currentXml).find()) {
                continue;
            }
            String newXml = rewrite(currentXml, restore);
            if (newXml.equals(currentXml)) {
                return;
            }
            reserveWriteSlot(deviceId, channel);
            String result = isapiClient.put(deviceId, path, newXml);
            OverlayTextResponse response = new OverlayTextResponse();
            if (!checkDeviceAccepted(deviceId, channel, result, response)) {
                throw IsupException.failed("恢复叠加前缀", deviceId, channel, null,
                        "statusCode=" + response.getDeviceStatusCode() + "，" + response.getDeviceStatusString());
            }
            log.info("已恢复前缀：deviceId={}, channel={}, overlayId={}", deviceId, channel, overlayId);
            return;
        }
        log.warn("恢复前缀未能成功：deviceId={}, channel={}, overlayId={}",
                deviceId, channel, overlayId);
    }

    /** 取设备当前叠加区里已经存在的文字，用于写入未生效时回显画面实际内容。 */
    private static String currentDisplayText(String xml, Integer overlayId) {
        String block = locateOverlayBlock(xml, overlayId);
        if (block == null) {
            return "";
        }
        Matcher matcher = DISPLAY_TEXT.matcher(block);
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group();
        int start = raw.indexOf('>');
        int end = raw.lastIndexOf('<');
        return start >= 0 && end > start ? raw.substring(start + 1, end) : "";
    }

    /**
     * 写入节流：同一通道距上次真实写入不足 {@link #minWriteIntervalSec} 秒时先等待。
     *
     * <p>叠加配置写入会落到设备 flash，过密的写入会触发设备的高频写保护，
     * 保护期间设备拒收配置，画面就不再更新了。这里把同一通道的写入摊平到配置间隔。</p>
     */
    private synchronized void reserveWriteSlot(String deviceId, int channel) {
        long minMs = Math.max(0, minWriteIntervalSec) * 1000L;
        if (minMs == 0) {
            return;
        }
        String key = deviceId + "|" + channel;
        Long last = lastWriteAt.get(key);
        if (last != null) {
            long waitMs = minMs - (System.currentTimeMillis() - last);
            if (waitMs > 0) {
                log.info("叠加写入节流：deviceId={}, channel={}, 等待 {} ms 后再写设备", deviceId, channel, waitMs);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw IsupException.wrap("等待叠加写入", deviceId, channel, null, e);
                }
            }
        }
        lastWriteAt.put(key, System.currentTimeMillis());
    }

    /**
     * 记录设备对本次写入的返回结果。
     *
     * <p>SDK 调用成功只代表透传完成，结果要看响应体里的 statusCode：1 才是已接受。
     * 空响应、缺失或非法状态码均不能确认成功。</p>
     *
     * <p>由 {@code applied} 表达设备是否接受，原因放在状态字段中；
     * 工位服务会汇总两路结果，将任一路失败返回调用方。</p>
     *
     * @return 设备是否接受本次写入
     */
    private boolean checkDeviceAccepted(String deviceId, int channel, String putResponse,
                                        OverlayTextResponse response) {
        if (putResponse == null || putResponse.isBlank()) {
            response.setDeviceStatusString("设备响应为空，无法确认写入结果");
            return false;
        }
        int code;
        String statusString;
        try {
            Element root = parseXml(putResponse);
            if (!"ResponseStatus".equals(root.getLocalName())) {
                response.setDeviceStatusString("设备响应不是 ResponseStatus，无法确认写入结果");
                return false;
            }
            String statusCode = null;
            statusString = "";
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element) {
                    if ("statusCode".equals(element.getLocalName())) {
                        statusCode = element.getTextContent().trim();
                    } else if ("statusString".equals(element.getLocalName())) {
                        statusString = element.getTextContent().trim();
                    }
                }
            }
            code = Integer.parseInt(statusCode);
        } catch (Exception e) {
            response.setDeviceStatusString("设备响应 XML 或 statusCode 无效，无法确认写入结果");
            return false;
        }
        response.setDeviceStatusCode(code);
        response.setDeviceStatusString(statusString);
        if (code == 1) {
            return true;
        }
        log.error("设备未接受叠加写入：deviceId={}, channel={}, statusCode={}, statusString={}；"
                        + "短时间内反复写叠加配置会触发设备 flash 写保护",
                deviceId, channel, code, statusString);
        return false;
    }

    /** 关掉并清空指定叠加块（旧单号块）。块不存在则原样返回。 */
    static String disableOverlayBlock(String xml, int overlayId) {
        String target = locateOverlayBlock(xml, overlayId);
        if (target == null) {
            return xml;
        }
        String updated = replaceElementText(target, ENABLED, "false");
        if (updated == null) {
            updated = target;
        }
        String cleared = replaceElementText(updated, DISPLAY_TEXT, "");
        if (cleared != null) {
            updated = cleared;
        }
        int start = xml.indexOf(target);
        return xml.substring(0, start) + updated + xml.substring(start + target.length());
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
