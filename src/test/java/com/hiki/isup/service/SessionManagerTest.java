package com.hiki.isup.service;

import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.NativeSdkLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话管理测试：不依赖真实海康 SDK（NativeSdkLoader 未加载时跳过 SDK 调用）。
 */
class SessionManagerTest {

    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(new NativeSdkLoader());
        sessionManager.configureTimeouts(3600, 3600, 3600);
    }

    @AfterEach
    void tearDown() {
        sessionManager.shutdown();
    }

    @Test
    @DisplayName("创建会话后状态为 STARTING，且能通过 sessionId 查到")
    void createSession() {
        MediaSession session = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);

        assertEquals(SessionStatus.STARTING, session.getStatus());
        assertEquals(SessionType.PREVIEW, session.getType());
        assertEquals("GW4206623", session.getDeviceId());
        assertEquals(1, session.getChannel());
        assertSame(session, sessionManager.get(session.getSessionId()));
        assertNotNull(UUID.fromString(session.getSessionId()), "sessionId 应为合法 UUID");
    }

    @Test
    @DisplayName("同一设备不同通道可以同时预览")
    void multipleChannelsOfSameDevice() {
        MediaSession ch1 = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);
        MediaSession ch2 = sessionManager.create(SessionType.PREVIEW, "GW4206623", 2);

        assertEquals(2, sessionManager.list().size());
        assertFalse(ch1.getSessionId().equals(ch2.getSessionId()));
        assertEquals(2L, sessionManager.activeCount());
    }

    @Test
    @DisplayName("ISUP 会话 ID 与本服务 sessionId 能正确关联")
    void isupSessionIndex() {
        MediaSession session = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);
        sessionManager.registerIsupSession(session, 9527);

        assertEquals(9527, session.getIsupSessionId());
        assertSame(session, sessionManager.findByIsupSessionId(SessionType.PREVIEW, 9527));
        // 预览与回放不能混用 session
        assertNull(sessionManager.findByIsupSessionId(SessionType.PLAYBACK, 9527));
    }

    @Test
    @DisplayName("会话 ID 为负数时不建立索引，避免误匹配")
    void negativeIsupSessionIdIgnored() {
        MediaSession session = sessionManager.create(SessionType.PLAYBACK, "GW4206623", 1);
        sessionManager.registerIsupSession(session, -1);

        assertNull(sessionManager.findByIsupSessionId(SessionType.PLAYBACK, -1));
    }

    @Test
    @DisplayName("按设备与通道兜底匹配待接流会话")
    void findPendingByDeviceChannel() {
        MediaSession session = sessionManager.create(SessionType.PLAYBACK, "GW4206623", 3);

        assertSame(session, sessionManager.findPendingByDeviceChannel(SessionType.PLAYBACK, "GW4206623", 3));
        assertNull(sessionManager.findPendingByDeviceChannel(SessionType.PLAYBACK, "GW4206623", 4));
        // 单个待接流会话时才能唯一匹配，否则返回 null
        MediaSession another = sessionManager.create(SessionType.PLAYBACK, "GW4206623", 3);
        assertNull(sessionManager.findPendingByDeviceChannel(SessionType.PLAYBACK, "GW4206623", 3));
        assertNotNull(another);
    }

    @Test
    @DisplayName("停止会话后进入终态，且不会被重复停止")
    void stopOnce() {
        MediaSession session = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);

        sessionManager.stop(session, SessionStatus.STOPPED, "客户端主动停止");
        assertEquals(SessionStatus.STOPPED, session.getStatus());
        assertTrue(session.isFinished());

        // 第二次停止不应改变状态
        sessionManager.stop(session, SessionStatus.FAILED, "重复停止");
        assertEquals(SessionStatus.STOPPED, session.getStatus());
        assertEquals("客户端主动停止", session.getError());
    }

    @Test
    @DisplayName("设备下线会停止该设备的全部会话")
    void stopByDevice() {
        sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);
        sessionManager.create(SessionType.PLAYBACK, "GW4206623", 2);
        sessionManager.create(SessionType.PREVIEW, "OTHER", 1);

        sessionManager.stopByDevice("GW4206623");

        List<MediaSession> all = sessionManager.list();
        assertEquals(3, all.size());
        assertEquals(2, all.stream()
                .filter(s -> "GW4206623".equals(s.getDeviceId()))
                .filter(MediaSession::isFinished)
                .count());
        assertEquals(SessionStatus.STARTING, all.stream()
                .filter(s -> "OTHER".equals(s.getDeviceId()))
                .findFirst().orElseThrow().getStatus());
    }

    @Test
    @DisplayName("未找到会话时 require 抛出明确异常")
    void requireThrows() {
        String unknown = UUID.randomUUID().toString();
        try {
            sessionManager.require(unknown);
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("会话不存在"), e.getMessage());
        }
    }

    @Test
    @DisplayName("异步停止（SDK 回调线程用）最终也会让会话进入终态")
    void stopAsyncEventuallyFinishes() throws Exception {
        MediaSession session = sessionManager.create(SessionType.PLAYBACK, "GW4206623", 1);

        sessionManager.stopAsync(session, SessionStatus.COMPLETED, "回放结束");

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !session.isFinished()) {
            Thread.sleep(20);
        }
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
        assertTrue(session.isFinished());
    }

    @Test
    @DisplayName("releaseHook 只在会话结束时执行一次")
    void releaseHookRunsOnce() {
        MediaSession session = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);
        int[] counter = new int[1];
        session.setReleaseHook(() -> counter[0]++);

        session.runReleaseHook();
        session.runReleaseHook();

        assertEquals(1, counter[0]);
    }
}
