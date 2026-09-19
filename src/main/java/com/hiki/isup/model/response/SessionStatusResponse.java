package com.hiki.isup.model.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;

import java.time.LocalDateTime;

/**
 * 会话 / 下载任务状态查询返回。
 */
public class SessionStatusResponse {

    private String sessionId;
    private SessionType type;
    private SessionStatus status;
    private String deviceId;
    private Integer channel;
    private String playUrl;
    private String filePath;
    private String downloadUrl;
    private Long fileSize;
    private Long bytesReceived;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    private String error;

    public static SessionStatusResponse from(MediaSession session) {
        SessionStatusResponse r = new SessionStatusResponse();
        r.sessionId = session.getSessionId();
        r.type = session.getType();
        r.status = session.getStatus();
        r.deviceId = session.getDeviceId();
        r.channel = session.getChannel();
        r.playUrl = session.getPlayUrl();
        r.filePath = session.getFilePath();
        r.startTime = session.getStartTime();
        r.endTime = session.getEndTime();
        r.createdAt = session.getCreatedAt();
        r.updatedAt = session.getUpdatedAt();
        r.error = session.getError();
        r.bytesReceived = session.getBytesReceived();
        if (session.getType() == SessionType.DOWNLOAD) {
            r.downloadUrl = "/api/v1/download/" + session.getSessionId() + "/file";
        }
        return r;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public SessionType getType() {
        return type;
    }

    public void setType(SessionType type) {
        this.type = type;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Integer getChannel() {
        return channel;
    }

    public void setChannel(Integer channel) {
        this.channel = channel;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getBytesReceived() {
        return bytesReceived;
    }

    public void setBytesReceived(Long bytesReceived) {
        this.bytesReceived = bytesReceived;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
