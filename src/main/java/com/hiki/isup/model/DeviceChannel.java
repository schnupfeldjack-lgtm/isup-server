package com.hiki.isup.model;

/**
 * 设备（录像机）下的一路通道。
 *
 * <p>{@code channel} 就是预览 / 回放 / 下载接口里的通道号，
 * {@code name} 是设备端配置的通道名称，仅用于展示与人工核对。</p>
 */
public class DeviceChannel {

    /** 通道号，从 1 开始 */
    private int channel;

    /** 设备端通道名称，设备未返回时为空串 */
    private String name;

    public DeviceChannel() {
    }

    public DeviceChannel(int channel, String name) {
        this.channel = channel;
        this.name = name == null ? "" : name;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }
}
