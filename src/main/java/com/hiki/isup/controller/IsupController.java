package com.hiki.isup.controller;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.NotFoundException;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.model.request.IsapiProxyRequest;
import com.hiki.isup.model.request.PlaybackRequest;
import com.hiki.isup.model.request.PreviewRequest;
import com.hiki.isup.model.request.ChannelDetectRequest;
import com.hiki.isup.model.request.WaybillOverlayRequest;
import com.hiki.isup.model.response.ApiResult;
import com.hiki.isup.model.response.ChannelDetectResponse;
import com.hiki.isup.model.response.ChannelListResponse;
import com.hiki.isup.model.response.DownloadTaskResponse;
import com.hiki.isup.model.response.HealthResponse;
import com.hiki.isup.model.response.IsapiProxyResponse;
import com.hiki.isup.model.response.SessionStatusResponse;
import com.hiki.isup.model.response.StreamSessionResponse;
import com.hiki.isup.service.ChannelService;
import com.hiki.isup.service.DeviceRegistry;
import com.hiki.isup.service.DownloadService;
import com.hiki.isup.service.IsapiProxyService;
import com.hiki.isup.service.IsupService;
import com.hiki.isup.service.MediaUrlSigner;
import com.hiki.isup.service.PlaybackService;
import com.hiki.isup.service.StationOverlayService;
import com.hiki.isup.service.PreviewService;
import com.hiki.isup.service.SessionManager;
import com.hiki.isup.util.MediaPaths;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/**
 * ERP 调用的 HTTP API。
 *
 * <p>全部接口位于 /api/v1/** 且需要 X-API-Key；/api/health 不鉴权。</p>
 */
@RestController
public class IsupController {

    private static final Logger log = LoggerFactory.getLogger(IsupController.class);

    /** 停止会话后等待其进入终态的最长时间（毫秒） */
    private static final long STOP_WAIT_MILLIS = 2000;

    private final IsupProperties properties;
    private final IsupService isupService;
    private final DeviceRegistry deviceRegistry;
    private final SessionManager sessionManager;
    private final PreviewService previewService;
    private final PlaybackService playbackService;
    private final DownloadService downloadService;
    private final ChannelService channelService;
    private final StationOverlayService stationOverlayService;
    private final IsapiProxyService isapiProxyService;
    private final MediaUrlSigner mediaUrlSigner;

    public IsupController(IsupProperties properties,
                          IsupService isupService,
                          DeviceRegistry deviceRegistry,
                          SessionManager sessionManager,
                          PreviewService previewService,
                          PlaybackService playbackService,
                          DownloadService downloadService,
                          ChannelService channelService,
                          StationOverlayService stationOverlayService,
                          IsapiProxyService isapiProxyService,
                          MediaUrlSigner mediaUrlSigner) {
        this.properties = properties;
        this.isupService = isupService;
        this.deviceRegistry = deviceRegistry;
        this.sessionManager = sessionManager;
        this.previewService = previewService;
        this.playbackService = playbackService;
        this.downloadService = downloadService;
        this.channelService = channelService;
        this.stationOverlayService = stationOverlayService;
        this.isapiProxyService = isapiProxyService;
        this.mediaUrlSigner = mediaUrlSigner;
    }

    /**
     * 健康检查（不鉴权）
     */
    @GetMapping("/api/health")
    public HealthResponse health() {
        HealthResponse response = new HealthResponse("UP");
        response.setSdkReady(isupService.isReady());
        response.setOnlineDevices(deviceRegistry.onlineCount());
        response.setActiveSessions((int) sessionManager.activeCount());
        response.setPublicIp(properties.getPublicIp());
        response.setCmsPort(properties.getCms().getListenPort());
        response.setPreviewPort(properties.getStream().getPreviewPort());
        response.setPlaybackPort(properties.getStream().getPlaybackPort());
        response.setUptimeSeconds(ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return response;
    }

    /**
     * 当前在线设备列表
     */
    @GetMapping("/api/v1/devices")
    public List<DeviceInfo> devices() {
        return deviceRegistry.list();
    }

    /**
     * 查询某台设备（录像机）下的工位通道。
     *
     * <p>请求能进来就返回 200。{@code 打包台1全景} 与 {@code 打包台1细节} 合成一条 {@code 打包台1}。
     * 查不到或匹配失败只记日志，响应里是空列表，不把失败原因交给调用方。</p>
     */
    @GetMapping("/api/v1/devices/{deviceId}/channels")
    public ChannelListResponse deviceChannels(@PathVariable String deviceId,
                                              @RequestParam(name = "probeMinutes", required = false) Integer probeMinutes) {
        log.info("收到通道查询请求：deviceId={}, probeMinutes={}", deviceId, probeMinutes);
        return channelService.queryChannels(deviceId, probeMinutes);
    }

    /**
     * 工位单号和当次面单申请收件地址叠加。
     *
     * <p>{@code channelCode=打包台1} 会同时写「打包台1全景」和「打包台1细节」。
     * 非空盖上「快递单号：」+ 单号；空串这两路只留「快递单号：」。
     * 参数错误或设备失败返回对应错误。</p>
     */
    @PostMapping(value = "/api/v1/devices/{deviceId}/overlay", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResult deviceOverlay(@PathVariable String deviceId,
                                   @RequestBody(required = false) WaybillOverlayRequest request) {
        String channelCode = request == null ? null : request.getChannelCode();
        String waybillNo = request == null ? null : request.getWaybillNo();
        log.info("收到叠加单号：deviceId={}, channelCode={}, waybillNo={}", deviceId, channelCode, waybillNo);
        if (request != null && request.getPlatformOrderNo() != null) {
            stationOverlayService.apply(deviceId, channelCode, waybillNo,
                    request.getRecipient(), request.getExpectedWaybillNo(), request.getPlatformOrderNo());
        } else if (request == null || (request.getRecipient() == null && request.getExpectedWaybillNo() == null)) {
            stationOverlayService.apply(deviceId, channelCode, waybillNo);
        } else {
            stationOverlayService.apply(deviceId, channelCode, waybillNo,
                    request.getRecipient(), request.getExpectedWaybillNo());
        }
        return ApiResult.ok();
    }

    /**
     * ISAPI 透传：向指定设备转发一条任意 ISAPI 请求，原样返回响应 XML。
     *
     * <p>对接设备新能力时先用它摸清真实地址与报文结构，再封装成专用接口。</p>
     */
    @PostMapping(value = "/api/v1/devices/{deviceId}/isapi", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IsapiProxyResponse deviceIsapi(@PathVariable String deviceId,
                                          @RequestBody IsapiProxyRequest request) {
        log.info("收到 ISAPI 透传请求：deviceId={}, method={}, path={}",
                deviceId, request == null ? null : request.getMethod(),
                request == null ? null : request.getPath());
        return isapiProxyService.forward(deviceId, request);
    }

    /**
     * 逐通道探测：设备不支持 ISAPI 透传时的兜底手段。
     *
     * <p>对 1..maxChannel 各回放请求里那一小段录像，返回哪些通道真有数据。</p>
     */
    @PostMapping(value = "/api/v1/devices/{deviceId}/channels/detect", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChannelDetectResponse detectChannels(@PathVariable String deviceId,
                                                @Valid @RequestBody ChannelDetectRequest request) {
        log.info("收到通道探测请求：deviceId={}, startTime={}, endTime={}, maxChannel={}",
                deviceId, request.getStartTime(), request.getEndTime(), request.getMaxChannel());
        return channelService.detectChannels(deviceId, request);
    }

    /**
     * 实时预览。8003 监听在 {@code IsupService.start()} 里暂时注释，调用会直接拒绝。
     * 恢复预览时：取消那处注释并重启。
     */
    @PostMapping(value = "/api/v1/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StreamSessionResponse preview(@Valid @RequestBody PreviewRequest request) {
        log.info("收到预览请求：deviceId={}, channel={}, streamType={}",
                request.getDeviceId(), request.getChannel(), request.getStreamType());
        StreamSessionResponse response = previewService.startPreview(request);
        response.setPlayUrl(mediaUrlSigner.sign("preview", response.getSessionId()));
        return response;
    }

    /**
     * 按时间段回放
     */
    @PostMapping(value = "/api/v1/playback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StreamSessionResponse playback(@Valid @RequestBody PlaybackRequest request) {
        log.info("收到回放请求：deviceId={}, channel={}, startTime={}, endTime={}",
                request.getDeviceId(), request.getChannel(), request.getStartTime(), request.getEndTime());
        StreamSessionResponse response = playbackService.startPlayback(request);
        response.setPlayUrl(mediaUrlSigner.sign("playback", response.getSessionId()));
        return response;
    }

    /**
     * 按时间段下载录像（异步，立即返回 taskId）
     */
    @PostMapping(value = "/api/v1/download", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DownloadTaskResponse download(@Valid @RequestBody DownloadRequest request) {
        log.info("收到下载请求：deviceId={}, channel={}, startTime={}, endTime={}",
                request.getDeviceId(), request.getChannel(), request.getStartTime(), request.getEndTime());
        return downloadService.startDownload(request);
    }

    /**
     * 查询会话 / 下载任务状态
     */
    @GetMapping("/api/v1/sessions/{sessionId}")
    public SessionStatusResponse sessionStatus(@PathVariable String sessionId) {
        if (!MediaPaths.isSafeId(sessionId)) {
            throw new BadRequestException("sessionId 格式非法：" + sessionId);
        }
        return toResponse(sessionManager.require(sessionId));
    }

    /**
     * 查询会话（按创建时间倒序，默认最多 200 条）。
     *
     * <p>ERP 等外部调用方发起的会话也能查到，便于统一观测与停止；
     * 终态会话会在内存中保留一段时间后再清理，因此列表可能很长，需要 limit 兜底。</p>
     */
    @GetMapping("/api/v1/sessions")
    public List<SessionStatusResponse> sessions(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        int max = Math.min(Math.max(limit, 1), 1000);
        return sessionManager.list().stream()
                .limit(max)
                .map(this::toResponse)
                .toList();
    }

    /**
     * 会话 → 响应体。文件大小只在首次查询时读一次磁盘并缓存到会话上，
     * 避免每次列表请求都对每个下载任务做一次 stat。
     */
    private SessionStatusResponse toResponse(MediaSession session) {
        SessionStatusResponse response = SessionStatusResponse.from(session);
        if (session.getPlayUrl() != null) {
            if (session.getType() == SessionType.PREVIEW) {
                response.setPlayUrl(mediaUrlSigner.sign("preview", session.getSessionId()));
            } else if (session.getType() == SessionType.PLAYBACK) {
                response.setPlayUrl(mediaUrlSigner.sign("playback", session.getSessionId()));
            }
        }
        if (session.getFilePath() != null) {
            long size = session.getFileSize();
            if (size < 0) {
                File file = new File(session.getFilePath());
                size = file.exists() ? file.length() : -1;
                if (size >= 0) {
                    session.setFileSize(size);
                }
            }
            if (size >= 0) {
                response.setFileSize(size);
            }
        }
        return response;
    }

    /**
     * 停止会话 / 下载任务
     */
    @DeleteMapping("/api/v1/sessions/{sessionId}")
    public Map<String, Object> stopSession(@PathVariable String sessionId) {
        if (!MediaPaths.isSafeId(sessionId)) {
            throw new BadRequestException("sessionId 格式非法：" + sessionId);
        }
        MediaSession session = sessionManager.require(sessionId);
        if (!session.isFinished()) {
            // 停止动作内含海康 SDK 调用，个别设备无响应时可能长时间不返回。
            // 交给停止线程池执行，请求线程只等一个很短的时间拿终态。
            sessionManager.stopAsync(session, SessionStatus.STOPPED, "客户端主动停止");
            awaitFinished(session);
        }
        log.info("停止会话：sessionId={}, status={}", sessionId, session.getStatus());
        return Map.of(
                "sessionId", session.getSessionId(),
                "status", session.getStatus().name()
        );
    }

    /**
     * 等待会话进入终态，最多 STOP_WAIT_MILLIS 毫秒。
     *
     * <p>等待期间未进入终态说明 SDK 停止调用迟迟未返回，此时直接响应：
     * 停止动作仍在后台执行，客户端可轮询 GET /api/v1/sessions/{id} 确认结果。</p>
     */
    private void awaitFinished(MediaSession session) {
        try {
            session.awaitTerminal(STOP_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 下载已完成的 MP4 文件
     */
    @GetMapping("/api/v1/download/{taskId}/file")
    public ResponseEntity<Resource> downloadFile(@PathVariable String taskId) {
        if (!MediaPaths.isSafeId(taskId)) {
            throw new BadRequestException("taskId 格式非法：" + taskId);
        }
        MediaSession session = sessionManager.require(taskId);
        if (session.getType() != SessionType.DOWNLOAD) {
            throw new BadRequestException("会话 " + taskId + " 不是下载任务，当前类型：" + session.getType());
        }
        if (session.getStatus() != SessionStatus.COMPLETED) {
            throw new BadRequestException("下载任务尚未完成，当前状态：" + session.getStatus()
                    + "，请轮询 GET /api/v1/sessions/" + taskId);
        }
        String filePath = session.getFilePath();
        if (filePath == null) {
            throw new NotFoundException("下载文件路径为空，taskId=" + taskId);
        }
        File file = new File(filePath);
        if (!file.exists()) {
            throw new NotFoundException("下载文件不存在，可能已被清理：taskId=" + taskId);
        }
        if (!MediaPaths.isInsideMediaRoot(properties.getMediaDir(), file)) {
            throw new BadRequestException("下载文件路径非法：" + filePath);
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "video/mp4")
                .contentLength(file.length())
                .body(resource);
    }
}
