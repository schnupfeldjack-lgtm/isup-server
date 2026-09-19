package com.hiki.isup.model.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 按时间段回放请求。
 *
 * <p>时间按录像机设备本地时间处理。</p>
 */
public class PlaybackRequest {

    /** 设备 ID */
    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;

    /** 通道号，从 1 开始 */
    @NotNull(message = "channel 不能为空")
    @Min(value = 1, message = "channel 必须大于等于 1")
    private Integer channel;

    /**
     * 开始时间，支持 {@code yyyy-MM-dd'T'HH:mm:ss} 或 {@code yyyy-MM-dd HH:mm:ss}
     */
    @NotBlank(message = "startTime 不能为空")
    private String startTime;

    /** 结束时间，格式同 startTime */
    @NotBlank(message = "endTime 不能为空")
    private String endTime;

    /** 链路模式：0-TCP，1-UDP，2-HRUDP；不传则使用全局配置 isup.link-mode */
    private Integer linkMode;

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

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public Integer getLinkMode() {
        return linkMode;
    }

    public void setLinkMode(Integer linkMode) {
        this.linkMode = linkMode;
    }
}
