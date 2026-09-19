package com.hiki.isup.controller;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.NotFoundException;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.model.request.PlaybackRequest;
import com.hiki.isup.model.request.PreviewRequest;
import com.hiki.isup.model.response.DownloadTaskResponse;
import com.hiki.isup.model.response.HealthResponse;
import com.hiki.isup.model.response.SessionStatusResponse;
import com.hiki.isup.model.response.StreamSessionResponse;
import com.hiki.isup.service.DeviceRegistry;
import com.hiki.isup.service.DownloadService;
import com.hiki.isup.service.IsupService;
import com.hiki.isup.service.PlaybackService;
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

    private final IsupProperties properties;
    private final IsupService isupService;
    private final DeviceRegistry deviceRegistry;
    private final SessionManager sessionManager;
    private final PreviewService previewService;
    private final PlaybackService playbackService;
    private final DownloadService downloadService;

    public IsupController(IsupProperties properties,
                          IsupService isupService,
                          DeviceRegistry deviceRegistry,
                          SessionManager sessionManager,
                          PreviewService previewService,
                          PlaybackService playbackService,
                          DownloadService downloadService) {
        this.properties = properties;
        this.isupService = isupService;
        this.deviceRegistry = deviceRegistry;
        this.sessionManager = sessionManager;
        this.previewService = previewService;
        this.playbackService = playbackService;
        this.downloadService = downloadService;
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
     * 实时预览
     */
    @PostMapping(value = "/api/v1/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StreamSessionResponse preview(@Valid @RequestBody PreviewRequest request) {
        log.info("收到预览请求：deviceId={}, channel={}, streamType={}",
                request.getDeviceId(), request.getChannel(), request.getStreamType());
        return previewService.startPreview(request);
    }

    /**
     * 按时间段回放
     */
    @PostMapping(value = "/api/v1/playback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StreamSessionResponse playback(@Valid @RequestBody PlaybackRequest request) {
        log.info("收到回放请求：deviceId={}, channel={}, startTime={}, endTime={}",
                request.getDeviceId(), request.getChannel(), request.getStartTime(), request.getEndTime());
        return playbackService.startPlayback(request);
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
        MediaSession session = sessionManager.require(sessionId);
        SessionStatusResponse response = SessionStatusResponse.from(session);
        if (session.getFilePath() != null) {
            File file = new File(session.getFilePath());
            if (file.exists()) {
                response.setFileSize(file.length());
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
            sessionManager.stop(session, SessionStatus.STOPPED, "客户端主动停止");
        }
        log.info("停止会话：sessionId={}, status={}", sessionId, session.getStatus());
        return Map.of(
                "sessionId", session.getSessionId(),
                "status", session.getStatus().name()
        );
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
