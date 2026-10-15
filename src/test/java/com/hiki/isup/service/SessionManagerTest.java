package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.hiki.isup.sdk.HCISUPStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

/**
 * 会话管理测试：不依赖真实海康 SDK（NativeSdkLoader 未加载时跳过 SDK 调用）。
 */
class SessionManagerTest {

    private SessionManager sessionManager;
    private IsupProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IsupProperties();
        properties.getSession().setStartTimeoutSec(3600);
        properties.getSession().setIdleTimeoutSec(3600);
        properties.getSession().setDownloadMaxSec(3600);
        properties.getSession().setDownloadRetainMinutes(60);
        sessionManager = new SessionManager(new NativeSdkLoader(), properties);
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

    @Test void diagnosticsUseReservedSlotsAndAdmissionBlocksBeforeClaimingMoreWork() {
        properties.getSession().setMaxActiveSessions(1);
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "device", 1);
        assertEquals(1, sessionManager.resourceSnapshot().reservedSlots());
        assertTrue(sessionManager.recordingAdmissionBlocked());
        assertTrue(session.tryBeginNativeOperation());
        sessionManager.stop(session, SessionStatus.FAILED, "test native lease");
        assertEquals(1, sessionManager.resourceSnapshot().reservedSlots());
        assertEquals(1, sessionManager.resourceSnapshot().pendingCleanup());
        assertTrue(sessionManager.resourceSnapshot().oldestCleanupAgeMillis() >= 0);
        assertTrue(sessionManager.recordingAdmissionBlocked());
        sessionManager.endNativeOperation(session);
    }

    @Test void recordingAdmissionPausesBeforeGlobalPacketBudgetIsExhausted() throws Exception {
        java.lang.reflect.Field field = FfmpegProcess.class.getDeclaredField("DEFAULT_MEMORY_BUDGET");
        field.setAccessible(true);
        FfmpegProcess.MemoryBudget budget = (FfmpegProcess.MemoryBudget) field.get(null);
        long before = budget.usedBytes.get();
        try {
            budget.usedBytes.set(budget.globalBytes - budget.maxPacketBytes + 1);
            assertTrue(sessionManager.recordingAdmissionBlocked(), "不足一个最大包容量时停止领取，不能先消耗订单重试次数");
            budget.usedBytes.set(budget.globalBytes - budget.maxPacketBytes);
            assertFalse(sessionManager.recordingAdmissionBlocked(), "释放缓存后可恢复领取，已有流不受新门控干预");
        } finally { budget.usedBytes.set(before); }
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
    void cleanupRemovesEveryNativeIdAlias() {
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "device", 1);
        sessionManager.registerIsupSession(session, 41);
        sessionManager.registerIsupSession(session, 42);
        sessionManager.stop(session, SessionStatus.FAILED, "test cleanup");
        assertSame(session, sessionManager.findByIsupSessionId(SessionType.DOWNLOAD, 41),
                "终态保留期间旧 exact 必须可识别，不能兜底到新任务");
        sessionManager.discardDownload(session.getSessionId());
        assertNull(sessionManager.findByIsupSessionId(SessionType.DOWNLOAD, 41));
        assertNull(sessionManager.findByIsupSessionId(SessionType.DOWNLOAD, 42));
        var index = org.springframework.test.util.ReflectionTestUtils.getField(sessionManager, "isupSessionIndex");
        assertTrue(((java.util.Map<?, ?>) index).isEmpty(), "SDK 返回不同实际 ID 后不能遗留旧索引");
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
    void awaitFinishedReturnsWhileNativeStopIsBlockedAndRetainsResources() throws Exception {
        properties.getSession().setMaxActiveSessions(1);
        CountDownLatch nativeEntered = new CountDownLatch(1);
        CountDownLatch nativeRelease = new CountDownLatch(1);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_StopPlayBack(17)).thenAnswer(inv -> {
            nativeEntered.countDown();
            nativeRelease.await();
            return true;
        });
        sessionManager.shutdown();
        sessionManager = new SessionManager(loader, properties);
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setLinkHandle(17);
        sessionManager.registerIsupSession(session, 33);
        Object callback = new Object();
        session.setDataCallback(callback);
        AtomicBoolean callbackReleased = new AtomicBoolean();
        session.setReleaseHook(() -> callbackReleased.set(true));
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        when(ffmpeg.waitFor(anyLong())).thenReturn(0);
        session.setFfmpegProcess(ffmpeg);

        CompletableFuture<MediaSession> waiting = CompletableFuture.supplyAsync(
                () -> sessionManager.awaitFinished(session.getSessionId(), 20));
        try {
            assertTrue(nativeEntered.await(2, TimeUnit.SECONDS));
            assertSame(session, waiting.get(1, TimeUnit.SECONDS), "native Stop 不能扩大等待期限");
            assertEquals(SessionStatus.FAILED, session.getStatus());
            verify(ffmpeg, timeout(1000)).destroy();
            assertFalse(callbackReleased.get(), "native 尚未确认停止，不能释放 callback");
            assertSame(callback, session.getDataCallback());
            assertThrows(IllegalStateException.class,
                    () -> sessionManager.create(SessionType.DOWNLOAD, "DLWH", 2));
            session.setUpdatedAt(java.time.LocalDateTime.now().minusHours(2));
            sessionManager.sweep();
            sessionManager.discardDownload(session.getSessionId());
            assertSame(session, sessionManager.get(session.getSessionId()), "清理不能遗忘仍被 native 使用的会话");
            assertFalse(callbackReleased.get());
        } finally {
            nativeRelease.countDown();
            waiting.get(3, TimeUnit.SECONDS);
        }
        awaitCondition(callbackReleased::get);
        assertNotNull(sessionManager.create(SessionType.DOWNLOAD, "DLWH", 2));
    }

    @Test
    void normalDownloadFinishesLocallyWhileNativeStopIsBlocked(@TempDir Path tempDir) throws Exception {
        CountDownLatch nativeEntered = new CountDownLatch(1);
        CountDownLatch nativeRelease = new CountDownLatch(1);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_StopPlayBack(18)).thenAnswer(inv -> {
            nativeEntered.countDown();
            nativeRelease.await();
            return true;
        });
        sessionManager.shutdown();
        sessionManager = new SessionManager(loader, properties);
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setLinkHandle(18);
        Path file = Files.write(tempDir.resolve("normal.mp4"), new byte[]{1, 2, 3});
        session.setFilePath(file.toString());
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        when(ffmpeg.waitFor(anyLong())).thenReturn(0);
        session.setFfmpegProcess(ffmpeg);
        AtomicBoolean released = new AtomicBoolean();
        session.setReleaseHook(() -> released.set(true));
        try {
            sessionManager.finishDownloadAsync(session, null);
            assertTrue(nativeEntered.await(2, TimeUnit.SECONDS));
            assertTrue(session.awaitTerminal(1000), "正常收尾也不能被 native Stop 阻塞");
            assertEquals(SessionStatus.COMPLETED, session.getStatus());
            verify(ffmpeg, timeout(1000)).destroy();
            assertFalse(released.get());
        } finally {
            nativeRelease.countDown();
        }
        awaitCondition(released::get);
    }

    @Test
    void timedOutDownloadCannotBecomeCompletedAfterLocalDrain(@TempDir Path tempDir) throws Exception {
        properties.getSession().setMaxActiveSessions(1);
        CountDownLatch drainEntered = new CountDownLatch(1);
        CountDownLatch drainRelease = new CountDownLatch(1);
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setFilePath(Files.write(tempDir.resolve("cancelled.mp4"), new byte[]{1}).toString());
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        when(ffmpeg.waitFor(anyLong())).thenAnswer(inv -> {
            drainEntered.countDown();
            drainRelease.await();
            return 0;
        });
        session.setFfmpegProcess(ffmpeg);
        try {
            sessionManager.finishDownloadAsync(session, null);
            assertTrue(drainEntered.await(2, TimeUnit.SECONDS));
            MediaSession result = sessionManager.awaitFinished(session.getSessionId(), 20);
            assertEquals(SessionStatus.FAILED, result.getStatus());
            assertEquals("等待下载超时", result.getError());
            assertThrows(IllegalStateException.class,
                    () -> sessionManager.create(SessionType.DOWNLOAD, "DLWH", 2),
                    "本地 writer 仍未退出，不能提前交还名额");
        } finally {
            drainRelease.countDown();
        }
        verify(ffmpeg, timeout(1000)).destroy();
        assertEquals(SessionStatus.FAILED, session.getStatus());
    }

    @Test
    void packingDownloadCompletesFromRawSourceWithoutMp4(@TempDir Path tempDir) throws Exception {
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setFilePath(tempDir.resolve("future.mp4").toString());
        session.setRawFilePath(Files.write(tempDir.resolve("recording-source.ps"), new byte[]{1, 2, 3}).toString());
        FfmpegProcess writer = mock(FfmpegProcess.class);
        when(writer.waitFor(anyLong())).thenReturn(0);
        session.setFfmpegProcess(writer);

        sessionManager.finishDownload(session, null);

        assertEquals(SessionStatus.COMPLETED, session.getStatus());
        assertEquals(3, session.getFileSize());
        assertFalse(Files.exists(Path.of(session.getFilePath())));
    }

    @Test
    void nativeStopFailureRetainsCallbackAndCapacity() throws Exception {
        properties.getSession().setMaxActiveSessions(1);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_StopPlayBack(19)).thenReturn(false);
        sessionManager.shutdown();
        sessionManager = new SessionManager(loader, properties);
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setLinkHandle(19);
        AtomicBoolean released = new AtomicBoolean();
        session.setReleaseHook(() -> released.set(true));

        sessionManager.stop(session, SessionStatus.FAILED, "failed");

        verify(stream, timeout(1000)).NET_ESTREAM_StopPlayBack(19);
        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertFalse(released.get());
        assertThrows(IllegalStateException.class,
                () -> sessionManager.create(SessionType.DOWNLOAD, "DLWH", 2));
        when(stream.NET_ESTREAM_StopPlayBack(19)).thenReturn(true);
        awaitCondition(() -> {
            sessionManager.sweep();
            return released.get();
        });
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void prepareSdkUnloadTimesOutWithoutDroppingBlockedNativeCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        HCISUPStream stream = mock(HCISUPStream.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.stream()).thenReturn(stream);
        when(stream.NET_ESTREAM_StopPlayBack(20)).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            return true;
        });
        sessionManager.shutdown();
        sessionManager = new SessionManager(loader, properties);
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setLinkHandle(20);
        Object callback = new Object();
        session.setDataCallback(callback);
        AtomicBoolean released = new AtomicBoolean();
        session.setReleaseHook(() -> released.set(true));
        sessionManager.stop(session, SessionStatus.FAILED, "shutdown");
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertFalse(sessionManager.prepareSdkUnload(20));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
            assertSame(callback, session.getDataCallback());
            assertFalse(released.get());
            assertThrows(IllegalStateException.class,
                    () -> sessionManager.create(SessionType.DOWNLOAD, "DLWH", 2));
        } finally {
            release.countDown();
        }
        awaitCondition(released::get);
    }

    @Test
    void prepareSdkUnloadWaitsForLocalWriterBeforeAllowingUnload() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        FfmpegProcess process = mock(FfmpegProcess.class);
        when(process.waitFor(anyLong())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            return 0;
        });
        session.setFfmpegProcess(process);
        sessionManager.stop(session, SessionStatus.FAILED, "shutdown");
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(sessionManager.prepareSdkUnload(20));
        } finally {
            release.countDown();
        }
        assertTrue(sessionManager.prepareSdkUnload(2000));
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

    @Test
    @DisplayName("并发上限内可正常创建，达到上限后新建会话抛异常")
    void maxActiveSessionsLimit() {
        properties.getSession().setMaxActiveSessions(2);
        sessionManager = new SessionManager(new NativeSdkLoader(), properties);

        MediaSession first = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);
        MediaSession second = sessionManager.create(SessionType.PREVIEW, "GW4206623", 2);

        try {
            sessionManager.create(SessionType.PREVIEW, "GW4206623", 3);
            throw new AssertionError("超出并发上限时应抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("并发会话数已达上限"), e.getMessage());
        }

        sessionManager.stop(first, SessionStatus.STOPPED, "手动停止");

        // 名额随会话停止释放，否则上限会被永久占满
        MediaSession third = sessionManager.create(SessionType.PREVIEW, "GW4206623", 3);
        assertNotNull(third);
        assertEquals(2L, sessionManager.activeCount());
        assertEquals(SessionStatus.STOPPED, first.getStatus());
    }

    @Test
    @DisplayName("并发上限为 0 时表示不限制")
    void maxActiveSessionsUnlimited() {
        properties.getSession().setMaxActiveSessions(0);
        sessionManager = new SessionManager(new NativeSdkLoader(), properties);

        for (int i = 1; i <= 20; i++) {
            assertNotNull(sessionManager.create(SessionType.PREVIEW, "GW4206623", i));
        }
        assertEquals(20L, sessionManager.activeCount());
    }

    @Test
    @DisplayName("discardDownload 立即删掉本地下载目录并从会话表移除")
    void discardDownloadRemovesSessionAndFiles(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("task-1");
        Files.createDirectories(dir);
        Path file = Files.write(dir.resolve("a.mp4"), new byte[]{1, 2, 3});
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH1xx", 1);
        session.setStatus(SessionStatus.COMPLETED);
        session.setOutputDir(dir.toString());
        session.setFilePath(file.toString());

        sessionManager.discardDownload(session.getSessionId());

        assertNull(sessionManager.get(session.getSessionId()));
        assertFalse(Files.exists(file), file.toString());
        assertFalse(Files.exists(dir), dir.toString());
    }

    @Test
    void discardDownloadMustRespectFileLease(@TempDir Path tempDir) throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("leased"));
        Path file = Files.write(dir.resolve("source.ps"), new byte[]{1, 2, 3});
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setStatus(SessionStatus.COMPLETED);
        session.setOutputDir(dir.toString());
        assertTrue(session.retainFiles());
        sessionManager.discardDownload(session.getSessionId());
        assertSame(session, sessionManager.get(session.getSessionId()));
        assertTrue(Files.exists(file));
        session.releaseFiles();
        sessionManager.discardDownload(session.getSessionId());
        assertNull(sessionManager.get(session.getSessionId()));
        assertFalse(Files.exists(file));
    }

    @Test
    void failedDeletionKeepsSessionForWatchdogRetry(@TempDir Path tempDir) throws Exception {
        for (boolean discard : new boolean[]{true, false}) {
            Path dir = Files.createDirectory(tempDir.resolve("delete-fails-" + discard));
            Path file = Files.write(dir.resolve("source.ps"), new byte[]{1, 2, 3});
            MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
            session.setStatus(SessionStatus.COMPLETED);
            session.setUpdatedAt(java.time.LocalDateTime.now().minusHours(2));
            session.setOutputDir(dir.toString());
            try (org.mockito.MockedStatic<SessionManager> deletion = org.mockito.Mockito.mockStatic(SessionManager.class)) {
                deletion.when(() -> SessionManager.deleteRecursively(dir))
                        .thenThrow(new java.io.IOException("temporary filesystem failure")).thenCallRealMethod();
                if (discard) sessionManager.discardDownload(session.getSessionId());
                else sessionManager.sweep();
                assertSame(session, sessionManager.get(session.getSessionId()), "discard=" + discard);
                assertTrue(Files.exists(file));
                sessionManager.sweep();
                assertNull(sessionManager.get(session.getSessionId()));
                assertFalse(Files.exists(file));
            }
        }
    }

    @Test
    void explicitCleanupFailureRetriesWithoutWaitingForRetention(@TempDir Path tempDir) throws Exception {
        for (int retainMinutes : new int[]{0, 60}) {
            properties.getSession().setDownloadRetainMinutes(retainMinutes);
            Path dir = Files.createDirectory(tempDir.resolve("immediate-retry-" + retainMinutes));
            Path file = Files.write(dir.resolve("source.ps"), new byte[]{1, 2, 3});
            MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
            session.setStatus(SessionStatus.COMPLETED);
            session.setOutputDir(dir.toString());
            try (org.mockito.MockedStatic<SessionManager> deletion = org.mockito.Mockito.mockStatic(SessionManager.class)) {
                deletion.when(() -> SessionManager.deleteRecursively(dir))
                        .thenThrow(new java.io.IOException("temporary filesystem failure")).thenCallRealMethod();
                sessionManager.discardDownload(session.getSessionId());
                assertSame(session, sessionManager.get(session.getSessionId()));
                assertTrue(Files.exists(file));

                sessionManager.sweep();

                assertNull(sessionManager.get(session.getSessionId()), "retainMinutes=" + retainMinutes);
                assertFalse(Files.exists(file));
            }
        }
    }

    @Test
    void sweepMustNotDeleteDownloadDuringQueuedCutOrUpload(@TempDir Path tempDir) throws Exception {
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        Path dir = Files.createDirectory(tempDir.resolve("retained"));
        Path file = Files.write(dir.resolve("source.mp4"), new byte[]{1});
        session.setOutputDir(dir.toString());
        session.setStatus(SessionStatus.COMPLETED);
        session.setUpdatedAt(java.time.LocalDateTime.now().minusHours(2));
        assertTrue(session.retainFiles());
        var sweep = SessionManager.class.getDeclaredMethod("sweepFinished", MediaSession.class, long.class);
        sweep.setAccessible(true);

        sweep.invoke(sessionManager, session, System.currentTimeMillis());
        assertTrue(Files.exists(file));
        assertSame(session, sessionManager.get(session.getSessionId()));

        session.releaseFiles();
        sweep.invoke(sessionManager, session, System.currentTimeMillis());
        assertFalse(Files.exists(file));
        assertNull(sessionManager.get(session.getSessionId()));
        assertFalse(session.retainFiles(), "清理开始后不能再接管旧路径");
    }

    @Test
    @DisplayName("下载收尾先关闭 ffmpeg stdin，再等待进程退出")
    void finishDownloadClosesStdinBeforeWait(@TempDir Path tempDir) throws Exception {
        Path file = Files.write(tempDir.resolve("record.mp4"), new byte[]{1, 2, 3, 4});
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH1xx", 1);
        session.setFilePath(file.toString());
        session.setOutputDir(tempDir.toString());
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        when(ffmpeg.waitFor(anyLong())).thenReturn(0);
        session.setFfmpegProcess(ffmpeg);

        sessionManager.finishDownload(session, null);

        InOrder order = inOrder(ffmpeg);
        order.verify(ffmpeg).closeStdin();
        order.verify(ffmpeg).waitFor(anyLong());
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
    }

    @Test
    @DisplayName("ffmpeg 写入中断时，即使 MP4 已有字节也判失败并删掉半成品")
    void finishDownloadFailsWhenWriteStalled(@TempDir Path tempDir) throws Exception {
        Path file = Files.write(tempDir.resolve("record.mp4"), new byte[]{1, 2, 3, 4});
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH1xx", 1);
        session.setFilePath(file.toString());
        session.setOutputDir(tempDir.toString());
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        when(ffmpeg.isWriteFailed()).thenReturn(true);
        when(ffmpeg.waitFor(anyLong())).thenReturn(0);
        session.setFfmpegProcess(ffmpeg);

        sessionManager.finishDownload(session, "回放结束");

        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals(SessionManager.INCOMPLETE_DOWNLOAD, session.getError());
        assertFalse(Files.exists(file), file.toString());
    }

    @Test
    @DisplayName("下载超时强制结束时，已有字节的 MP4 也判失败并删除")
    void finishDownloadFailsWhenTimedOut(@TempDir Path tempDir) throws Exception {
        Path file = Files.write(tempDir.resolve("record.mp4"), new byte[]{1, 2, 3, 4});
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH1xx", 1);
        session.setFilePath(file.toString());
        session.setOutputDir(tempDir.toString());
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        when(ffmpeg.isWriteFailed()).thenReturn(false);
        when(ffmpeg.waitFor(anyLong())).thenReturn(0);
        session.setFfmpegProcess(ffmpeg);

        sessionManager.finishDownload(session, SessionManager.DOWNLOAD_TIMEOUT);

        assertEquals(SessionStatus.FAILED, session.getStatus());
        assertEquals(SessionManager.DOWNLOAD_TIMEOUT, session.getError());
        assertFalse(Files.exists(file), file.toString());
    }

    @Test
    void downloadRejectsFailedOrUnconfirmedFfmpegExit(@TempDir Path tempDir) throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            for (Integer exit : new Integer[]{1, null}) {
                Path dir = Files.createDirectory(tempDir.resolve("exit-" + fallback + "-" + exit));
                Path file = Files.write(dir.resolve("record.mp4"), new byte[]{1, 2, 3});
                MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
                session.setFilePath(file.toString());
                session.setOutputDir(dir.toString());
                FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
                when(ffmpeg.waitFor(anyLong())).thenReturn(exit);
                session.setFfmpegProcess(ffmpeg);
                if (fallback) {
                    sessionManager.finishDownloadAsync(session, "回放结束");
                    awaitCondition(() -> !Files.exists(file));
                } else {
                    sessionManager.finishDownload(session, "回放结束");
                }
                assertEquals(SessionStatus.FAILED, session.getStatus(), "fallback=" + fallback + ", exit=" + exit);
                assertFalse(Files.exists(file));
            }
        }
    }

    @Test
    void downloadDetectsWriteFailureWhileDraining(@TempDir Path tempDir) throws Exception {
        for (boolean fallback : new boolean[]{false, true}) {
            Path dir = Files.createDirectory(tempDir.resolve("drain-" + fallback));
            Path file = Files.write(dir.resolve("record.mp4"), new byte[]{1, 2, 3});
            MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
            session.setFilePath(file.toString());
            session.setOutputDir(dir.toString());
            FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
            AtomicBoolean failed = new AtomicBoolean();
            when(ffmpeg.isWriteFailed()).thenAnswer(inv -> failed.get());
            when(ffmpeg.waitFor(anyLong())).thenAnswer(inv -> { failed.set(true); return 0; });
            session.setFfmpegProcess(ffmpeg);
            if (fallback) {
                sessionManager.finishDownloadAsync(session, "回放结束");
                awaitCondition(() -> !Files.exists(file));
            } else {
                sessionManager.finishDownload(session, "回放结束");
            }
            assertEquals(SessionStatus.FAILED, session.getStatus(), "fallback=" + fallback);
            assertFalse(Files.exists(file));
        }
    }

    @Test
    void downloadAsyncWaitsForSuccessfulMuxBeforeCompleting(@TempDir Path tempDir) throws Exception {
        Path file = Files.write(tempDir.resolve("fallback.mp4"), new byte[]{1, 2, 3});
        MediaSession session = sessionManager.create(SessionType.DOWNLOAD, "DLWH", 1);
        session.setFilePath(file.toString());
        session.setOutputDir(tempDir.toString());
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        when(ffmpeg.waitFor(anyLong())).thenReturn(0);
        session.setFfmpegProcess(ffmpeg);
        sessionManager.finishDownloadAsync(session, "回放结束");
        assertTrue(session.awaitTerminal(2000));
        InOrder order = inOrder(ffmpeg);
        order.verify(ffmpeg).closeStdin();
        order.verify(ffmpeg).waitFor(anyLong());
        order.verify(ffmpeg).isWriteFailed();
        assertEquals(SessionStatus.COMPLETED, session.getStatus());
        assertTrue(Files.exists(file));
    }

    @Test
    @DisplayName("停止线程池拒绝新任务时，先关闭输入并保留清理任务和资源名额")
    void stopAsyncRetainsResourcesWhenPoolRejected() {
        properties.getSession().setMaxActiveSessions(1);
        MediaSession session = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        session.setFfmpegProcess(ffmpeg);
        AtomicBoolean released = new AtomicBoolean();
        session.setReleaseHook(() -> released.set(true));
        sessionManager.shutdownStopExecutor();

        assertDoesNotThrow(() -> sessionManager.stopAsync(session, SessionStatus.STOPPED, "pool-full"));

        verify(ffmpeg).closeStdin();
        assertEquals(SessionStatus.STOPPED, session.getStatus());
        assertTrue(session.isFinished());
        assertFalse(released.get());
        assertThrows(IllegalStateException.class,
                () -> sessionManager.create(SessionType.PREVIEW, "GW4206623", 2));
    }

    @Test
    @DisplayName("看护扫终态会话时，仍活着的 ffmpeg 会被销毁并从会话表移除")
    void sweepFinishedDestroysLeftoverFfmpeg(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("preview-1");
        Files.createDirectories(dir);
        Files.write(dir.resolve("seg.ts"), new byte[]{1});
        MediaSession session = sessionManager.create(SessionType.PREVIEW, "GW4206623", 1);
        FfmpegProcess ffmpeg = mock(FfmpegProcess.class);
        session.setFfmpegProcess(ffmpeg);
        session.setOutputDir(dir.toString());
        session.markStopping();
        session.setStatus(SessionStatus.STOPPED);
        session.setUpdatedAt(java.time.LocalDateTime.now().minusMinutes(11));

        sessionManager.sweep();

        verify(ffmpeg).destroy();
        assertNull(sessionManager.get(session.getSessionId()));
        assertFalse(Files.exists(dir), dir.toString());
    }

    @Test
    @DisplayName("启动清理会删掉上一进程遗留的 download 目录")
    void purgeTempMediaDirsRemovesOrphanDownloads(@TempDir Path tempDir) throws Exception {
        Path download = tempDir.resolve("download").resolve("task-1");
        Files.createDirectories(download);
        Path file = Files.write(download.resolve("a.mp4"), new byte[]{1, 2, 3});
        Path preview = tempDir.resolve("preview").resolve("sess-1");
        Files.createDirectories(preview);

        int removed = sessionManager.purgeTempMediaDirs(tempDir);

        assertTrue(removed >= 2, "应至少删掉 download 与 preview 各一个目录");
        assertFalse(Files.exists(file), file.toString());
        assertFalse(Files.exists(download), download.toString());
        assertFalse(Files.exists(preview), preview.toString());
    }
}
