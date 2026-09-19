package com.hiki.isup.model;

import java.time.LocalDateTime;

/**
 * 在线设备信息（仅保存在内存，不入库）。
 */
public class DeviceInfo {

    /** 设备 ID，即录像机上配置的 DeviceID */
    private String deviceId;
    /** 海康 SDK 分配的登录句柄 lUserID */
    private int lUserId;
    /** 设备 IP（设备注册时上报的地址） */
    private String deviceIp;
    private boolean online;
    private LocalDateTime onlineTime;
    /** 设备序列号 */
    private String serialNumber;
    /** 设备名称 */
    private String deviceName;

    public DeviceInfo() {
    }

    public DeviceInfo(String deviceId, int lUserId, String deviceIp) {
        this.deviceId = deviceId;
        this.lUserId = lUserId;
        this.deviceIp = deviceIp;
        this.online = true;
        this.onlineTime = LocalDateTime.now();
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public int getlUserId() {
        return lUserId;
    }

    public int getLUserId() {
        return lUserId;
    }

    public void setLUserId(int lUserId) {
        this.lUserId = lUserId;
    }

    public String getDeviceIp() {
        return deviceIp;
    }

    public void setDeviceIp(String deviceIp) {
        this.deviceIp = deviceIp;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public LocalDateTime getOnlineTime() {
        return onlineTime;
    }

    public void setOnlineTime(LocalDateTime onlineTime) {
        this.onlineTime = onlineTime;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }
}
