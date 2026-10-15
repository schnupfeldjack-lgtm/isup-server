package com.hiki.isup.controller;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.exception.NotFoundException;
import com.hiki.isup.model.response.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.List;

/**
 * 统一异常处理。
 *
 * <p>ISUP 相关异常会带上 operation / deviceId / channel / sessionId / errorCode，
 * 保证 ERP 侧和日志都能直接定位问题。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * ISUP SDK 或业务失败
     */
    @ExceptionHandler(IsupException.class)
    public ResponseEntity<ErrorResponse> handleIsupException(IsupException e) {
        log.error("ISUP 操作失败：{}", e.getMessage(), e);
        ErrorResponse body = new ErrorResponse("ISUP_ERROR", e.getMessage());
        body.setOperation(e.getOperation());
        body.setDeviceId(e.getDeviceId());
        body.setChannel(e.getChannel());
        body.setSessionId(e.getSessionId());
        body.setErrorCode(e.getErrorCode());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e) {
        log.warn("请求参数或状态非法：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        log.warn("资源不存在：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();
        log.warn("参数校验失败：{}", errors);
        ErrorResponse body = new ErrorResponse("BAD_REQUEST", "参数校验失败");
        body.setFieldErrors(errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", "请求体 JSON 解析失败：" + e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }

    /**
     * 并发会话数触顶等资源受限场景。属于可重试的临时状态，用 503 让调用方稍后重试，
     * 而不是打成 500 让人误以为服务坏了。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.warn("服务资源受限：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("TOO_MANY_SESSIONS", e.getMessage()));
    }

    /**
     * 静态资源不存在，最常见的就是浏览器自动发起的 /favicon.ico 请求。
     *
     * <p>这类请求不是业务故障，只回 404 且不打堆栈，否则每次打开页面都会刷一条
     * ERROR + 完整堆栈，把真正的错误日志淹掉。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.debug("静态资源不存在：{}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        String code = status == null ? "HTTP_ERROR" : status.name();
        log.debug("HTTP 请求未完成：status={}, reason={}", e.getStatusCode(), e.getReason());
        return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders())
                .body(new ErrorResponse(code, e.getReason()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Void> handleNoHandlerFound(NoHandlerFoundException e) {
        log.debug("请求路径不存在：{}", e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        if (isClientDisconnect(e)) {
            log.info("客户端已断开，响应未写回：{}", e.toString());
            return ResponseEntity.status(499).build();
        }
        log.error("服务器内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    /**
     * 调用方提前断开（刷新、超时、取消）。业务往往已经做完，只是响应写不回去。
     */
    static boolean isClientDisconnect(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.endsWith("AsyncRequestNotUsableException") || name.endsWith("ClientAbortException")) {
                return true;
            }
            if (current instanceof IOException && isResetMessage(current.getMessage())) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private static boolean isResetMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("Connection reset")
                || message.contains("Broken pipe")
                || message.contains("中止了一个已建立的连接");
    }

    /** 便于测试断言：暴露字段错误信息转换逻辑 */
    static List<String> toFieldErrors(List<FieldError> fieldErrors) {
        return fieldErrors.stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
    }
}
