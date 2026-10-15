package com.hiki.isup.model.response;

import java.time.LocalDateTime;

/**
 * 通道字符叠加（OSD）下发结果。
 */
public class OverlayTextResponse {

    private String deviceId;
    private int channel;

    /** 本次命中的 ISAPI 路径 */
    private String isapiPath;

    /** 实际显示的完整文本（含前缀） */
    private String displayText;

    /** 设备当前的叠加配置原文（改之前） */
    private String currentXml;

    /** 实际 PUT 出去的 XML（dryRun 时为待下发内容） */
    private String requestXml;

    /** 设备对 PUT 的响应，dryRun 或未下发时为 null */
    private String responseXml;

    /** 是否已真正写入设备 */
    private boolean applied;

    /** 本次是否真的向设备发了 PUT。false 表示设备已有相同配置，为减少 flash 写入而跳过。 */
    private boolean written = true;

    /** 当前画面已属于其他包裹，本次清除未执行。 */
    private boolean superseded;

    /** 设备返回的 statusCode（1=已接受）。设备未返回该节点时为 null */
    private Integer deviceStatusCode;

    /** 设备返回的 statusString，如 OK */
    private String deviceStatusString;

    /** 设备实际存储的坐标（设备 SDK 会把过小值归一化到 528） */
    private double deviceActualPositionX;
    private double deviceActualPositionY;

    /** 是否已调度"恢复默认前缀文本"（durationSec>0 且带了单号时为 true） */
    private boolean hideScheduled;

    /** 单号隐藏的时间戳，未调度时为 null */
    private LocalDateTime hideAt;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public String getIsapiPath() {
        return isapiPath;
    }

    public void setIsapiPath(String isapiPath) {
        this.isapiPath = isapiPath;
    }

    public String getDisplayText() {
        return displayText;
    }

    public void setDisplayText(String displayText) {
        this.displayText = displayText;
    }

    public String getCurrentXml() {
        return currentXml;
    }

    public void setCurrentXml(String currentXml) {
        this.currentXml = currentXml;
    }

    public String getRequestXml() {
        return requestXml;
    }

    public void setRequestXml(String requestXml) {
        this.requestXml = requestXml;
    }

    public String getResponseXml() {
        return responseXml;
    }

    public void setResponseXml(String responseXml) {
        this.responseXml = responseXml;
    }

    public boolean isApplied() {
        return applied;
    }

    public void setApplied(boolean applied) {
        this.applied = applied;
    }

    public boolean isWritten() {
        return written;
    }

    public void setWritten(boolean written) {
        this.written = written;
    }

    public boolean isSuperseded() {
        return superseded;
    }

    public void setSuperseded(boolean superseded) {
        this.superseded = superseded;
    }

    public Integer getDeviceStatusCode() {
        return deviceStatusCode;
    }

    public void setDeviceStatusCode(Integer deviceStatusCode) {
        this.deviceStatusCode = deviceStatusCode;
    }

    public String getDeviceStatusString() {
        return deviceStatusString;
    }

    public void setDeviceStatusString(String deviceStatusString) {
        this.deviceStatusString = deviceStatusString;
    }

    public double getDeviceActualPositionX() {
        return deviceActualPositionX;
    }

    public void setDeviceActualPositionX(double deviceActualPositionX) {
        this.deviceActualPositionX = deviceActualPositionX;
    }

    public double getDeviceActualPositionY() {
        return deviceActualPositionY;
    }

    public void setDeviceActualPositionY(double deviceActualPositionY) {
        this.deviceActualPositionY = deviceActualPositionY;
    }

    public boolean isHideScheduled() {
        return hideScheduled;
    }

    public void setHideScheduled(boolean hideScheduled) {
        this.hideScheduled = hideScheduled;
    }

    public LocalDateTime getHideAt() {
        return hideAt;
    }

    public void setHideAt(LocalDateTime hideAt) {
        this.hideAt = hideAt;
    }
}
