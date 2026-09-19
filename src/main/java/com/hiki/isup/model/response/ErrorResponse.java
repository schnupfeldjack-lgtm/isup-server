package com.hiki.isup.model.response;

import java.util.List;

/**
 * 统一错误响应。
 */
public class ErrorResponse {

    private String error;
    private String message;
    /** 失败的 ISUP 操作名 */
    private String operation;
    private String deviceId;
    private Integer channel;
    private String sessionId;
    /** 海康 SDK 错误码 */
    private Integer errorCode;
    private List<String> fieldErrors;

    public ErrorResponse() {
    }

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public List<String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(List<String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }
}
