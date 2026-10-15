package com.hiki.isup.model;

import java.time.LocalDateTime;

/**
 * {@code wms_outbound_waybill_video} 一行待处理记录。
 */
public class WaybillVideoRecord {

    private String id;
    private String outboundWaybillId;
    private String waybillId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private String deviceId;
    /** 工位名，如 {@code 打包台1}，用来匹配设备通道名 */
    private String channelCode;
    private String videoUrl1;
    private String videoUrl2;

    public boolean hasDeviceId() {
        return deviceId != null && !deviceId.isBlank();
    }

    public boolean hasChannelCode() {
        return channelCode != null && !channelCode.isBlank();
    }

    public boolean hasUrl(CameraChannel channel) {
        String url = urlOf(channel);
        return url != null && !url.isBlank();
    }

    public String urlOf(CameraChannel channel) {
        return switch (channel) {
            case PANORAMA -> videoUrl1;
            case DETAIL -> videoUrl2;
        };
    }

    public void setUrl(CameraChannel channel, String url) {
        switch (channel) {
            case PANORAMA -> videoUrl1 = url;
            case DETAIL -> videoUrl2 = url;
        }
    }

    public boolean hasAllUrls(Iterable<CameraChannel> channels) {
        for (CameraChannel channel : channels) {
            if (!hasUrl(channel)) {
                return false;
            }
        }
        return true;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOutboundWaybillId() {
        return outboundWaybillId;
    }

    public void setOutboundWaybillId(String outboundWaybillId) {
        this.outboundWaybillId = outboundWaybillId;
    }

    public String getWaybillId() {
        return waybillId;
    }

    public void setWaybillId(String waybillId) {
        this.waybillId = waybillId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public void setChannelCode(String channelCode) {
        this.channelCode = channelCode;
    }

    public String getVideoUrl1() {
        return videoUrl1;
    }

    public void setVideoUrl1(String videoUrl1) {
        this.videoUrl1 = videoUrl1;
    }

    public String getVideoUrl2() {
        return videoUrl2;
    }

    public void setVideoUrl2(String videoUrl2) {
        this.videoUrl2 = videoUrl2;
    }
}
