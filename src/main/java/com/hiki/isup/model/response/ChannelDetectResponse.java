package com.hiki.isup.model.response;

import com.hiki.isup.model.ChannelProbe;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道探测结果：逐通道回放一小段录像，看哪些通道真有数据。
 *
 * <p>设备不支持 ISAPI 透传时用它兜底：拿不到通道名，但能确定哪些通道号可用。</p>
 */
public class ChannelDetectResponse {

    private String deviceId;
    private String serialNumber;
    private String startTime;
    private String endTime;
    private int maxChannel;
    private List<ChannelProbe> probes = new ArrayList<>();
    /** 有录像数据的通道号汇总，直接用于下载接口的 channel */
    private List<Integer> availableChannels = new ArrayList<>();

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
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

    public int getMaxChannel() {
        return maxChannel;
    }

    public void setMaxChannel(int maxChannel) {
        this.maxChannel = maxChannel;
    }

    public List<ChannelProbe> getProbes() {
        return probes;
    }

    public void setProbes(List<ChannelProbe> probes) {
        this.probes = probes == null ? new ArrayList<>() : probes;
    }

    public List<Integer> getAvailableChannels() {
        return availableChannels;
    }

    public void setAvailableChannels(List<Integer> availableChannels) {
        this.availableChannels = availableChannels == null ? new ArrayList<>() : availableChannels;
    }
}
