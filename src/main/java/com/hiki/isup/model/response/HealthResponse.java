package com.hiki.isup.model.response;

/**
 * 健康检查（不鉴权）。
 */
public class HealthResponse {

    private String status;
    private boolean sdkReady;
    private int onlineDevices;
    private int activeSessions;
    private String publicIp;
    private Integer cmsPort;
    private Integer previewPort;
    private Integer playbackPort;
    private long uptimeSeconds;

    public HealthResponse() {
    }

    public HealthResponse(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isSdkReady() {
        return sdkReady;
    }

    public void setSdkReady(boolean sdkReady) {
        this.sdkReady = sdkReady;
    }

    public int getOnlineDevices() {
        return onlineDevices;
    }

    public void setOnlineDevices(int onlineDevices) {
        this.onlineDevices = onlineDevices;
    }

    public int getActiveSessions() {
        return activeSessions;
    }

    public void setActiveSessions(int activeSessions) {
        this.activeSessions = activeSessions;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public Integer getCmsPort() {
        return cmsPort;
    }

    public void setCmsPort(Integer cmsPort) {
        this.cmsPort = cmsPort;
    }

    public Integer getPreviewPort() {
        return previewPort;
    }

    public void setPreviewPort(Integer previewPort) {
        this.previewPort = previewPort;
    }

    public Integer getPlaybackPort() {
        return playbackPort;
    }

    public void setPlaybackPort(Integer playbackPort) {
        this.playbackPort = playbackPort;
    }

    public long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public void setUptimeSeconds(long uptimeSeconds) {
        this.uptimeSeconds = uptimeSeconds;
    }
}
