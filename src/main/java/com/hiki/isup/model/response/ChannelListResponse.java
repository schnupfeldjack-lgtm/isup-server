package com.hiki.isup.model.response;

import com.hiki.isup.model.StationChannel;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备通道列表响应。
 *
 * <p>通道按工位合并：{@code 打包台1全景} 与 {@code 打包台1细节} 合成一条 {@code 打包台1}。
 * 设备编号在请求路径里已经有了，数据取自哪条 ISAPI 只记日志、不进响应。</p>
 */
public class ChannelListResponse {

    private String serialNumber;
    private List<StationChannel> channels = new ArrayList<>();

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public List<StationChannel> getChannels() {
        return channels;
    }

    public void setChannels(List<StationChannel> channels) {
        this.channels = channels == null ? new ArrayList<>() : channels;
    }
}
