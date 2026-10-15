package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.model.response.DownloadTaskResponse;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.NativeSdkLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;

/**
 * 下载文件名生成测试（含 deviceId 安全化，防止路径穿越）。
 */
@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    @Mock
    private IsupService isupService;
    @Mock
    private DeviceRegistry deviceRegistry;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private FfmpegService ffmpegService;
    @Mock
    private HCISUPCMS cms;
    @Mock
    private FfmpegProcess ffmpegProcess;

    @TempDir
    Path tempDir;

    private DownloadService service;
    private MediaSession session;

    @BeforeEach
    void setUp() {
        IsupProperties properties = new IsupProperties();
        properties.setPublicIp("203.0.113.10");
        properties.setMediaDir(tempDir.toString());
        service = new DownloadService(properties, isupService, deviceRegistry, sessionManager, ffmpegService);
        session = new MediaSession("sess-download", SessionType.DOWNLOAD, "GW4206623", 1);
        lenient().when(deviceRegistry.requireConnection(anyString(), anyString())).thenReturn(new DeviceInfo("GW4206623", 7, "127.0.0.1"));
        lenient().when(sessionManager.create(any(), any(DeviceInfo.class), anyInt())).thenReturn(session);
        lenient().when(isupService.cms()).thenReturn(cms);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("文件名包含设备、通道与时间段")
    void fileName() {
        String name = DownloadService.buildFileName("GW4206623", 1,
                LocalDateTime.of(2026, 9, 19, 14, 0, 0),
                LocalDateTime.of(2026, 9, 19, 14, 10, 0));

        assertEquals("GW4206623_ch1_20260919140000-20260919141000.mp4", name);
        assertTrue(name.endsWith(".mp4"));
    }

    @Test
    @DisplayName("deviceId 中的路径分隔符会被替换，避免路径穿越")
    void fileNameSanitized() {
        String name = DownloadService.buildFileName("../../etc/passwd", 2,
                LocalDateTime.of(2026, 9, 19, 14, 0, 0),
                LocalDateTime.of(2026, 9, 19, 14, 10, 0));

        assertTrue(!name.contains("/"), "文件名不能包含 / : " + name);
        assertTrue(!name.contains("\\"), "文件名不能包含 \\ : " + name);
        assertTrue(!name.contains(".."), "文件名不能包含 .. : " + name);
        assertTrue(name.endsWith("_ch2_20260919140000-20260919141000.mp4"), name);
    }

    @Test
    @DisplayName("StartPlayBack 失败时不起 ffmpeg")
    void sdkStartFailureDoesNotStartFfmpeg() throws Exception {
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenReturn(false);
        when(cms.NET_ECMS_GetLastError()).thenReturn(17);

        assertThrows(IsupException.class, () -> service.startDownload(downloadRequest()));
        verify(ffmpegService, never()).startMp4(any(), any(), anyString());
    }

    @Test
    @DisplayName("先 StartPlayBack，成功后再起 ffmpeg，最后 StartPushPlayBack")
    void ffmpegStartsAfterSdkThenPush() throws Exception {
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenAnswer(invocation -> {
            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT out = invocation.getArgument(2);
            out.lSessionID = 42;
            out.write();
            return true;
        });
        when(ffmpegService.startMp4(any(), any(), anyString())).thenReturn(ffmpegProcess);
        when(cms.NET_ECMS_StartPushPlayBack(anyInt(), any(), any())).thenReturn(true);

        service.startDownload(downloadRequest());

        InOrder order = inOrder(cms, ffmpegService);
        order.verify(cms).NET_ECMS_StartPlayBack(anyInt(), any(), any());
        order.verify(ffmpegService).startMp4(any(), any(), anyString());
        order.verify(cms).NET_ECMS_StartPushPlayBack(anyInt(), any(), any());
        org.junit.jupiter.api.Assertions.assertNull(session.getRawFilePath(), "手动下载不额外保存原始码流");
    }

    @Test void packingDownloadPreservesSourceInTheSessionDirectory() throws Exception {
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenReturn(true);
        when(cms.NET_ECMS_StartPushPlayBack(anyInt(), any(), any())).thenReturn(true);
        when(ffmpegService.startPackingCapture(any(), any(), anyString())).thenReturn(ffmpegProcess);
        service.startPackingDownload(downloadRequest());
        assertEquals(Path.of(session.getOutputDir()).resolve("recording-source.ps").toString(), session.getRawFilePath());
        verify(ffmpegService).startPackingCapture(any(), org.mockito.ArgumentMatchers.eq(Path.of(session.getRawFilePath())), anyString());
        verify(ffmpegService, never()).startMp4(any(), any(), anyString());
    }

    @Test
    void cancelledSynchronousStartKeepsCapacityAndCleansLateSdkId() throws Exception {
        IsupProperties properties = realProperties(1);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        when(loader.isLoaded()).thenReturn(true);
        when(loader.cms()).thenReturn(cms);
        lenient().when(cms.NET_ECMS_StopPlayBack(7, 71)).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT out = inv.getArgument(2);
            out.lSessionID = 71;
            out.write();
            return true;
        });
        lenient().when(ffmpegService.startMp4(any(), any(), anyString())).thenReturn(ffmpegProcess);
        lenient().when(cms.NET_ECMS_StartPushPlayBack(anyInt(), any(), any())).thenReturn(true);
        SessionManager realManager = new SessionManager(loader, properties);
        DownloadService realService = new DownloadService(properties, isupService, deviceRegistry,
                realManager, ffmpegService);
        CompletableFuture<DownloadTaskResponse> startup = CompletableFuture.supplyAsync(
                () -> realService.startDownload(downloadRequest()));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            MediaSession pending = realManager.list().get(0);
            realManager.stop(pending, SessionStatus.FAILED, "cancel while starting");
            assertThrows(IllegalStateException.class,
                    () -> realManager.create(SessionType.DOWNLOAD, "GW4206623", 2));
            assertFalse(realManager.prepareSdkUnload(20), "未返回的 native Start 不能卸载 SDK");
            release.countDown();
            startup.get(2, TimeUnit.SECONDS);
            verify(cms, timeout(1000)).NET_ECMS_StopPlayBack(7, 71);
            verify(ffmpegService, never()).startMp4(any(), any(), anyString());
            verify(cms, never()).NET_ECMS_StartPushPlayBack(anyInt(), any(), any());
        } finally {
            release.countDown();
            startup.handle((value, failure) -> null).get(3, TimeUnit.SECONDS);
            realService.shutdown();
            realManager.shutdown();
        }
    }

    @Test
    void packingStartDeadlineReturnsWhileNativeStartIsBlocked() throws Exception {
        IsupProperties properties = realProperties(1);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        when(loader.isLoaded()).thenReturn(true);
        lenient().when(loader.cms()).thenReturn(cms);
        lenient().when(cms.NET_ECMS_StopPlayBack(7, 72)).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        lenient().when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT out = inv.getArgument(2);
            out.lSessionID = 72;
            out.write();
            return true;
        });
        SessionManager realManager = new SessionManager(loader, properties);
        DownloadService realService = new DownloadService(properties, isupService, deviceRegistry,
                realManager, ffmpegService);
        try {
            CompletableFuture<DownloadTaskResponse> call = CompletableFuture.supplyAsync(() ->
                    startPackingWithDeadline(realService, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)));
            DownloadTaskResponse response = call.get(2, TimeUnit.SECONDS);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(SessionStatus.FAILED, response.getStatus());
            assertEquals(SessionStatus.FAILED, realManager.require(response.getTaskId()).getStatus());
            assertThrows(IllegalStateException.class,
                    () -> realManager.create(SessionType.DOWNLOAD, "GW4206623", 2));
            assertFalse(realManager.prepareSdkUnload(20));
            release.countDown();
            verify(cms, timeout(2000)).NET_ECMS_StopPlayBack(7, 72);
            verify(ffmpegService, never()).startPackingCapture(any(), any(), anyString());
            verify(cms, never()).NET_ECMS_StartPushPlayBack(anyInt(), any(), any());
        } finally {
            release.countDown();
            realService.shutdown();
            realManager.shutdown();
        }
    }

    @Test
    void packingStartWorkerLimitRejectsWithoutLeakingSessionCapacity() throws Exception {
        IsupProperties properties = realProperties(5);
        NativeSdkLoader loader = mock(NativeSdkLoader.class);
        lenient().when(loader.isLoaded()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        lenient().when(cms.NET_ECMS_StartPlayBack(anyInt(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await();
            return false;
        });
        SessionManager realManager = new SessionManager(loader, properties);
        DownloadService realService = new DownloadService(properties, isupService, deviceRegistry,
                realManager, ffmpegService);
        try {
            for (int i = 0; i < 4; i++) {
                DownloadTaskResponse response = startPackingWithDeadline(realService,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
                assertEquals(SessionStatus.FAILED, response.getStatus());
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            DownloadTaskResponse rejected = startPackingWithDeadline(realService,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
            assertEquals(SessionStatus.FAILED, rejected.getStatus());
            assertTrue(realManager.require(rejected.getTaskId()).getError().contains("启动线程"));
            verify(cms, times(4)).NET_ECMS_StartPlayBack(anyInt(), any(), any());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            MediaSession replacement = null;
            while (replacement == null && System.nanoTime() < deadline) {
                try {
                    replacement = realManager.create(SessionType.DOWNLOAD, "GW4206623", 2);
                } catch (IllegalStateException capacityNotYetReleased) {
                    Thread.sleep(5);
                }
            }
            org.junit.jupiter.api.Assertions.assertNotNull(replacement, "被拒启动不能泄漏第五个会话名额");
        } finally {
            release.countDown();
            realService.shutdown();
            realManager.shutdown();
        }
    }

    private IsupProperties realProperties(int maxSessions) {
        IsupProperties properties = new IsupProperties();
        properties.setMediaDir(tempDir.toString());
        properties.setPublicIp("203.0.113.10");
        properties.getSession().setMaxActiveSessions(maxSessions);
        return properties;
    }

    private static DownloadTaskResponse startPackingWithDeadline(DownloadService target, long deadlineNanos) {
        return target.startPackingDownload(downloadRequest(), deadlineNanos);
    }

    private static DownloadRequest downloadRequest() {
        DownloadRequest request = new DownloadRequest();
        request.setDeviceId("GW4206623");
        request.setChannel(1);
        request.setStartTime("2026-09-19 14:00:00");
        request.setEndTime("2026-09-19 14:10:00");
        return request;
    }
}
