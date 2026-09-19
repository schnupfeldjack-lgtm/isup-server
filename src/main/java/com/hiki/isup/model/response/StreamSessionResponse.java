package com.hiki.isup.model.response;

import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;

/**
 * 预览 / 回放接口返回。
 */
public class StreamSessionResponse {

    private String sessionId;
    private SessionType type;
    private SessionStatus status;
    private String playUrl;
    private String deviceId;
    private Integer channel;

    public StreamSessionResponse() {
    }

    public StreamSessionResponse(String sessionId, SessionType type, SessionStatus status, String playUrl) {
        this.sessionId = sessionId;
        this.type = type;
        this.status = status;
        this.playUrl = playUrl;
    }

    public static StreamSessionResponse of(String sessionId, SessionType type, SessionStatus status, String playUrl) {
        return new StreamSessionResponse(sessionId, type, status, playUrl);
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

    public String getPlayUrl() {
        return playUrl;
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
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
}
