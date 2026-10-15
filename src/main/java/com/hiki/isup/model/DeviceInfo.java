package com.hiki.isup.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线设备信息（仅保存在内存，不入库）。
 */
public class DeviceInfo {

    private static final AtomicLong NEXT_CONNECTION_GENERATION = new AtomicLong();
    /** 每次注册使用新代次，即使 SDK 复用了同一数值句柄；仅用于进程内清理边界。 */
    private final long connectionGeneration = NEXT_CONNECTION_GENERATION.incrementAndGet();

    /** 设备 ID，即录像机上配置的 DeviceID */
    private String deviceId;
    /** 海康 SDK 分配的登录句柄 lUserID */
    private int lUserId;
    /** 设备 IP（设备注册时上报的地址） */
    private String deviceIp;
    private boolean online;
    private LocalDateTime onlineTime;
    /** 最近一次掉线时间（在线时为 null；宽限期内条目已置 online=false 但仍在注册表里） */
    private LocalDateTime offlineTime;
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

    @JsonIgnore
    public long getConnectionGeneration() {
        return connectionGeneration;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
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

    public LocalDateTime getOfflineTime() {
        return offlineTime;
    }

    public void setOfflineTime(LocalDateTime offlineTime) {
        this.offlineTime = offlineTime;
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
