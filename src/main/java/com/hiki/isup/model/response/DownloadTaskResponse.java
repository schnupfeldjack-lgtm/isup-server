package com.hiki.isup.model.response;

import com.hiki.isup.model.SessionStatus;

/**
 * 下载接口立即返回，不阻塞等待录像下载完成。
 */
public class DownloadTaskResponse {

    private String taskId;
    private SessionStatus status;
    private String deviceId;
    private Integer channel;
    /** 完成后可直接下载 MP4 的地址 */
    private String fileUrl;

    public DownloadTaskResponse() {
    }

    public DownloadTaskResponse(String taskId, SessionStatus status) {
        this.taskId = taskId;
        this.status = status;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public void setStatus(SessionStatus status) {
        this.status = status;
    }

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

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }
}
