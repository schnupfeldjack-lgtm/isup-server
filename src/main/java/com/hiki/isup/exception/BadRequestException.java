package com.hiki.isup.exception;

/**
 * 请求参数或业务状态不满足要求（HTTP 400）。
 * 例如：时间范围非法、设备不在线、会话状态不允许该操作。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
