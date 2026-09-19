package com.hiki.isup.model.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 实时预览请求。
 */
public class PreviewRequest {

    /** 设备 ID（录像机上配置的 DeviceID） */
    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;

    /** 通道号，从 1 开始 */
    @NotNull(message = "channel 不能为空")
    @Min(value = 1, message = "channel 必须大于等于 1")
    private Integer channel;

    /** 码流类型：0-主码流，1-子码流，2-第三码流 */
    @Min(value = 0, message = "streamType 取值必须是 0/1/2")
    private Integer streamType = 0;

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

    public Integer getStreamType() {
        return streamType;
    }

    public void setStreamType(Integer streamType) {
        this.streamType = streamType;
    }

    public Integer getLinkMode() {
        return linkMode;
    }

    public void setLinkMode(Integer linkMode) {
        this.linkMode = linkMode;
    }
}
