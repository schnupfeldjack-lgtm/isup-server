package com.hiki.isup.exception;

/**
 * ISUP 操作异常。
 *
 * <p>禁止只写 throw new RuntimeException("失败")。
 * 异常信息必须包含：操作名 / deviceId / channel / sessionId / SDK errorCode，
 * 保证线上日志能直接定位到是哪台设备、哪个通道、哪个 SDK 调用出错。</p>
 */
public class IsupException extends RuntimeException {

    private final String operation;
    private final String deviceId;
    private final Integer channel;
    private final String sessionId;
    private final Integer errorCode;

    private IsupException(String operation, String deviceId, Integer channel,
                          String sessionId, Integer errorCode, String message, Throwable cause) {
        super(buildMessage(operation, deviceId, channel, sessionId, errorCode, message), cause);
        this.operation = operation;
        this.deviceId = deviceId;
        this.channel = channel;
        this.sessionId = sessionId;
        this.errorCode = errorCode;
    }

    /**
     * SDK 调用失败（带海康 SDK 错误码）
     */
    public static IsupException sdkFailed(String operation, String deviceId, Integer channel,
                                          String sessionId, int errorCode) {
        return new IsupException(operation, deviceId, channel, sessionId, errorCode, null, null);
    }

    /**
     * SDK 调用失败，附带补充说明
     */
    public static IsupException sdkFailed(String operation, String deviceId, Integer channel,
                                          String sessionId, int errorCode, String extra) {
        return new IsupException(operation, deviceId, channel, sessionId, errorCode, extra, null);
    }

    /**
     * 非 SDK 类的业务失败（无 errorCode）
     */
    public static IsupException failed(String operation, String deviceId, Integer channel,
                                       String sessionId, String reason) {
        return new IsupException(operation, deviceId, channel, sessionId, null, reason, null);
    }

    /**
     * 包装其他异常，保留原始堆栈便于排查
     */
    public static IsupException wrap(String operation, String deviceId, Integer channel,
                                     String sessionId, Throwable cause) {
        String reason = cause == null ? "未知异常" : (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        return new IsupException(operation, deviceId, channel, sessionId, null, reason, cause);
    }

    private static String buildMessage(String operation, String deviceId, Integer channel,
                                       String sessionId, Integer errorCode, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append(operation).append(" failed:");
        if (deviceId != null) {
            sb.append(" deviceId=").append(deviceId);
        }
        if (channel != null) {
            sb.append(" channel=").append(channel);
        }
        if (sessionId != null) {
            sb.append(" sessionId=").append(sessionId);
        }
        if (errorCode != null) {
            sb.append(" errorCode=").append(errorCode);
        }
        if (extra != null && !extra.isBlank()) {
            sb.append(" detail=").append(extra);
        }
        return sb.toString();
    }

    public String getOperation() {
        return operation;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Integer getChannel() {
        return channel;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Integer getErrorCode() {
        return errorCode;
    }
}
