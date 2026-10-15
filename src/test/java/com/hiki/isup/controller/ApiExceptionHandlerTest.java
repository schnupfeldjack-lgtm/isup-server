package com.hiki.isup.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLog() {
        logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLog() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("客户端断开只记一条无堆栈日志，不当成服务器错误")
    void clientDisconnectIsNotServerError() {
        AsyncRequestNotUsableException ex = new AsyncRequestNotUsableException(
                "ServletOutputStream failed to flush: Connection reset by peer",
                new IOException("Connection reset by peer"));

        ResponseEntity<?> response = handler.handleException(ex);

        assertEquals(499, response.getStatusCode().value());
        assertTrue(appender.list.stream().noneMatch(event -> event.getLevel() == Level.ERROR),
                "客户端断开不应打 ERROR");
        assertTrue(appender.list.stream().anyMatch(event -> event.getThrowableProxy() == null),
                "客户端断开不应带堆栈");
    }

    @Test
    @DisplayName("未知异常仍按服务器错误记录堆栈")
    void unexpectedExceptionStaysError() {
        ResponseEntity<?> response = handler.handleException(new RuntimeException("boom"));

        assertEquals(500, response.getStatusCode().value());

        assertTrue(appender.list.stream().anyMatch(event ->
                event.getLevel() == Level.ERROR && event.getThrowableProxy() != null));
    }
}
