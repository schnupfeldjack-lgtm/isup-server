package com.hiki.isup.exception;

/**
 * 资源不存在（HTTP 404）。例如会话不存在、下载文件不存在。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
