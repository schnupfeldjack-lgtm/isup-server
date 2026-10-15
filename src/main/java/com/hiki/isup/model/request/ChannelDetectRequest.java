package com.hiki.isup.model.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 通道探测请求：用一小段真实录像试出哪些通道有数据。
 */
public class ChannelDetectRequest {

    /**
     * 开始时间，支持 {@code yyyy-MM-dd'T'HH:mm:ss} 或 {@code yyyy-MM-dd HH:mm:ss}
     */
    @NotBlank(message = "startTime 不能为空")
    private String startTime;

    /** 结束时间，格式同 startTime */
    @NotBlank(message = "endTime 不能为空")
    private String endTime;

    /** 探测到第几通道为止，不传用默认值 */
    @Min(value = 1, message = "maxChannel 必须大于等于 1")
    private Integer maxChannel;

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

    public Integer getMaxChannel() {
        return maxChannel;
    }

    public void setMaxChannel(Integer maxChannel) {
        this.maxChannel = maxChannel;
    }
}
