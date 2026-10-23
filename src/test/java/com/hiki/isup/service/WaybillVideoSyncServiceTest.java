package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.job.WaybillVideoSyncJob;
import com.hiki.isup.model.CameraChannel;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.DeviceInfo;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionStatus;
import com.hiki.isup.model.SessionType;
import com.hiki.isup.model.WaybillVideoRecord;
import com.hiki.isup.model.WaybillVideoStatus;
import com.hiki.isup.model.request.DownloadRequest;
import com.hiki.isup.model.response.DownloadTaskResponse;
import com.hiki.isup.repository.WaybillVideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WaybillVideoSyncServiceTest {

    private static final String LEGACY_RETRY_FINGERPRINT =
            "413571012796f6477fe224f4dd731d098c7edf77d94fe5010c68af69ffb156f3";

    @Test
    void fairSchedulingActuallyDispatchesThreeTodayThenHistoryWithoutOldPriorityOverride() {
        assertFalse(properties.getWaybillVideo().isFairSchedulingEnabled());
        properties.getWaybillVideo().setFairSchedulingEnabled(true);
        properties.getWaybillVideo().setDownloadConcurrency(1);
        properties.getWaybillVideo().setPriorityIds(List.of("H0"));
        when(ossService.isConfigured()).thenReturn(true);
        var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var pending = new java.util.concurrent.CopyOnWriteArrayList<WaybillVideoRecord>();
        for (int i=0;i<9;i++) pending.add(fairReadyRow("T"+i,day));
        for (int i=0;i<3;i++) pending.add(fairReadyRow("H"+i,day.minusDays(1)));
        stubFairQueue(pending);
        var claimed = new java.util.concurrent.CopyOnWriteArrayList<String>();
        org.mockito.Mockito.doAnswer(inv -> {
            WaybillVideoRecord r=inv.getArgument(0); claimed.add(r.getId()); pending.remove(r); return true;
        }).when(repository).claimSnapshot(any());

        LegacyVideoSyncTestSupport.syncOnce(service);

        assertEquals(List.of("T0","T1","T2","H0","T3","T4","T5","H1","T6","T7","T8","H2"),claimed);
        verify(repository,never()).findPriorityPending(any());
        verify(repository,never()).findPending(org.mockito.ArgumentMatchers.anyInt());
        verify(repository,times(12)).updateStatusSnapshot(any(),eq(WaybillVideoStatus.SUCCESS));
        verify(downloadService,never()).startPackingDownload(any(),anyLong());
    }

    @Test
    void fairFailedCasDoesNotHideHistoryAndNoRecordIsSubmittedTwiceInARound() {
        properties.getWaybillVideo().setFairSchedulingEnabled(true);
        properties.getWaybillVideo().setBatchSize(1);
        properties.getWaybillVideo().setDownloadConcurrency(1);
        when(ossService.isConfigured()).thenReturn(true);
        var day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var today=fairReadyRow("T0",day); var old=fairReadyRow("H0",day.minusDays(1));
        var pending=new java.util.concurrent.CopyOnWriteArrayList<>(List.of(today,old));
        stubFairQueue(pending);
        org.mockito.Mockito.doReturn(false).when(repository).claimSnapshot(today);
        org.mockito.Mockito.doAnswer(inv->{pending.remove(old);return true;}).when(repository).claimSnapshot(old);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository,times(1)).claimSnapshot(today);
        verify(repository,times(1)).claimSnapshot(old);
        verify(repository).updateStatusSnapshot(old,WaybillVideoStatus.SUCCESS);
        verify(repository,never()).updateStatusSnapshot(today,WaybillVideoStatus.SUCCESS);
    }

    @Test
    void fairMidnightDiscardsCachedYesterdayRowsBeforeNextSubmission() {
        properties.getWaybillVideo().setFairSchedulingEnabled(true);
        properties.getWaybillVideo().setDownloadConcurrency(1);
        when(ossService.isConfigured()).thenReturn(true);
        var day=java.time.LocalDate.of(2026,10,21);
        var now=new AtomicReference<>(java.time.Instant.parse("2026-10-21T15:59:59Z"));
        java.time.Clock clock=new java.time.Clock() {
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId z){return this;}
            public java.time.Instant instant(){return now.get();}
        };
        org.springframework.test.util.ReflectionTestUtils.setField(service,"pendingScheduler",new PendingVideoScheduler(clock));
        var pending=new java.util.concurrent.CopyOnWriteArrayList<WaybillVideoRecord>();
        for(int i=0;i<4;i++) pending.add(fairReadyRow("T"+i,day));
        for(int i=0;i<3;i++) pending.add(fairReadyRow("N"+i,day.plusDays(1)));
        stubFairQueue(pending);
        var claimed=new java.util.concurrent.CopyOnWriteArrayList<String>();
        org.mockito.Mockito.doAnswer(inv->{
            WaybillVideoRecord r=inv.getArgument(0); claimed.add(r.getId()); pending.remove(r);
            now.set(java.time.Instant.parse("2026-10-21T16:00:01Z")); return true;
        }).when(repository).claimSnapshot(any());

        LegacyVideoSyncTestSupport.syncOnce(service);

        assertEquals(List.of("T0","N0","N1","N2","T1","T2","T3"),claimed);
    }

    private WaybillVideoRecord fairReadyRow(String id,java.time.LocalDate day) {
        var row=pendingRow(id); row.setStartTime(day.atTime(9,0)); row.setEndTime(day.atTime(9,1));
        row.setVideoUrl1(verifiedUrl(row,1)); row.setVideoUrl2(verifiedUrl(row,2)); return row;
    }

    private void stubFairQueue(List<WaybillVideoRecord> pending) {
        when(repository.findCurrentDayPending(any(),any(),org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv->{
            LocalDateTime from=inv.getArgument(0), to=inv.getArgument(1); int limit=inv.getArgument(2);
            return pending.stream().filter(r->!r.getStartTime().isBefore(from)&&r.getStartTime().isBefore(to)).limit(limit).toList();
        });
        when(repository.findHistoricalPending(any(),org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv->{
            LocalDateTime from=inv.getArgument(0); int limit=inv.getArgument(1);
            return pending.stream().filter(r->r.getStartTime().isBefore(from)).limit(limit).toList();
        });
    }

    @Test
    void boundaryGuardDeviceIsExplicitOptInAndTrimsConfiguration() {
        assertEquals("", properties.getWaybillVideo().getBoundaryGuardDevice());
        properties.getWaybillVideo().setBoundaryGuardDevice(" DLWH1xx ");
        assertEquals("DLWH1xx", properties.getWaybillVideo().getBoundaryGuardDevice());
        properties.getWaybillVideo().setBoundaryGuardDevice(null);
        assertEquals("", properties.getWaybillVideo().getBoundaryGuardDevice());
    }

    @Test
    void boundaryGuardMissingForOptedDeviceFailsBeforeNativeDownload() {
        properties.getWaybillVideo().setBoundaryGuardDevice("DLWH1xx");
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        verify(repository, never()).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    void boundaryGuardAdjustsBothChannelsBeforeDownloadAndKeepsBusinessInterval() throws Exception {
        properties.getWaybillVideo().setBoundaryGuardDevice(" DLWH1xx ");
        RecordingBoundaryGuard guard = org.mockito.Mockito.mock(RecordingBoundaryGuard.class);
        service.setRecordingBoundaryGuard(guard);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        LocalDateTime businessStart = row.getStartTime();
        LocalDateTime businessEnd = row.getEndTime();
        LocalDateTime paddedStart = businessStart.minusSeconds(5);
        LocalDateTime paddedEnd = businessEnd.plusSeconds(15);
        LocalDateTime safeEnd = paddedEnd.plusSeconds(25);
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownloadAndUpload();
        when(guard.safeEnd(eq("DLWH1xx"), org.mockito.ArgumentMatchers.anyInt(),
                eq(paddedStart), eq(paddedEnd), anyLong())).thenReturn(safeEnd);

        LegacyVideoSyncTestSupport.syncOnce(service);

        InOrder order = inOrder(guard, downloadService);
        ArgumentCaptor<Long> deadlines = ArgumentCaptor.forClass(Long.class);
        for (int channel : List.of(1, 2)) {
            order.verify(guard).safeEnd(eq("DLWH1xx"), eq(channel), eq(paddedStart), eq(paddedEnd), deadlines.capture());
            order.verify(downloadService).startPackingDownload(org.mockito.ArgumentMatchers.argThat(request ->
                    request.getChannel() == channel
                            && com.hiki.isup.util.TimeRange.parse(request.getStartTime(), "start").equals(paddedStart)
                            && com.hiki.isup.util.TimeRange.parse(request.getEndTime(), "end").equals(safeEnd)),
                    eq(deadlines.getValue()));
        }
        assertEquals(deadlines.getAllValues().get(0), deadlines.getAllValues().get(1));
        verify(recordingRemuxer, times(2)).remux(any(), eq(businessStart), eq(businessEnd), eq(deadlines.getValue()));
        assertEquals(businessStart, row.getStartTime());
        assertEquals(businessEnd, row.getEndTime());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "another-device", "DLWH1"})
    void boundaryGuardDoesNotAffectDisabledOrNonmatchingDevices(String configuredDevice) throws Exception {
        properties.getWaybillVideo().setBoundaryGuardDevice(configuredDevice);
        RecordingBoundaryGuard guard = org.mockito.Mockito.mock(RecordingBoundaryGuard.class);
        service.setRecordingBoundaryGuard(guard);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownloadAndUpload();

        LegacyVideoSyncTestSupport.syncOnce(service);

        org.mockito.Mockito.verifyNoInteractions(guard);
        ArgumentCaptor<DownloadRequest> requests = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(downloadService, times(2)).startPackingDownload(requests.capture(), anyLong());
        for (DownloadRequest request : requests.getAllValues()) {
            assertEquals(row.getEndTime().plusSeconds(15),
                    com.hiki.isup.util.TimeRange.parse(request.getEndTime(), "end"));
        }
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    void boundaryGuardMetadataFailurePreventsNativeDownloadAndSuccess() throws Exception {
        properties.getWaybillVideo().setBoundaryGuardDevice("DLWH1xx");
        RecordingBoundaryGuard guard = org.mockito.Mockito.mock(RecordingBoundaryGuard.class);
        service.setRecordingBoundaryGuard(guard);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(guard.safeEnd(eq("DLWH1xx"), eq(1), any(), any(), anyLong()))
                .thenThrow(new java.io.IOException("recording index unavailable"));

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(recordingRemuxer, never()).remux(any(), any(), any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        verify(repository, never()).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void boundaryGuardValidatesOriginalAndAdjustedDownloadRanges(boolean invalidOriginal) throws Exception {
        properties.getWaybillVideo().setBoundaryGuardDevice("DLWH1xx");
        RecordingBoundaryGuard guard = org.mockito.Mockito.mock(RecordingBoundaryGuard.class);
        service.setRecordingBoundaryGuard(guard);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        if (invalidOriginal) {
            row.setEndTime(row.getStartTime().plusHours(24));
        } else {
            when(guard.safeEnd(eq("DLWH1xx"), eq(1), any(), any(), anyLong()))
                    .thenReturn(row.getStartTime().plusHours(24));
        }
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        if (invalidOriginal) org.mockito.Mockito.verifyNoInteractions(guard);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    void priorityCandidatesPrecedeNormalBatchWithoutDroppingOrDuplicatingNormalRows() {
        WaybillVideoRecord priority = pendingRow("priority-1");
        WaybillVideoRecord normal = pendingRow("normal-1");
        properties.getWaybillVideo().setPriorityIds(List.of(priority.getId()));
        lenient().when(repository.findPriorityPending(List.of(priority.getId()))).thenReturn(List.of(priority));
        when(repository.findPending(5)).thenReturn(List.of(normal, priority));

        List<WaybillVideoRecord> rows = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "samplePending");

        assertEquals(List.of(priority, normal), rows);
        verify(repository, never()).claimSnapshot(any());
    }

    @Test
    void absentPriorityCandidatesFallBackToOriginalPendingOrdering() {
        WaybillVideoRecord first = pendingRow("normal-1");
        WaybillVideoRecord second = pendingRow("normal-2");
        properties.getWaybillVideo().setPriorityIds(List.of("completed-id"));
        lenient().when(repository.findPriorityPending(List.of("completed-id"))).thenReturn(List.of());
        when(repository.findPending(5)).thenReturn(List.of(first, second));
        assertEquals(List.of(first, second),
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "samplePending"));
    }

    @Test
    void defaultSelectionDoesNotQueryPriorityRows() {
        WaybillVideoRecord row = pendingRow();
        when(repository.findPending(5)).thenReturn(List.of(row));
        assertEquals(List.of(row),
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "samplePending"));
        verify(repository, never()).findPriorityPending(any());
    }

    @Test
    void priorityRetryCooldownStillLetsNormalRowCompleteThroughExistingCas() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord priority = pendingRow("priority-cooldown");
        WaybillVideoRecord normal = pendingRow("normal-ready");
        normal.setVideoUrl1(verifiedUrl(normal, 1));
        normal.setVideoUrl2(verifiedUrl(normal, 2));
        properties.getWaybillVideo().setPriorityIds(List.of(priority.getId()));
        var policy = new RecordingRetryPolicy(tempDir.resolve("priority-retries.properties"),
                3, 100, 10000, 20000, 86400000, () -> 1000L);
        assertTrue(policy.beginAttempt(priority.getId(), retryFingerprint(priority)));
        policy.failed(priority.getId(), false, "temporary failure");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        lenient().when(repository.findPriorityPending(List.of(priority.getId()))).thenReturn(List.of(priority));
        nextPending(normal);
        lenient().when(repository.claim(priority.getId())).thenReturn(true);
        when(repository.claim(normal.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).claimSnapshot(priority);
        verify(repository).claimSnapshot(normal);
        verify(repository).updateStatusSnapshot(normal, WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        assertEquals(1, policy.inspect(priority.getId()).attempts());
    }

    @Test
    void copiedSegmentsUseDistinctObjectVersion() {
        String key = WaybillVideoSyncService.buildRecordingObjectKey("waybill-video/", pendingRow(),
                CameraChannel.PANORAMA, 1);
        assertTrue(key.startsWith("waybill-video/packing-segment-v1/"));
        assertFalse(key.contains("packing-window-v4/"));
    }

    @Test
    void existingV4AndCopiedSegmentUrlsCanFinalizeTogether() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        String panorama = legacyV4Url(row, 1);
        String detail = segmentUrl(row, 2);
        row.setVideoUrl1(panorama);
        row.setVideoUrl2(detail);
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        assertEquals(panorama, row.getVideoUrl1());
        assertEquals(detail, row.getVideoUrl2());
    }

    @Test
    void bothExistingV4UrlsRemainValid() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(legacyV4Url(row, 1));
        row.setVideoUrl2(legacyV4Url(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
    }

    @Test
    void missingChannelUsesNewKeyWhileExistingV4ChannelIsPreserved() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        String panorama = legacyV4Url(row, 1);
        row.setVideoUrl1(panorama);
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubOneChannel(2, Files.write(tempDir.resolve("detail-segment.mp4"), new byte[]{1}), segmentUrl(row, 2));

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, times(1)).startPackingDownload(any(), anyLong());
        verify(ossService).upload(any(), eq(segmentUrl(row, 2).substring("https://oss/".length())), anyLong());
        verify(repository, never()).updateUrl(eq(row.getId()), eq(CameraChannel.PANORAMA), anyString());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        assertEquals(panorama, row.getVideoUrl1());
        assertEquals(segmentUrl(row, 2), row.getVideoUrl2());
    }

    @Test
    void outputVersionAndValidMixedUrlsKeepExistingRetryFingerprintAndIsolation() throws Exception {
        WaybillVideoRecord row = pendingRow();
        var policy = new RecordingRetryPolicy(tempDir.resolve("legacy-isolation.properties"),
                3, 100, 1, 10, 86400000, () -> 1000L);
        assertTrue(policy.beginAttempt(row.getId(), LEGACY_RETRY_FINGERPRINT));
        policy.failed(row.getId(), true, "existing isolation");
        var before = policy.inspect(row.getId());
        assertEquals(LEGACY_RETRY_FINGERPRINT, retryFingerprint(row));
        row.setVideoUrl1(legacyV4Url(row, 1));
        row.setVideoUrl2(segmentUrl(row, 2));

        String currentFingerprint = retryFingerprint(row);

        assertEquals(LEGACY_RETRY_FINGERPRINT, currentFingerprint);
        assertFalse(policy.canAttempt(row.getId(), currentFingerprint));
        assertEquals(before, policy.inspect(row.getId()));
    }

    @Test
    void existingRecoveryGrantIsConsumedWithoutResettingAttempts() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(legacyV4Url(row, 1));
        Path ledger = tempDir.resolve("legacy-grant.properties");
        var policy = new RecordingRetryPolicy(ledger, 3, 100, 1, 10, 86400000, () -> 1000L);
        assertTrue(policy.beginAttempt(row.getId(), LEGACY_RETRY_FINGERPRINT));
        policy.failed(row.getId(), true, "existing isolation");
        var isolated = policy.inspect(row.getId());
        var grant = policy.release(row.getId(), isolated.revision(), isolated.fingerprint(), "approved recovery");
        policy = new RecordingRetryPolicy(ledger, 3, 100, 1, 10, 86400000, () -> 1000L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownload(2, Files.write(tempDir.resolve("grant-detail.ps"), new byte[]{1}));
        when(recordingRemuxer.remux(any(), any(), any(), anyLong()))
                .thenThrow(new java.io.IOException("remux failed"));

        LegacyVideoSyncTestSupport.syncOnce(service);

        var after = policy.inspect(row.getId());
        assertEquals(LEGACY_RETRY_FINGERPRINT, after.fingerprint());
        assertEquals(grant.attempts() + 1, after.attempts());
        assertEquals(grant.totalAttempts() + 1, after.totalAttempts());
        assertEquals(grant.firstAt(), after.firstAt());
        assertEquals(grant.recoveryAudit(), after.recoveryAudit());
        assertTrue(after.quarantined());
        assertFalse(after.recoveryPending());
        verify(downloadService, times(1)).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        assertEquals(legacyV4Url(row, 1), row.getVideoUrl1());
    }

    private String retryFingerprint(WaybillVideoRecord row) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "fingerprint", row,
                List.of(new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
    }

    @Test
    void realRemuxerRecentShortVideoRetriesThenUploadsCompletedVideo() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        AtomicLong durationMicros = new AtomicLong(1_000_000);
        var policy = installRealRemuxer(clock, durationMicros, 3, 86_400_000);
        WaybillVideoRecord row = recentRecordingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(ossService.upload(any(), anyString(), anyLong())).thenReturn(verifiedUrl(row, 1));

        LegacyVideoSyncTestSupport.syncOnce(service);

        assertFalse(policy.inspect(row.getId()).quarantined(), "近期短片须进入有限退避，不能立即永久隔离");
        assertTrue(policy.inspect(row.getId()).reason().contains("RecordingDurationShortageException"));
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        nextPending(row);
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(downloadService, times(1)).startPackingDownload(any(), anyLong());

        clock.addAndGet(1_000);
        durationMicros.set(60_000_000);
        nextPending(row);
        LegacyVideoSyncTestSupport.syncOnce(service);

        var requests = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(downloadService, times(2)).startPackingDownload(requests.capture(), anyLong());
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        assertEquals(row.getEndTime().plusSeconds(15).format(formatter), requests.getAllValues().get(0).getEndTime());
        assertEquals(row.getEndTime().plusSeconds(25).format(formatter), requests.getAllValues().get(1).getEndTime());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(ossService).upload(any(), anyString(), anyLong());
        assertNull(policy.inspect(row.getId()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1})
    void realRemuxerShortRetryRemainsBoundedByAttemptsAndAge(int ageLimit) throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        var policy = installRealRemuxer(clock, new AtomicLong(1_000_000), ageLimit == 0 ? 2 : 3,
                ageLimit == 0 ? 86_400_000 : 500);
        WaybillVideoRecord row = recentRecordingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        assertFalse(policy.inspect(row.getId()).quarantined());
        clock.addAndGet(1_000);
        nextPending(row);
        LegacyVideoSyncTestSupport.syncOnce(service);
        nextPending(row);
        LegacyVideoSyncTestSupport.syncOnce(service);

        assertTrue(policy.inspect(row.getId()).quarantined());
        assertTrue(policy.inspect(row.getId()).reason().contains(ageLimit == 0 ? "次数耗尽" : "超过重试年龄"));
        verify(downloadService, times(ageLimit == 0 ? 2 : 1)).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {601, -30})
    void realRemuxerOldOrFutureShortVideoIsPermanent(int endAgeSeconds) throws Exception {
        var policy = installRealRemuxer(new AtomicLong(System.currentTimeMillis()), new AtomicLong(1_000_000),
                3, 86_400_000);
        WaybillVideoRecord row = recentRecordingRow();
        row.setEndTime(LocalDateTime.now().minusSeconds(endAgeSeconds).withNano(0));
        row.setStartTime(row.getEndTime().minusSeconds(40));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        assertTrue(policy.inspect(row.getId()).quarantined());
        assertTrue(policy.inspect(row.getId()).reason().contains("永久失败"));
        verify(ossService, never()).upload(any(), anyString(), anyLong());
    }

    @Test
    void realRemuxerExcessiveDurationIsPermanentEvenForRecentVideo() throws Exception {
        var policy = installRealRemuxer(new AtomicLong(System.currentTimeMillis()), new AtomicLong(100_000_000),
                3, 86_400_000);
        WaybillVideoRecord row = recentRecordingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        assertTrue(policy.inspect(row.getId()).quarantined());
        assertTrue(policy.inspect(row.getId()).reason().contains("永久失败"));
        verify(ossService, never()).upload(any(), anyString(), anyLong());
    }

    @Test
    void realRemuxerShortRetryUsesFirstAttemptAfterBackoffAndLedgerReload() throws Exception {
        WaybillVideoRecord row = recentRecordingRow();
        row.setEndTime(row.getEndTime().minusMinutes(32));
        row.setStartTime(row.getEndTime().minusSeconds(40));
        AtomicLong clock = new AtomicLong(row.getEndTime().plusSeconds(30)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        var policy = installRealRemuxer(clock, new AtomicLong(1_000_000), 3, 86_400_000);
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        assertFalse(policy.inspect(row.getId()).quarantined());
        long firstAt = policy.inspect(row.getId()).firstAt();
        clock.set(System.currentTimeMillis());
        var reloaded = new RecordingRetryPolicy(tempDir.resolve("real-remux-retry.properties"), 3, 100,
                100, 1000, 86_400_000, clock::get);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", reloaded);
        nextPending(row);

        LegacyVideoSyncTestSupport.syncOnce(service);

        assertEquals(firstAt, reloaded.inspect(row.getId()).firstAt());
        assertEquals(2, reloaded.inspect(row.getId()).attempts());
        assertFalse(reloaded.inspect(row.getId()).quarantined());
        verify(downloadService, times(2)).startPackingDownload(any(), anyLong());
    }

    private WaybillVideoRecord recentRecordingRow() {
        WaybillVideoRecord row = pendingRow();
        row.setEndTime(LocalDateTime.now().minusSeconds(30).withNano(0));
        row.setStartTime(row.getEndTime().minusSeconds(40));
        return row;
    }

    private RecordingRetryPolicy installRealRemuxer(AtomicLong clock, AtomicLong durationMicros,
                                                   int maxAttempts, long maxAgeMillis) throws Exception {
        service.close();
        properties.getWaybillVideo().setChannels(List.of(1));
        var remuxer = new RecordingRemuxer(properties, (command, deadline, parent) -> {
            if (!command.contains("-progress")) {
                assertEquals("copy", command.get(command.indexOf("-c:v") + 1));
                assertTrue(command.contains("-an"));
                Files.write(Path.of(command.getLast()), new byte[]{7, 8, 9});
                return "";
            }
            assertFalse(command.contains("-c:v"));
            assertTrue(command.contains("-xerror"));
            assertEquals("passthrough", command.get(command.indexOf("-fps_mode") + 1));
            assertEquals("demux", command.get(command.indexOf("-enc_time_base") + 1));
            return "out_time_us=" + durationMicros.get() + "\nprogress=end\n";
        });
        service = new WaybillVideoSyncService(properties, downloadService, sessionManager,
                ossService, repository, channelService, deviceRegistry, remuxer);
        var policy = new RecordingRetryPolicy(tempDir.resolve("real-remux-retry.properties"), maxAttempts, 100,
                100, 1000, maxAgeMillis, clock::get);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        when(ossService.isConfigured()).thenReturn(true);
        AtomicReference<DownloadRequest> request = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        when(downloadService.startPackingDownload(any(), anyLong())).thenAnswer(inv -> {
            request.set(inv.getArgument(0));
            return new DownloadTaskResponse("real-remux-" + calls.incrementAndGet(), SessionStatus.STARTING);
        });
        when(sessionManager.awaitFinished(anyString(), anyLong())).thenAnswer(inv -> {
            Path folder = Files.createDirectory(tempDir.resolve(inv.<String>getArgument(0)));
            Path raw = Files.write(folder.resolve("source.ps"), new byte[]{1, 2, 3});
            MediaSession session = new MediaSession(inv.getArgument(0), SessionType.DOWNLOAD, "DLWH1xx", 1);
            session.setStatus(SessionStatus.COMPLETED);
            session.setRawFilePath(raw.toString());
            var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            session.setStartTime(LocalDateTime.parse(request.get().getStartTime(), formatter));
            session.setEndTime(LocalDateTime.parse(request.get().getEndTime(), formatter));
            return session;
        });
        return policy;
    }

    @Test
    void recentTailRetryEligibilitySurvivesBackoffAndLedgerReload() throws Exception {
        WaybillVideoRecord row = pendingRow();
        row.setEndTime(LocalDateTime.now().minusMinutes(32));
        row.setStartTime(row.getEndTime().minusMinutes(1));
        long firstAttempt = row.getEndTime().plusSeconds(30)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        Path ledger = tempDir.resolve("tail-retry.properties");
        var policy = new RecordingRetryPolicy(ledger, 3, 100, 1, 10, 86400000, () -> firstAttempt);
        assertTrue(policy.beginAttempt(row.getId(), "same-window"));
        policy.failed(row.getId(), false, "近期纯缺尾");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy",
                new RecordingRetryPolicy(ledger, 3, 100, 1, 10, 86400000, System::currentTimeMillis));
        var missingTail = new RecordingCoverageException(row.getStartTime(), row.getEndTime(),
                row.getStartTime().minusSeconds(5), row.getEndTime().minusNanos(1_059_000_000));

        assertEquals(false, org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                "isPermanentFailure", missingTail, row));
    }

    @Test
    void retryFetchesMoreTailButKeepsExactBusinessWindow() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        var policy = new RecordingRetryPolicy(tempDir.resolve("retry-tail.properties"), 3, 100,
                100, 1000, 86400000, clock::get);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        when(ossService.isConfigured()).thenReturn(true);
        properties.getWaybillVideo().setChannels(List.of(1));
        WaybillVideoRecord row = pendingRow();
        row.setEndTime(LocalDateTime.now().minusSeconds(30).withNano(0));
        row.setStartTime(row.getEndTime().minusSeconds(40));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        Path raw = Files.write(tempDir.resolve("tail-source.ps"), new byte[]{1, 2, 3});
        when(downloadService.startPackingDownload(any(), anyLong())).thenReturn(
                new DownloadTaskResponse("tail-first", SessionStatus.STARTING),
                new DownloadTaskResponse("tail-second", SessionStatus.STARTING));
        when(sessionManager.awaitFinished(anyString(), anyLong())).thenAnswer(inv -> {
            MediaSession session = new MediaSession(inv.getArgument(0), SessionType.DOWNLOAD, "DLWH1xx", 1);
            session.setStatus(SessionStatus.COMPLETED);
            session.setRawFilePath(raw.toString());
            session.setFilePath(raw.toString());
            return session;
        });
        when(recordingRemuxer.remux(any(), eq(row.getStartTime()), eq(row.getEndTime()), anyLong()))
                .thenThrow(new RecordingCoverageException(row.getStartTime(), row.getEndTime(),
                        row.getStartTime().minusSeconds(5), row.getEndTime().minusSeconds(1)))
                .thenReturn(raw);
        when(ossService.upload(any(), anyString(), anyLong())).thenReturn(verifiedUrl(row, 1));
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        clock.addAndGet(1000);
        nextPending(row);
        LegacyVideoSyncTestSupport.syncOnce(service);

        var requests = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(downloadService, times(2)).startPackingDownload(requests.capture(), anyLong());
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        assertEquals(row.getEndTime().plusSeconds(15).format(formatter), requests.getAllValues().get(0).getEndTime());
        assertEquals(row.getEndTime().plusSeconds(25).format(formatter), requests.getAllValues().get(1).getEndTime());
        for (DownloadRequest request : requests.getAllValues()) {
            assertEquals(row.getStartTime().minusSeconds(5).format(formatter), request.getStartTime());
        }
        verify(recordingRemuxer, times(2)).remux(any(), eq(row.getStartTime()), eq(row.getEndTime()), anyLong());
        verify(ossService).upload(any(), eq(verifiedUrl(row, 1).substring("https://oss/".length())), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    void validationFailurePreservesBoundedEvidenceBeforeSessionCleanup() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        Path raw = Files.write(tempDir.resolve("failed-source.ps"), new byte[]{1, 2, 3});
        stubDownload(1, raw);
        when(recordingRemuxer.remux(any(), any(), any(), anyLong()))
                .thenThrow(new RecordingValidationException("原始 PS 截断，PS offset=3"));
        org.mockito.Mockito.doAnswer(inv -> {
            assertTrue(Files.isRegularFile(tempDir.resolve("recording-failures/structure.ps")));
            return null;
        }).when(sessionManager).discardDownload("task-1");

        LegacyVideoSyncTestSupport.syncOnce(service);

        Path evidence = tempDir.resolve("recording-failures/structure.ps");
        assertTrue(Files.isRegularFile(evidence), "校验失败必须在清理前保留有界原始样本");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(evidence));
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    void evidenceStorageFailureCannotReplaceOriginalFailureOrPreventCleanup() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownload(1, Files.write(tempDir.resolve("raw-error.ps"), new byte[]{1, 2, 3}));
        Files.writeString(tempDir.resolve("recording-failures"), "unavailable evidence directory");
        when(recordingRemuxer.remux(any(), any(), any(), anyLong()))
                .thenThrow(new RecordingValidationException("原始 PS 时间戳不可信"));

        assertDoesNotThrow(() -> LegacyVideoSyncTestSupport.syncOnce(service));

        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(sessionManager).discardDownload("task-1");
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        assertTrue(((RecordingRetryPolicy.Snapshot) service.inspectRetry(row.getId())).reason()
                .contains("原始 PS 时间戳不可信"));
    }

    @Test
    void repeatedRecoveryCannotGrowDownloadTailWithoutBound() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        var policy = new RecordingRetryPolicy(tempDir.resolve("padding-cap.properties"), 10, 100,
                1, 1, 86400000, clock::get);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        WaybillVideoRecord row = pendingRow();
        for (int i = 0; i < 8; i++) {
            assertTrue(policy.beginAttempt(row.getId(), "same-business-window"));
            clock.incrementAndGet();
        }
        LocalDateTime end = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row);
        assertEquals(row.getEndTime().plusSeconds(35), end);
    }

    @Test
    void firstDownloadAndRetryTailUseFifteenTwentyFiveThenThirtyFiveSeconds() throws Exception {
        WaybillVideoRecord row = pendingRow();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", null);
        assertEquals(row.getEndTime().plusSeconds(15),
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row));
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        var policy = new RecordingRetryPolicy(tempDir.resolve("padding-first.properties"), 10, 100,
                1, 1, 86400000, clock::get);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        assertEquals(row.getEndTime().plusSeconds(15),
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row));
        for (int seconds : new int[]{15, 25, 35, 35}) {
            assertTrue(policy.beginAttempt(row.getId(), "same-business-window"));
            assertEquals(row.getEndTime().plusSeconds(seconds),
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row));
            assertEquals(row.getStartTime().minusSeconds(5),
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedStart", row));
            clock.incrementAndGet();
        }
    }

    @Test
    void firstDownloadCapsExtraTailWithoutMakingAnOriginallyInvalidRequestValid() throws Exception {
        WaybillVideoRecord row = pendingRow();
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        // 原请求距 24 小时上限尚余 10 秒时，首轮额外尾部恰好填满。
        row.setEndTime(row.getStartTime().plusHours(24).minusSeconds(20));
        LocalDateTime end = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row);
        assertEquals(row.getEndTime().plusSeconds(15), end);
        assertDoesNotThrow(() -> com.hiki.isup.util.TimeRange.validate(
                row.getStartTime().minusSeconds(5).format(formatter), end.format(formatter)));
        // 旧的 start-5 / end+5 已超过 24 小时，扩尾上限不得把非法请求变合法。
        row.setEndTime(row.getStartTime().plusHours(24).minusSeconds(9));
        LocalDateTime invalidEnd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row);
        assertEquals(row.getEndTime().plusSeconds(5), invalidEnd);
        org.junit.jupiter.api.Assertions.assertThrows(com.hiki.isup.exception.BadRequestException.class,
                () -> com.hiki.isup.util.TimeRange.validate(
                        row.getStartTime().minusSeconds(5).format(formatter), invalidEnd.format(formatter)));
    }

    @Test
    void retryTailKeepsAnInitiallyValidMaximumRangeValid() throws Exception {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        var policy = new RecordingRetryPolicy(tempDir.resolve("padding-range.properties"), 3, 100,
                1, 1, 86400000, clock::get);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        WaybillVideoRecord row = pendingRow();
        row.setEndTime(row.getStartTime().plusHours(24).minusSeconds(10));
        assertTrue(policy.beginAttempt(row.getId(), "same-business-window"));
        LocalDateTime firstEnd = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row);
        assertEquals(row.getEndTime().plusSeconds(5), firstEnd);
        clock.incrementAndGet();
        assertTrue(policy.beginAttempt(row.getId(), "same-business-window"));

        LocalDateTime end = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "paddedEnd", row);
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        assertDoesNotThrow(() -> com.hiki.isup.util.TimeRange.validate(
                row.getStartTime().minusSeconds(5).format(formatter), end.format(formatter)));
        assertEquals(row.getEndTime().plusSeconds(5), end);
    }

    @Test
    void onlyRecentPureTailCoverageShortageMayRetry() {
        WaybillVideoRecord row = pendingRow();
        row.setEndTime(LocalDateTime.now().minusSeconds(20));
        row.setStartTime(row.getEndTime().minusMinutes(1));
        var missingTail = new RecordingCoverageException(row.getStartTime(), row.getEndTime(),
                row.getStartTime().minusSeconds(5), row.getEndTime().minusSeconds(2));
        assertEquals(false, org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                "isPermanentFailure", missingTail, row));
        row.setEndTime(LocalDateTime.now().minusDays(1));
        row.setStartTime(row.getEndTime().minusMinutes(1));
        var historicalGap = new RecordingCoverageException(row.getStartTime(), row.getEndTime(),
                row.getStartTime().minusSeconds(5), row.getEndTime().minusSeconds(2));
        assertEquals(true, org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                "isPermanentFailure", historicalGap, row));
        var missingHead = new RecordingCoverageException(row.getStartTime(), row.getEndTime(),
                row.getStartTime().plusSeconds(2), row.getEndTime().plusSeconds(5));
        assertEquals(true, org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,
                "isPermanentFailure", missingHead, row));
    }

    @Test
    void globalResourceBlockDoesNotClaimOrConsumeOrderAttempt() {
        when(sessionManager.recordingAdmissionBlocked()).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        org.mockito.Mockito.verifyNoInteractions(repository, downloadService, ossService);
        assertFalse(Files.exists(tempDir.resolve("waybill-video-retries.properties")));
    }

    @Test
    void claimMustUseLoadedBusinessSnapshotInsteadOfIdAlone() {
        org.mockito.Mockito.doReturn(false).when(repository).claimSnapshot(any());
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(repository, never()).claim(anyString());
        assertTrue(org.mockito.Mockito.mockingDetails(repository).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().equals("claimSnapshot")
                        && call.getArgument(0) == row));
    }

    @Test
    void staleUrlSnapshotStopsAllFurtherWritesAndUploads() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        org.mockito.Mockito.doReturn(true).when(repository).claimSnapshot(row);
        stubDownload(1, Files.write(tempDir.resolve("first.mp4"), new byte[]{1}));
        stubDownload(2, Files.write(tempDir.resolve("second.mp4"), new byte[]{2}));
        when(ossService.upload(any(), anyString(), anyLong())).thenReturn("https://oss/first.mp4");
        // 领取后订单被修改，第一次 URL CAS 已不能匹配。
        org.mockito.Mockito.doReturn(false).when(repository).updateUrlSnapshot(any(), any(), any());
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(ossService, times(1)).upload(any(), anyString(), anyLong());
        verify(repository, never()).updateStatusSnapshot(any(), any());
        assertNull(row.getVideoUrl1());
        assertNull(row.getVideoUrl2());
        verify(sessionManager).discardDownload("task-1");
        verify(sessionManager).discardDownload("task-2");
    }

    @Test
    void eachUrlAndFinalStatusUsesPreviouslyPersistedSnapshot() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        org.mockito.Mockito.doReturn(true).when(repository).claimSnapshot(row);
        stubDownloadAndUpload();
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            WaybillVideoRecord actual = inv.getArgument(0);
            assertTrue(actual == row);
            if (calls.getAndIncrement() == 0) assertNull(actual.getVideoUrl1());
            else assertEquals("https://oss/packing-window-v4/ch1.mp4", actual.getVideoUrl1());
            assertNull(actual.getVideoUrl2());
            return true;
        }).when(repository).updateUrlSnapshot(any(), any(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            assertEquals("https://oss/packing-window-v4/ch1.mp4", row.getVideoUrl1());
            assertEquals("https://oss/packing-window-v4/ch2.mp4", row.getVideoUrl2());
            assertEquals(WaybillVideoStatus.SUCCESS, inv.getArgument(1));
            return true;
        }).when(repository).updateStatusSnapshot(any(), any());
        LegacyVideoSyncTestSupport.syncOnce(service);
        assertEquals(2, calls.get());
        verify(repository).updateStatusSnapshot(row, WaybillVideoStatus.SUCCESS);
        verify(repository, never()).claim(anyString());
        verify(repository, never()).updateUrl(anyString(), any(), anyString());
        verify(repository, never()).updateStatus(anyString(), any());
        assertTrue(service.diagnostics().get("lastSuccessAt") != null);
    }

    @Test
    void recoveryChecksFailedStateAndRevisionThenOnlyPersistsOnePermit() throws Exception {
        RecordingRetryPolicy policy = new RecordingRetryPolicy(tempDir.resolve("recovery.properties"),
                3, 5, 10, 100, 1000, System::currentTimeMillis);
        policy.beginAttempt("id", "fp");
        policy.failed("id", true, "真实缺帧");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy", policy);
        var before = policy.inspect("id");
        WaybillVideoRecord row = pendingRow("id");
        when(repository.findById("id")).thenReturn(row);
        org.junit.jupiter.api.Assertions.assertThrows(WaybillVideoSyncService.RecoveryConflictException.class,
                () -> LegacyVideoSyncTestSupport.releaseRetry(service, "id", before.revision(), "fp", "确认录像已经补齐"));
        row.setStatus("FAILED");
        org.junit.jupiter.api.Assertions.assertThrows(WaybillVideoSyncService.RecoveryConflictException.class,
                () -> LegacyVideoSyncTestSupport.releaseRetry(service, "id", "stale", "fp", "确认录像已经补齐"));
        LegacyVideoSyncTestSupport.releaseRetry(service, "id", before.revision(), "fp", "确认录像已经补齐");
        assertTrue(policy.inspect("id").recoveryPending());
        assertEquals(1, policy.inspect("id").recoveryAudit().size());
        assertEquals(1, policy.inspect("id").totalAttempts());
        org.junit.jupiter.api.Assertions.assertThrows(WaybillVideoSyncService.RecoveryConflictException.class,
                () -> LegacyVideoSyncTestSupport.releaseRetry(service, "id", before.revision(), "fp", "重复请求"));
        verify(repository, never()).updateStatusSnapshot(any(), any());
        org.mockito.Mockito.verifyNoInteractions(downloadService, ossService, channelService);
    }

    @Test
    void recoveryWhileSyncRunningNeverReadsOrWritesOrder() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "running", true);
        org.junit.jupiter.api.Assertions.assertThrows(WaybillVideoSyncService.RecoveryConflictException.class,
                () -> LegacyVideoSyncTestSupport.releaseRetry(service, "id", "rev", "fp", "原因"));
        org.mockito.Mockito.verifyNoInteractions(repository, downloadService);
    }

    @Test
    void diagnosticReadBeforeInitializationHasNoDatabaseOrLedgerWrites() {
        var diagnostics = service.diagnostics();
        assertEquals(false, ((java.util.Map<?, ?>) diagnostics.get("retry")).get("initialized"));
        assertEquals(false, ((WaybillVideoSyncService.QueueSample) diagnostics.get("queue")).sampled());
        assertFalse(Files.exists(tempDir.resolve("waybill-video-retries.properties")));
        org.mockito.Mockito.verifyNoInteractions(repository, downloadService, ossService, deviceRegistry, channelService);
    }

    @Mock
    private DownloadService downloadService;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private OssService ossService;
    @Mock
    private WaybillVideoRepository repository;
    @Mock
    private ChannelService channelService;
    @Mock
    private DeviceRegistry deviceRegistry;
    @Mock
    private RecordingRemuxer recordingRemuxer;

    private IsupProperties properties;
    private WaybillVideoSyncService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        properties = new IsupProperties();
        properties.setMediaDir(tempDir.toString());
        properties.getWaybillVideo().setBatchSize(5);
        properties.getWaybillVideo().setChannels(List.of(1, 2));
        properties.getWaybillVideo().setPollIntervalMs(0);
        properties.getWaybillVideo().setMaxRunSeconds(0);
        properties.getOss().setPrefix("waybill-video/");
        service = new WaybillVideoSyncService(properties, downloadService, sessionManager,
                ossService, repository, channelService, deviceRegistry, recordingRemuxer);
        // 旧流程回归沿用既有 ID 桩；快照 API 的 SQL 绑定另有 Repository 契约测试。
        lenient().when(repository.claimSnapshot(any())).thenAnswer(inv ->
                repository.claim(((WaybillVideoRecord) inv.getArgument(0)).getId()));
        lenient().when(repository.updateUrlSnapshot(any(), any(), any())).thenAnswer(inv ->
                repository.updateUrl(((WaybillVideoRecord) inv.getArgument(0)).getId(), inv.getArgument(1), inv.getArgument(2)));
        lenient().when(repository.updateStatusSnapshot(any(), any())).thenAnswer(inv ->
                repository.updateStatus(((WaybillVideoRecord) inv.getArgument(0)).getId(), inv.getArgument(1)));
        lenient().when(recordingRemuxer.remux(any(MediaSession.class), any(LocalDateTime.class), any(LocalDateTime.class), anyLong()))
                .thenAnswer(inv -> Path.of(((MediaSession) inv.getArgument(0)).getFilePath()));
        lenient().when(repository.updateUrl(any(), any(), any())).thenReturn(true);
        lenient().when(repository.updateStatus(any(), any())).thenReturn(true);
        // 主代码走 onlineDeviceIds()（只要在线设备的 ID），不是 list()：
        // 桩错方法会让 onlineDeviceId() 返回 null，整条同步链路空转。
        DeviceInfo device = new DeviceInfo("DLWH1xx", 1, "192.168.22.210");
        lenient().when(deviceRegistry.onlineDeviceIds()).thenReturn(List.of(device.getDeviceId()));
        lenient().when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"),
                new DeviceChannel(2, "打包台1细节")));
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void missingDetailPreventsDownloadingPanorama() {
        when(ossService.isConfigured()).thenReturn(true);
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(new DeviceChannel(1, "打包台1全景")));
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    void correctedDeviceChannelMappingReleasesQuarantinedRow() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(new DeviceChannel(1, "打包台1全景")));
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());

        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(1, "打包台1全景"), new DeviceChannel(2, "打包台1细节")));
        nextPending(row);
        stubDownloadAndUpload();
        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, times(2)).startPackingDownload(any(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    void uploadsOnlyPreparedRemuxFile() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownloadAndUpload();
        Path exact = Files.write(tempDir.resolve("business-only.mp4"), new byte[]{8});
        when(recordingRemuxer.remux(any(MediaSession.class), eq(row.getStartTime()), eq(row.getEndTime()), anyLong()))
                .thenReturn(exact);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(recordingRemuxer, times(2)).remux(any(MediaSession.class), eq(row.getStartTime()), eq(row.getEndTime()), anyLong());
        verify(ossService, times(2)).upload(eq(exact), anyString(), anyLong());
        verify(ossService, times(2)).upload(eq(exact), contains("/packing-segment-v1/"), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    void completedPackingDownloadNeedsRawPsButNotIntermediateMp4() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl2(verifiedUrl(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        Path raw = Files.write(tempDir.resolve("packing.ps"), new byte[]{1});
        Path exact = Files.write(tempDir.resolve("packing.exact.mp4"), new byte[]{2});
        MediaSession session = new MediaSession("raw-only", SessionType.DOWNLOAD, "DLWH1xx", 1);
        session.setStatus(SessionStatus.COMPLETED);
        session.setRawFilePath(raw.toString());
        session.setFilePath(tempDir.resolve("nonexistent-intermediate.mp4").toString());
        when(downloadService.startPackingDownload(any(), anyLong())).thenReturn(new DownloadTaskResponse("raw-only", SessionStatus.STARTING));
        when(sessionManager.awaitFinished(eq("raw-only"), anyLong())).thenReturn(session);
        when(recordingRemuxer.remux(eq(session), any(), any(), anyLong())).thenReturn(exact);
        when(ossService.upload(eq(exact), anyString(), anyLong())).thenReturn(verifiedUrl(row, 1));

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(ossService).upload(eq(exact), anyString(), anyLong());
    }

    @Test
    void rowDeadlinePreventsSecondDownloadAfterSlowRemux() throws Exception {
        properties.getWaybillVideo().setRowTimeoutSeconds(1);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownload(1, Files.write(tempDir.resolve("slow-trim.ps"), new byte[]{1}));
        when(recordingRemuxer.remux(any(MediaSession.class), any(), any(), anyLong())).thenAnswer(inv -> {
            Thread.sleep(1100);
            return Path.of(((MediaSession) inv.getArgument(0)).getRawFilePath());
        });

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> LegacyVideoSyncTestSupport.syncOnce(service));

        verify(downloadService, times(1)).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    void legacyPartialRecordingMustNotMixWithNewWindow() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1("https://oss/legacy-full-window.mp4");
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        assertEquals("https://oss/legacy-full-window.mp4", row.getVideoUrl1());
    }

    @Test
    void secondChannelValidationFailurePreventsBothUploads() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownload(1, Files.write(tempDir.resolve("pair-ch1.mp4"), new byte[]{1}));
        stubDownload(2, Files.write(tempDir.resolve("pair-ch2.mp4"), new byte[]{2}));
        when(recordingRemuxer.remux(any(MediaSession.class), any(), any(), anyLong())).thenAnswer(inv -> {
            MediaSession session = inv.getArgument(0);
            if (session.getChannel() == 2) throw new java.io.IOException("细节首帧持续11.51秒，时间不可信");
            return Path.of(session.getFilePath());
        });

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository, never()).updateUrl(anyString(), any(), anyString());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        ArgumentCaptor<MediaSession> sessions = ArgumentCaptor.forClass(MediaSession.class);
        verify(recordingRemuxer, times(2)).remux(sessions.capture(), any(), any(), anyLong());
        for (MediaSession session : sessions.getAllValues()) assertTrue(session.beginFileCleanup());
        verify(sessionManager).discardDownload("task-1");
        verify(sessionManager).discardDownload("task-2");
    }

    @Test
    void twoLegacyUrlsCannotBePromotedToVerifiedSuccess() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1("https://oss/packing-window-v1/old-ch1.mp4");
        row.setVideoUrl2("https://oss/packing-window-v1/old-ch2.mp4");
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository, never()).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    void v2UrlsDoNotSatisfyV3ValidationRules() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1("https://oss/" + OssService.buildObjectKey("waybill-video/packing-window-v2/", row.getId(), 1, row.getStartTime()));
        row.setVideoUrl2("https://oss/" + OssService.buildObjectKey("waybill-video/packing-window-v2/", row.getId(), 2, row.getStartTime()));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    void verifiedUrlsCannotBeReusedAfterSameDayBusinessTimesChange() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        row.setStartTime(row.getStartTime().plusSeconds(1));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        verify(repository, never()).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    void verifiedUrlsCannotBeReusedAfterPhysicalChannelsChange() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(3, "打包台1全景"), new DeviceChannel(4, "打包台1细节")));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        verify(repository, never()).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    void exhaustedAttemptsStillPermitVerifiedUrlOnlyStatusFinalization() {
        properties.getWaybillVideo().setRetryMaxAttempts(1);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.SUCCESS))
                .thenThrow(new IllegalStateException("temporary status write failure")).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        nextPending(row);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository, times(2)).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
    }

    @Test
    void urlsMustBelongToThisRecordAndChannel() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1("https://oss/waybill-video/packing-window-v4/2026/09/20/another_ch1.mp4");
        row.setVideoUrl2("https://oss/waybill-video/packing-window-v4/2026/09/20/another_ch2.mp4");
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        LegacyVideoSyncTestSupport.syncOnce(service);
        verify(repository, never()).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    void bothPreparedFilesRemainRetainedUntilUploadsFinish() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownload(1, Files.write(tempDir.resolve("retained-ch1.mp4"), new byte[]{1}));
        stubDownload(2, Files.write(tempDir.resolve("retained-ch2.mp4"), new byte[]{2}));
        List<MediaSession> prepared = new java.util.ArrayList<>();
        when(recordingRemuxer.remux(any(MediaSession.class), any(), any(), anyLong())).thenAnswer(inv -> {
            MediaSession session = inv.getArgument(0);
            prepared.add(session);
            return Path.of(session.getFilePath());
        });
        when(ossService.upload(any(), anyString(), anyLong())).thenAnswer(inv -> {
            assertEquals(2, prepared.size(), "两路验证通过后才允许上传");
            for (MediaSession session : prepared) assertFalse(session.beginFileCleanup());
            return "https://oss/" + inv.getArgument(1);
        });
        LegacyVideoSyncTestSupport.syncOnce(service);
        for (MediaSession session : prepared) assertTrue(session.beginFileCleanup());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    void uploadRetryReusesOnlyVerifiedUrlAndStableObjectKey() throws Exception {
        AtomicLong retryClock = new AtomicLong(System.currentTimeMillis());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "retryPolicy",
                new RecordingRetryPolicy(tempDir.resolve("retry-test.properties"), 3, 100,
                        100, 1000, 100000, retryClock::get));
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownload(1, Files.write(tempDir.resolve("retry-ch1.mp4"), new byte[]{1}));
        stubDownload(2, Files.write(tempDir.resolve("retry-ch2.mp4"), new byte[]{2}));
        AtomicBoolean failDetailOnce = new AtomicBoolean(true);
        when(ossService.upload(any(), anyString(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            if (key.endsWith("_ch2.mp4") && failDetailOnce.getAndSet(false)) {
                throw new IllegalStateException("OSS transient failure");
            }
            return "https://oss/" + key;
        });

        LegacyVideoSyncTestSupport.syncOnce(service);
        assertEquals(verifiedUrl(row, 1), row.getVideoUrl1());
        assertNull(row.getVideoUrl2());
        retryClock.addAndGet(100);
        nextPending(row);
        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, times(3)).startPackingDownload(any(), anyLong());
        verify(ossService, times(1)).upload(any(), eq(verifiedUrl(row, 1).substring("https://oss/".length())), anyLong());
        verify(ossService, times(2)).upload(any(), eq(verifiedUrl(row, 2).substring("https://oss/".length())), anyLong());
        InOrder order = inOrder(repository);
        order.verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        order.verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    void doesNotUploadRawFileWhenRemuxFails() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(verifiedUrl(row, 1));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownload(2, Files.write(tempDir.resolve("unverified.mp4"), new byte[]{1}));
        when(recordingRemuxer.remux(any(MediaSession.class), any(LocalDateTime.class), any(LocalDateTime.class), anyLong()))
                .thenThrow(new java.io.IOException("缺少末帧覆盖"));

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository, never()).updateUrl(anyString(), any(), anyString());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        assertEquals(verifiedUrl(row, 1), row.getVideoUrl1());
    }

    @Test
    @DisplayName("空队列仍执行回收，但不查询设备通道")
    void emptyQueueSkipsChannelLookup() {
        when(ossService.isConfigured()).thenReturn(true);
        when(repository.findPending(5)).thenReturn(List.of());

        LegacyVideoSyncTestSupport.syncOnce(service);

        InOrder order = inOrder(repository);
        order.verify(repository).recoverStaleProcessing(10);
        order.verify(repository).findFailedForRetry(30, 5, 0);
        order.verify(repository).findPending(5);
        verify(channelService, never()).listChannels(anyString());
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("通道查询失败时不抢占首批记录")
    void channelLookupFailureLeavesPendingRowsUnclaimed() {
        when(ossService.isConfigured()).thenReturn(true);
        when(repository.findPending(5)).thenReturn(List.of(pendingRow()));
        when(channelService.listChannels("DLWH1xx")).thenThrow(new IllegalStateException("offline"));

        LegacyVideoSyncTestSupport.syncOnce(service);

        InOrder order = inOrder(repository, channelService);
        order.verify(repository).findPending(5);
        order.verify(channelService).listChannels("DLWH1xx");
        verify(repository, never()).claim(anyString());
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("OSS 未配置时仍回收卡住的 PROCESSING，但不下载")
    void skipWhenOssNotConfigured() {
        when(ossService.isConfigured()).thenReturn(false);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).recoverStaleProcessing(10);
        verify(repository, never()).recoverFailedForRetry(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).findPending(org.mockito.ArgumentMatchers.anyInt());
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("channel_code 为空的行跳过且不抢占")
    void skipBlankChannelCode() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setChannelCode("  ");
        nextPending(row);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository, never()).claim(anyString());
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("抢占失败则不下载，避免双写")
    void skipWhenClaimFails() {
        when(ossService.isConfigured()).thenReturn(true);
        nextPending(pendingRow());
        when(repository.claim("2101588195825704961")).thenReturn(false);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("两路都下载上传：开始提前5秒，结束延后5秒")
    void bothChannelsSuccess(CapturedOutput output) throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        stubDownloadAndUpload();

        LegacyVideoSyncTestSupport.syncOnce(service);

        ArgumentCaptor<DownloadRequest> requests = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(downloadService, org.mockito.Mockito.times(2)).startPackingDownload(requests.capture(), anyLong());
        assertEquals(1, requests.getAllValues().get(0).getChannel());
        assertEquals(2, requests.getAllValues().get(1).getChannel());
        assertEquals("DLWH1xx", requests.getAllValues().get(0).getDeviceId());
        assertEquals("2026-09-20 16:23:49", requests.getAllValues().get(0).getStartTime());
        assertEquals("2026-09-20 16:24:21", requests.getAllValues().get(0).getEndTime());
        assertEquals("2026-09-20 16:23:49", requests.getAllValues().get(1).getStartTime());
        assertEquals("2026-09-20 16:24:21", requests.getAllValues().get(1).getEndTime());
        assertEquals(LocalDateTime.of(2026, 9, 20, 16, 23, 54), row.getStartTime());
        assertEquals(LocalDateTime.of(2026, 9, 20, 16, 24, 6), row.getEndTime());

        verify(repository).updateUrl(row.getId(), CameraChannel.PANORAMA, "https://oss/packing-window-v4/ch1.mp4");
        verify(repository).updateUrl(row.getId(), CameraChannel.DETAIL, "https://oss/packing-window-v4/ch2.mp4");
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(ossService).upload(any(), contains("_ch1.mp4"), anyLong());
        verify(ossService).upload(any(), contains("_ch2.mp4"), anyLong());
        verify(sessionManager).discardDownload("task-1");
        verify(sessionManager).discardDownload("task-2");
        InOrder persistedBeforeCleanup = inOrder(repository, sessionManager);
        persistedBeforeCleanup.verify(repository).updateUrl(row.getId(), CameraChannel.PANORAMA, "https://oss/packing-window-v4/ch1.mp4");
        persistedBeforeCleanup.verify(repository).updateUrl(row.getId(), CameraChannel.DETAIL, "https://oss/packing-window-v4/ch2.mp4");
        persistedBeforeCleanup.verify(sessionManager).discardDownload("task-1");
        persistedBeforeCleanup.verify(sessionManager).discardDownload("task-2");
        assertTrue(output.getOut().contains("提交=1, 已结束=1, 成功=1, 失败=0, 跳过=0, 异常=0, 未完成=0"));
    }

    @Test
    @DisplayName("上传录像机返回的原文件")
    void uploadsDownloadedFile() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl2(verifiedUrl(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        Path raw = Files.write(tempDir.resolve("raw.mp4"), new byte[]{1, 2, 3});
        stubOneChannel(1, raw, "https://oss/packing-window-v4/ch1.mp4");

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(ossService).upload(eq(raw), contains("_ch1.mp4"), anyLong());
        verify(repository).updateUrl(row.getId(), CameraChannel.PANORAMA, "https://oss/packing-window-v4/ch1.mp4");
    }

    @Test
    @DisplayName("已有 video_url1 时只补通道 2")
    void skipExistingUrl() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(verifiedUrl(row, 1));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        Path file = Files.write(tempDir.resolve("ch2.mp4"), new byte[]{1, 2, 3});
        stubOneChannel(2, file, "https://oss/packing-window-v4/ch2.mp4");

        LegacyVideoSyncTestSupport.syncOnce(service);

        ArgumentCaptor<DownloadRequest> requests = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(downloadService, org.mockito.Mockito.times(1)).startPackingDownload(requests.capture(), anyLong());
        assertEquals(2, requests.getValue().getChannel());
        verify(repository, never()).updateUrl(eq(row.getId()), eq(CameraChannel.PANORAMA), anyString());
        verify(repository).updateUrl(row.getId(), CameraChannel.DETAIL, "https://oss/packing-window-v4/ch2.mp4");
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(sessionManager).discardDownload("task-2");
    }

    @Test
    @DisplayName("channel_code 打包台2 下载设备通道 3 和 4，分别写入 url1 和 url2")
    void matchesStationCodeToDeviceChannels() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(3, "打包台2全景"),
                new DeviceChannel(4, "打包台2细节")));
        WaybillVideoRecord row = pendingRow();
        row.setChannelCode("打包台2");
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        Path panorama = Files.write(tempDir.resolve("p.mp4"), new byte[]{1});
        Path detail = Files.write(tempDir.resolve("d.mp4"), new byte[]{2});
        stubDownload(3, panorama);
        stubDownload(4, detail);
        when(ossService.upload(any(), contains("_ch1"), anyLong())).thenReturn("https://oss/packing-window-v4/ch1.mp4");
        when(ossService.upload(any(), contains("_ch2"), anyLong())).thenReturn("https://oss/packing-window-v4/ch2.mp4");

        LegacyVideoSyncTestSupport.syncOnce(service);

        ArgumentCaptor<DownloadRequest> requests = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(downloadService, times(2)).startPackingDownload(requests.capture(), anyLong());
        assertEquals(3, requests.getAllValues().get(0).getChannel());
        assertEquals(4, requests.getAllValues().get(1).getChannel());
        verify(repository).updateUrl(row.getId(), CameraChannel.PANORAMA, "https://oss/packing-window-v4/ch1.mp4");
        verify(repository).updateUrl(row.getId(), CameraChannel.DETAIL, "https://oss/packing-window-v4/ch2.mp4");
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    @DisplayName("缺全景时不能单独上传细节，整单 FAILED")
    void missingPanoramaPreventsPairUpload(CapturedOutput output) throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        when(channelService.listChannels("DLWH1xx")).thenReturn(List.of(
                new DeviceChannel(2, "打包台1细节")));
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);


        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(repository, never()).updateUrl(anyString(), any(), anyString());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        assertTrue(output.getOut().contains("成功=0, 失败=1, 跳过=0, 异常=0, 未完成=0"));
    }

    @Test
    @DisplayName("状态回写未生效时汇总不误报成功")
    void summaryDoesNotCountUnpersistedSuccess(CapturedOutput output) {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.SUCCESS)).thenReturn(false);

        LegacyVideoSyncTestSupport.syncOnce(service);

        assertTrue(output.getOut().contains("成功=0, 失败=0, 跳过=1, 异常=0, 未完成=0"));
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("没有唯一在线设备时不下载")
    void skipWhenDeviceIsNotUnique() {
        when(ossService.isConfigured()).thenReturn(true);
        when(deviceRegistry.onlineDeviceIds()).thenReturn(List.of());

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository, never()).findPending(org.mockito.ArgumentMatchers.anyInt());
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("通道 1 下载失败即结束本行，不再启动通道 2，状态 FAILED")
    void oneChannelFailsPreventsOtherUpload() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        when(downloadService.startPackingDownload(any(), anyLong())).thenThrow(new RuntimeException("通道1下载失败"));

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository, never()).updateUrl(anyString(), any(), anyString());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        verify(downloadService, times(1)).startPackingDownload(any(), anyLong());
        verify(sessionManager, never()).awaitFinished(anyString(), anyLong());
        verify(sessionManager, never()).discardDownload(anyString());
    }

    @Test
    @DisplayName("下载任务 FAILED 时该通道不算成功")
    void downloadFailedMarksRecordFailed() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow();
        row.setVideoUrl1(verifiedUrl(row, 1));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        DownloadTaskResponse resp = new DownloadTaskResponse("task-2", SessionStatus.STARTING);
        when(downloadService.startPackingDownload(any(), anyLong())).thenReturn(resp);
        MediaSession session = new MediaSession("task-2", SessionType.DOWNLOAD, "DLWH1xx", 2);
        session.setStatus(SessionStatus.FAILED);
        session.setError("设备无这段录像");
        when(sessionManager.awaitFinished(eq("task-2"), anyLong())).thenReturn(session);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(ossService, never()).upload(any(), any(), anyLong());
        verify(sessionManager, never()).discardDownload(anyString());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    @DisplayName("每轮先回收超时 PROCESSING，再收回 FAILED，再查 PENDING")
    void recoverBeforeFindPending() {
        when(ossService.isConfigured()).thenReturn(true);
        when(repository.recoverStaleProcessing(10)).thenReturn(2);
        when(repository.findFailedForRetry(30, 5, 0)).thenReturn(List.of());
        when(repository.findPending(5)).thenReturn(List.of());

        LegacyVideoSyncTestSupport.syncOnce(service);

        InOrder order = inOrder(repository);
        order.verify(repository).recoverStaleProcessing(10);
        order.verify(repository).findFailedForRetry(30, 5, 0);
        order.verify(repository).findPending(5);
    }

    @Test
    void failedRetryPagingDoesNotSkipRowsShiftedByPreviousRecovery() throws Exception {
        verifyConsecutiveFailedRecoveryPages(false);
    }

    @Test
    void uncertainFailedRecoveryRestartsScanInsteadOfSkippingOldRows() throws Exception {
        verifyConsecutiveFailedRecoveryPages(true);
    }

    private void verifyConsecutiveFailedRecoveryPages(boolean uncertainFirstUpdate) throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        List<WaybillVideoRecord> failed = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<WaybillVideoRecord> pending = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<List<String>> selectedPages = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            WaybillVideoRecord row = pendingRow("retry-" + i);
            row.setStatus(WaybillVideoStatus.FAILED.name());
            row.setVideoUrl1(verifiedUrl(row, 1));
            row.setVideoUrl2(verifiedUrl(row, 2));
            failed.add(row);
        }
        when(repository.findFailedForRetry(eq(30), eq(5), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            int offset = inv.getArgument(2);
            List<WaybillVideoRecord> page = failed.stream().skip(offset).limit(5).toList();
            selectedPages.add(page.stream().map(WaybillVideoRecord::getId).toList());
            return page;
        });
        when(repository.findPending(5)).thenAnswer(inv -> pending.stream().limit(5).toList());
        AtomicBoolean firstUpdate = new AtomicBoolean(true);
        when(repository.recoverFailedForRetry(eq(30), org.mockito.ArgumentMatchers.<String>anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(1);
            List<WaybillVideoRecord> recovered = failed.stream().filter(row -> ids.contains(row.getId())).toList();
            pending.addAll(recovered);
            failed.removeAll(recovered);
            if (uncertainFirstUpdate && firstUpdate.getAndSet(false)) {
                throw new org.springframework.dao.DataAccessResourceFailureException("connection lost after UPDATE");
            }
            return recovered.size();
        });
        when(repository.claim(anyString())).thenAnswer(inv -> pending.removeIf(row -> row.getId().equals(inv.getArgument(0))));

        if (uncertainFirstUpdate) {
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> LegacyVideoSyncTestSupport.syncOnce(service));
        } else {
            LegacyVideoSyncTestSupport.syncOnce(service);
        }
        LegacyVideoSyncTestSupport.syncOnce(service);

        assertEquals(List.of("retry-1", "retry-2", "retry-3", "retry-4", "retry-5"), selectedPages.get(0));
        assertEquals(List.of("retry-6", "retry-7", "retry-8", "retry-9", "retry-10"), selectedPages.get(1));
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("一批 5 条处理完后继续拉下一批，直到队列空")
    void drainsMultipleBatches() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord first = pendingRow("id-1");
        WaybillVideoRecord second = pendingRow("id-2");
        when(repository.findPending(5)).thenReturn(List.of(first), List.of(second), List.of());
        when(repository.claim("id-1")).thenReturn(true);
        when(repository.claim("id-2")).thenReturn(true);
        stubDownloadAndUpload();

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).claim("id-1");
        verify(repository).claim("id-2");
        verify(repository).updateStatus("id-1", WaybillVideoStatus.SUCCESS);
        verify(repository).updateStatus("id-2", WaybillVideoStatus.SUCCESS);
        verify(repository, times(3)).findPending(5);
        verify(channelService, times(1)).listChannels("DLWH1xx");
        InOrder order = inOrder(repository, channelService);
        order.verify(repository).findPending(5);
        order.verify(channelService).listChannels("DLWH1xx");
        order.verify(repository).claim("id-1");
    }

    @Test
    @DisplayName("一批行数占满行线程池时，通道下载仍能完成")
    void channelDownloadsCompleteWhenRowPoolIsFull() throws Exception {
        service.close();
        properties.getWaybillVideo().setDownloadConcurrency(1);
        properties.getWaybillVideo().setBatchSize(4);
        service = new WaybillVideoSyncService(properties, downloadService, sessionManager,
                ossService, repository, channelService, deviceRegistry, recordingRemuxer);

        when(ossService.isConfigured()).thenReturn(true);
        List<WaybillVideoRecord> rows = List.of(
                pendingRow("id-1"), pendingRow("id-2"), pendingRow("id-3"), pendingRow("id-4"));
        when(repository.findPending(4)).thenReturn(rows, List.of());
        when(repository.claim(anyString())).thenReturn(true);
        when(downloadService.startPackingDownload(any(), anyLong())).thenAnswer(inv -> {
            DownloadRequest req = inv.getArgument(0);
            return new DownloadTaskResponse(req.getDeviceId() + "-" + req.getChannel(),
                    SessionStatus.STARTING);
        });
        when(sessionManager.awaitFinished(anyString(), anyLong())).thenAnswer(inv -> {
            String taskId = inv.getArgument(0);
            Path file = Files.write(tempDir.resolve(taskId + ".mp4"), new byte[]{1, 2, 3});
            MediaSession session = new MediaSession(taskId, SessionType.DOWNLOAD, "DLWH1xx", 1);
            session.setStatus(SessionStatus.COMPLETED);
            session.setFilePath(file.toString());
        session.setRawFilePath(file.toString());
            return session;
        });
        when(ossService.upload(any(), any(), anyLong())).thenReturn("https://oss/packing-window-v4/ok.mp4");

        assertTimeoutPreemptively(Duration.ofSeconds(8), () -> LegacyVideoSyncTestSupport.syncOnce(service));

        verify(repository, times(4)).updateStatus(anyString(), eq(WaybillVideoStatus.SUCCESS));
        verify(downloadService, times(8)).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("一行完成后立即补位，不等待同批长下载结束")
    void replenishesSlotBeforeSlowRowFinishes() throws Exception {
        service.close();
        properties.getWaybillVideo().setBatchSize(2);
        properties.getWaybillVideo().setDownloadConcurrency(2);
        service = new WaybillVideoSyncService(properties, downloadService, sessionManager,
                ossService, repository, channelService, deviceRegistry, recordingRemuxer);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord first = pendingRow("id-slow");
        WaybillVideoRecord second = pendingRow("id-fast");
        WaybillVideoRecord third = pendingRow("id-next");
        second.setStartTime(first.getStartTime().plusSeconds(1));
        third.setStartTime(first.getStartTime().plusSeconds(2));
        for (WaybillVideoRecord row : List.of(first, second, third)) {
            row.setVideoUrl2(verifiedUrl(row, 2));
        }
        when(repository.findPending(2)).thenReturn(List.of(first, second), List.of(third), List.of());
        when(repository.claim(anyString())).thenReturn(true);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        Map<String, String> taskIds = Map.of(
                "2026-09-20 16:23:49", "task-slow",
                "2026-09-20 16:23:50", "task-fast",
                "2026-09-20 16:23:51", "task-next");
        Path file = Files.write(tempDir.resolve("rolling.mp4"), new byte[]{1, 2, 3});
        when(downloadService.startPackingDownload(any(), anyLong())).thenAnswer(inv -> {
            DownloadRequest request = inv.getArgument(0);
            String taskId = taskIds.get(request.getStartTime());
            if ("task-next".equals(taskId)) {
                thirdStarted.countDown();
            }
            return new DownloadTaskResponse(taskId, SessionStatus.STARTING);
        });
        when(sessionManager.awaitFinished(anyString(), anyLong())).thenAnswer(inv -> {
            String taskId = inv.getArgument(0);
            if ("task-slow".equals(taskId)) {
                firstStarted.countDown();
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS), "长下载必须能被测试释放");
            } else if ("task-fast".equals(taskId)) {
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "先确认长下载已经占用一个槽位");
            }
            MediaSession session = new MediaSession(taskId, SessionType.DOWNLOAD, "DLWH1xx", 1);
            session.setStatus(SessionStatus.COMPLETED);
            session.setFilePath(file.toString());
        session.setRawFilePath(file.toString());
            return session;
        });
        when(ossService.upload(any(), any(), anyLong())).thenReturn("https://oss/packing-window-v4/rolling.mp4");
        when(repository.updateStatus("id-fast", WaybillVideoStatus.SUCCESS)).thenAnswer(inv -> {
            secondFinished.countDown();
            return true;
        });
        ExecutorService runner = Executors.newSingleThreadExecutor();
        Future<?> execution = runner.submit(() -> LegacyVideoSyncTestSupport.syncOnce(service));
        try {
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "首条长下载应启动");
            assertTrue(secondFinished.await(5, TimeUnit.SECONDS), "第二条应先完成并释放槽位");
            assertTrue(thirdStarted.await(3, TimeUnit.SECONDS), "空闲槽应在首条结束前启动第三条");
            assertEquals(1, releaseFirst.getCount(), "第三条启动不应依赖首条释放");
        } finally {
            releaseFirst.countDown();
            try {
                execution.get(5, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
                assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        verify(repository).updateStatus("id-slow", WaybillVideoStatus.SUCCESS);
        verify(repository).updateStatus("id-next", WaybillVideoStatus.SUCCESS);
    }

    @Test
    @DisplayName("整轮预算到期只停止接新行，已抢占行仍完成双路")
    void stopsNewRowsWhenTimeBudgetExceeded() throws Exception {
        service.close();
        properties.getWaybillVideo().setMaxRunSeconds(1);
        AtomicLong now = new AtomicLong(0);
        service = new WaybillVideoSyncService(properties, downloadService, sessionManager,
                ossService, repository, channelService, deviceRegistry, recordingRemuxer, now::get);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord first = pendingRow("id-1");
        WaybillVideoRecord second = pendingRow("id-2");
        when(repository.findPending(5)).thenReturn(List.of(first, second), List.of());
        when(repository.claim("id-1")).thenAnswer(inv -> {
            now.set(TimeUnit.SECONDS.toNanos(2));
            return true;
        });
        stubDownloadAndUpload();

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).claim("id-1");
        verify(repository, never()).claim("id-2");
        verify(downloadService, times(2)).startPackingDownload(any(), anyLong());
        verify(repository).updateUrl("id-1", CameraChannel.PANORAMA, "https://oss/packing-window-v4/ch1.mp4");
        verify(repository).updateUrl("id-1", CameraChannel.DETAIL, "https://oss/packing-window-v4/ch2.mp4");
        verify(repository).updateStatus("id-1", WaybillVideoStatus.SUCCESS);
        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(sessionManager, times(2)).awaitFinished(anyString(), timeout.capture());
        assertTrue(timeout.getAllValues().stream().allMatch(value -> value > 1000),
                "已接任务的两路下载不应被整轮剩余预算截断");
        verify(repository, times(1)).findPending(5);
    }

    @Test
    @DisplayName("单路下载独立超时，不使用整轮接单预算截短等待")
    void downloadTimeoutIsIndependentOfRunDeadline() {
        service.close();
        properties.getWaybillVideo().setMaxRunSeconds(1);
        service = new WaybillVideoSyncService(properties, downloadService, sessionManager,
                ossService, repository, channelService, deviceRegistry, recordingRemuxer, System::nanoTime);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-timeout");
        row.setVideoUrl1(verifiedUrl(row, 1));
        when(repository.findPending(5)).thenReturn(List.of(row), List.of());
        when(repository.claim(row.getId())).thenReturn(true);
        when(downloadService.startPackingDownload(any(), anyLong()))
                .thenReturn(new DownloadTaskResponse("task-timeout", SessionStatus.STARTING));
        MediaSession running = new MediaSession("task-timeout", SessionType.DOWNLOAD, "DLWH1xx", 2);
        running.setStatus(SessionStatus.RUNNING);
        when(sessionManager.awaitFinished(eq("task-timeout"), anyLong())).thenReturn(running);

        LegacyVideoSyncTestSupport.syncOnce(service);

        ArgumentCaptor<Long> timeout = ArgumentCaptor.forClass(Long.class);
        verify(sessionManager).awaitFinished(eq("task-timeout"), timeout.capture());
        assertTrue(timeout.getValue() > 1000, "单路等待应有独立超时，实际=" + timeout.getValue());
        verify(sessionManager).stopAsync(eq(running), eq(SessionStatus.FAILED), anyString());
        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    @DisplayName("下载等待保留中断时，清除中断完成 FAILED 回写")
    void interruptedDownloadStillPersistsFailedStatus() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-interrupted-download");
        row.setVideoUrl1(verifiedUrl(row, 1));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(downloadService.startPackingDownload(any(), anyLong()))
                .thenReturn(new DownloadTaskResponse("task-interrupted", SessionStatus.STARTING));
        MediaSession interrupted = new MediaSession("task-interrupted", SessionType.DOWNLOAD, "DLWH1xx", 2);
        interrupted.setStatus(SessionStatus.FAILED);
        interrupted.setError("等待下载被中断");
        when(sessionManager.awaitFinished(eq("task-interrupted"), anyLong())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return interrupted;
        });
        AtomicBoolean persisted = new AtomicBoolean();
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.FAILED)).thenAnswer(inv -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("connection acquisition interrupted");
            }
            persisted.set(true);
            return true;
        });

        try {
            assertDoesNotThrow(() -> LegacyVideoSyncTestSupport.syncOnce(service));
            assertTrue(persisted.get(), "中断后的失败状态必须真正写入");
            verify(ossService, never()).upload(any(), any(), anyLong());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("上传返回时线程已中断，保留成功 URL 后结束未完成行")
    void interruptedUploadStillPersistsUrlAndFailedStatus() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-interrupted-upload");
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        Path file = Files.write(tempDir.resolve("interrupted-upload.mp4"), new byte[]{1, 2, 3});
        stubDownload(1, file);
        stubDownload(2, Files.write(tempDir.resolve("interrupted-upload-detail.mp4"), new byte[]{1, 2, 3}));
        when(ossService.upload(any(), contains("_ch1"), anyLong())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return "https://oss/packing-window-v4/uploaded-panorama.mp4";
        });
        AtomicBoolean urlPersisted = new AtomicBoolean();
        AtomicBoolean statusPersisted = new AtomicBoolean();
        when(repository.updateUrl(row.getId(), CameraChannel.PANORAMA, "https://oss/packing-window-v4/uploaded-panorama.mp4"))
                .thenAnswer(inv -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("connection acquisition interrupted");
                    }
                    urlPersisted.set(true);
                    return true;
                });
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.FAILED)).thenAnswer(inv -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("connection acquisition interrupted");
            }
            statusPersisted.set(true);
            return true;
        });

        try {
            assertDoesNotThrow(() -> LegacyVideoSyncTestSupport.syncOnce(service));
            assertTrue(urlPersisted.get(), "已经成功上传的 URL 必须保留");
            assertTrue(statusPersisted.get(), "未完成的另一通道应使本行结束为 FAILED");
            verify(downloadService, times(2)).startPackingDownload(any(), anyLong());
            assertFalse(row.hasUrl(CameraChannel.DETAIL));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("URL 回写异常仍尝试 FAILED 收尾，后续行继续处理")
    void urlWriteFailureStillFinalizesAndContinuesWithNextRow() throws Exception {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord first = pendingRow("id-write-failed");
        WaybillVideoRecord second = pendingRow("id-after-failure");
        first.setVideoUrl2(verifiedUrl(first, 2));
        second.setVideoUrl1(verifiedUrl(second, 1));
        nextPending(first, second);
        when(repository.claim(anyString())).thenReturn(true);
        stubDownloadAndUpload();
        when(repository.updateUrl(eq(first.getId()), eq(CameraChannel.PANORAMA), anyString()))
                .thenThrow(new IllegalStateException("database write failed"));

        assertDoesNotThrow(() -> LegacyVideoSyncTestSupport.syncOnce(service));

        verify(repository).updateStatus(first.getId(), WaybillVideoStatus.FAILED);
        verify(repository).claim(second.getId());
        verify(sessionManager).discardDownload("task-1");
        verify(repository).updateUrl(second.getId(), CameraChannel.DETAIL, "https://oss/packing-window-v4/ch2.mp4");
        verify(repository).updateStatus(second.getId(), WaybillVideoStatus.SUCCESS);
        InOrder persistedBeforeCleanup = inOrder(repository, sessionManager);
        persistedBeforeCleanup.verify(repository).updateUrl(second.getId(), CameraChannel.DETAIL, "https://oss/packing-window-v4/ch2.mp4");
        persistedBeforeCleanup.verify(sessionManager).discardDownload("task-2");
    }

    @Test
    @DisplayName("两路 URL 已齐的 PENDING 直接补 SUCCESS，无需重新下载上传")
    void completesPendingRowWhenBothUrlsAlreadyExist() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-urls-complete");
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
        verify(ossService, never()).upload(any(), any(), anyLong());
        verify(repository, never()).updateUrl(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("补位查询异常时，仍等待已在途行完成收尾")
    void refillQueryFailureStillWaitsForInFlightRows() throws Exception {
        service.close();
        properties.getWaybillVideo().setBatchSize(2);
        properties.getWaybillVideo().setDownloadConcurrency(2);
        service = new WaybillVideoSyncService(properties, downloadService, sessionManager,
                ossService, repository, channelService, deviceRegistry, recordingRemuxer);
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord slow = pendingRow("id-query-slow");
        slow.setVideoUrl2(verifiedUrl(slow, 2));
        WaybillVideoRecord ready = pendingRow("id-query-ready");
        ready.setVideoUrl1(verifiedUrl(ready, 1));
        ready.setVideoUrl2(verifiedUrl(ready, 2));
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        CountDownLatch refillAttempted = new CountDownLatch(1);
        CountDownLatch runReturned = new CountDownLatch(1);
        when(repository.findPending(2)).thenReturn(List.of(slow, ready)).thenAnswer(inv -> {
            refillAttempted.countDown();
            throw new IllegalStateException("pending query failed");
        });
        when(repository.claim(anyString())).thenReturn(true);
        Path file = Files.write(tempDir.resolve("query-failure.mp4"), new byte[]{1, 2, 3});
        when(downloadService.startPackingDownload(any(), anyLong()))
                .thenReturn(new DownloadTaskResponse("task-query-slow", SessionStatus.STARTING));
        when(sessionManager.awaitFinished(eq("task-query-slow"), anyLong())).thenAnswer(inv -> {
            downloadStarted.countDown();
            assertTrue(releaseDownload.await(10, TimeUnit.SECONDS), "在途下载必须能被测试释放");
            MediaSession session = new MediaSession("task-query-slow", SessionType.DOWNLOAD, "DLWH1xx", 1);
            session.setStatus(SessionStatus.COMPLETED);
            session.setFilePath(file.toString());
        session.setRawFilePath(file.toString());
            return session;
        });
        when(ossService.upload(any(), any(), anyLong())).thenReturn("https://oss/packing-window-v4/query-slow.mp4");
        ExecutorService runner = Executors.newSingleThreadExecutor();
        Future<?> execution = runner.submit(() -> {
            try {
                LegacyVideoSyncTestSupport.syncOnce(service);
            } finally {
                runReturned.countDown();
            }
        });
        try {
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS), "首条下载应已在途");
            assertTrue(refillAttempted.await(3, TimeUnit.SECONDS), "空闲槽应触发下一次查询");
            assertFalse(runReturned.await(200, TimeUnit.MILLISECONDS), "查询异常不能让整轮丢下在途行提前返回");
        } finally {
            releaseDownload.countDown();
            try {
                execution.get(5, TimeUnit.SECONDS);
            } finally {
                runner.shutdownNow();
                assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        verify(repository).updateStatus(slow.getId(), WaybillVideoStatus.SUCCESS);
        verify(repository).updateStatus(ready.getId(), WaybillVideoStatus.SUCCESS);
    }

    @Test
    @DisplayName("本轮重复返回已提交 ID 时只抢占一次")
    void duplicatePendingIdIsClaimedOnlyOncePerRun() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-duplicate");
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        when(repository.findPending(5)).thenReturn(List.of(row), List.of(row), List.of());
        when(repository.claim(row.getId())).thenReturn(true);

        LegacyVideoSyncTestSupport.syncOnce(service);

        verify(repository, times(1)).claim(row.getId());
        verify(repository, times(1)).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        verify(downloadService, never()).startPackingDownload(any(), anyLong());
    }

    @Test
    @DisplayName("SUCCESS 回写异常时仍尝试 FAILED 收尾")
    void successStatusWriteFailureAttemptsFailedStatus() {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-success-write-failed");
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.SUCCESS))
                .thenThrow(new IllegalStateException("success status write failed"));

        assertDoesNotThrow(() -> LegacyVideoSyncTestSupport.syncOnce(service));

        InOrder finalization = inOrder(repository);
        finalization.verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        finalization.verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
    }

    @Test
    @DisplayName("SUCCESS 和 FAILED 回写均失败时只计异常，不误报已收尾")
    void failedFallbackStatusWriteIsCountedAsError(CapturedOutput output) {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-all-status-writes-failed");
        row.setVideoUrl1(verifiedUrl(row, 1));
        row.setVideoUrl2(verifiedUrl(row, 2));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.SUCCESS))
                .thenThrow(new IllegalStateException("success status write failed"));
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.FAILED))
                .thenThrow(new IllegalStateException("failed status write failed"));

        assertDoesNotThrow(() -> LegacyVideoSyncTestSupport.syncOnce(service));

        InOrder finalization = inOrder(repository);
        finalization.verify(repository).updateStatus(row.getId(), WaybillVideoStatus.SUCCESS);
        finalization.verify(repository).updateStatus(row.getId(), WaybillVideoStatus.FAILED);
        assertTrue(output.getOut().contains("成功=0, 失败=0, 跳过=0, 异常=1, 未完成=0"));
    }

    @Test
    @DisplayName("调度线程被中断后等待 FAILED 回写完成，再恢复中断退出")
    void schedulerInterruptWaitsForFailedPersistence() throws Exception {
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        CountDownLatch finalizationStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);
        CountDownLatch schedulerReturned = new CountDownLatch(1);
        AtomicBoolean persisted = new AtomicBoolean();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> schedulerFailure = new AtomicReference<>();
        stubInterruptedRowFinalization(downloadStarted, releaseDownload,
                finalizationStarted, releaseFinalization, persisted);
        Thread scheduler = new Thread(() -> {
            try {
                LegacyVideoSyncTestSupport.syncOnce(service);
            } catch (Throwable failure) {
                schedulerFailure.set(failure);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
                schedulerReturned.countDown();
            }
        }, "interrupted-video-scheduler");
        scheduler.start();
        try {
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS), "工作线程应先进入下载等待");
            scheduler.interrupt();
            assertTrue(finalizationStarted.await(5, TimeUnit.SECONDS), "取消后应尝试 FAILED 回写");
            assertFalse(schedulerReturned.await(200, TimeUnit.MILLISECONDS),
                    "Future 取消后，调度器必须等待实际数据库收尾");
            assertFalse(persisted.get(), "回写仍被阻塞");
        } finally {
            releaseDownload.countDown();
            releaseFinalization.countDown();
            scheduler.join(5000);
            if (scheduler.isAlive()) {
                scheduler.interrupt();
                scheduler.join(5000);
            }
            assertFalse(scheduler.isAlive(), "测试必须释放并结束调度线程");
        }
        assertNull(schedulerFailure.get());
        assertTrue(persisted.get(), "返回前应完成 FAILED 回写");
        assertTrue(interruptRestored.get(), "调度线程的中断标志应恢复");
    }

    @Test
    @DisplayName("close 调用线程已中断时仍等待 FAILED 回写，再恢复中断返回")
    void interruptedCloseWaitsForFailedPersistence() throws Exception {
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        CountDownLatch finalizationStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicBoolean persisted = new AtomicBoolean();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> schedulerFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        stubInterruptedRowFinalization(downloadStarted, releaseDownload,
                finalizationStarted, releaseFinalization, persisted);
        Thread scheduler = new Thread(() -> {
            try {
                LegacyVideoSyncTestSupport.syncOnce(service);
            } catch (Throwable failure) {
                schedulerFailure.set(failure);
            }
        }, "closing-video-scheduler");
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                service.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            } finally {
                interruptRestored.set(Thread.currentThread().isInterrupted());
                closeReturned.countDown();
            }
        }, "interrupted-video-close");
        scheduler.start();
        try {
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS), "工作线程应先进入下载等待");
            closer.start();
            assertTrue(finalizationStarted.await(5, TimeUnit.SECONDS), "关闭应中断下载并进入 FAILED 回写");
            assertFalse(closeReturned.await(200, TimeUnit.MILLISECONDS),
                    "close 被中断后也必须等待工作线程实际收尾");
            assertFalse(persisted.get(), "回写仍被阻塞");
        } finally {
            releaseDownload.countDown();
            releaseFinalization.countDown();
            closer.join(5000);
            scheduler.join(5000);
            if (closer.isAlive()) {
                closer.interrupt();
                closer.join(5000);
            }
            if (scheduler.isAlive()) {
                scheduler.interrupt();
                scheduler.join(5000);
            }
            assertFalse(closer.isAlive(), "测试必须释放并结束关闭线程");
            assertFalse(scheduler.isAlive(), "测试必须释放并结束调度线程");
        }
        assertNull(schedulerFailure.get());
        assertNull(closeFailure.get());
        assertTrue(persisted.get(), "close 返回前应完成 FAILED 回写");
        assertTrue(interruptRestored.get(), "close 调用线程的中断标志应恢复");
    }

    @Test
    @DisplayName("上一轮未结束时 Job 跳过，避免重叠执行")
    void jobSkipsWhenAlreadyRunning() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WaybillVideoSyncService blocking = org.mockito.Mockito.mock(WaybillVideoSyncService.class);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return null;
        }).when(blocking).syncOnce();

        WaybillVideoSyncJob job = new WaybillVideoSyncJob(blocking);
        Thread first = new Thread(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "legacyRun"), "job-1");
        first.start();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "legacyRun");
        release.countDown();
        first.join(5000);

        assertEquals(1, calls.get());
    }

    private void stubInterruptedRowFinalization(CountDownLatch downloadStarted, CountDownLatch releaseDownload,
                                                CountDownLatch finalizationStarted, CountDownLatch releaseFinalization,
                                                AtomicBoolean persisted) {
        when(ossService.isConfigured()).thenReturn(true);
        WaybillVideoRecord row = pendingRow("id-interrupted-finalization");
        row.setVideoUrl1(verifiedUrl(row, 1));
        nextPending(row);
        when(repository.claim(row.getId())).thenReturn(true);
        when(downloadService.startPackingDownload(any(), anyLong()))
                .thenReturn(new DownloadTaskResponse("task-interrupted-finalization", SessionStatus.STARTING));
        when(sessionManager.awaitFinished(eq("task-interrupted-finalization"), anyLong())).thenAnswer(inv -> {
            downloadStarted.countDown();
            try {
                assertTrue(releaseDownload.await(10, TimeUnit.SECONDS), "下载等待必须能被测试释放");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            MediaSession session = new MediaSession("task-interrupted-finalization", SessionType.DOWNLOAD, "DLWH1xx", 2);
            session.setStatus(SessionStatus.FAILED);
            session.setError("等待下载被中断");
            return session;
        });
        when(repository.updateStatus(row.getId(), WaybillVideoStatus.FAILED)).thenAnswer(inv -> {
            assertFalse(Thread.currentThread().isInterrupted(), "数据库收尾不能携带线程中断标志");
            finalizationStarted.countDown();
            assertTrue(releaseFinalization.await(10, TimeUnit.SECONDS), "终态回写必须能被测试释放");
            persisted.set(true);
            return true;
        });
    }

    private void stubDownloadAndUpload() throws Exception {
        Path file1 = Files.write(tempDir.resolve("ch1.mp4"), new byte[]{1, 2, 3});
        Path file2 = Files.write(tempDir.resolve("ch2.mp4"), new byte[]{4, 5, 6});
        stubOneChannel(1, file1, "https://oss/packing-window-v4/ch1.mp4");
        stubOneChannel(2, file2, "https://oss/packing-window-v4/ch2.mp4");
    }

    private void stubDownload(int deviceChannel, Path file) {
        String taskId = "task-" + deviceChannel;
        when(downloadService.startPackingDownload(org.mockito.ArgumentMatchers.argThat(
                req -> req != null && req.getChannel() != null && req.getChannel() == deviceChannel), anyLong()))
                .thenReturn(new DownloadTaskResponse(taskId, SessionStatus.STARTING));
        MediaSession session = new MediaSession(taskId, SessionType.DOWNLOAD, "DLWH1xx", deviceChannel);
        session.setStatus(SessionStatus.COMPLETED);
        session.setFilePath(file.toString());
        session.setRawFilePath(file.toString());
        when(sessionManager.awaitFinished(eq(taskId), anyLong())).thenReturn(session);
    }

    private void stubOneChannel(int channel, Path file, String url) {
        String taskId = "task-" + channel;
        when(downloadService.startPackingDownload(org.mockito.ArgumentMatchers.argThat(
                req -> req != null && req.getChannel() != null && req.getChannel() == channel), anyLong()))
                .thenReturn(new DownloadTaskResponse(taskId, SessionStatus.STARTING));
        MediaSession session = new MediaSession(taskId, SessionType.DOWNLOAD, "DLWH1xx", channel);
        session.setStatus(SessionStatus.COMPLETED);
        session.setFilePath(file.toString());
        session.setRawFilePath(file.toString());
        when(sessionManager.awaitFinished(eq(taskId), anyLong())).thenReturn(session);
        when(ossService.upload(any(), contains("_ch" + channel), anyLong())).thenReturn(url);
    }

    private void nextPending(WaybillVideoRecord... rows) {
        when(repository.findPending(5)).thenReturn(List.of(rows), List.of());
    }

    private static WaybillVideoRecord pendingRow() {
        return pendingRow("2101588195825704961");
    }

    private static String verifiedUrl(WaybillVideoRecord row, int channel) {
        return "https://oss/" + WaybillVideoSyncService.buildRecordingObjectKey("waybill-video/", row,
                CameraChannel.fromCode(channel), channel);
    }

    private static String legacyV4Url(WaybillVideoRecord row, int channel) {
        return verifiedUrl(row, channel).replace("/packing-segment-v1/", "/packing-window-v4/");
    }

    private static String segmentUrl(WaybillVideoRecord row, int channel) {
        return verifiedUrl(row, channel).replace("/packing-window-v4/", "/packing-segment-v1/");
    }

    private static WaybillVideoRecord pendingRow(String id) {
        WaybillVideoRecord row = new WaybillVideoRecord();
        row.setId(id);
        row.setDeviceId("DLWH1xx");
        row.setChannelCode("打包台1");
        row.setStartTime(LocalDateTime.of(2026, 9, 20, 16, 23, 54));
        row.setEndTime(LocalDateTime.of(2026, 9, 20, 16, 24, 6));
        row.setStatus(WaybillVideoStatus.PENDING.name());
        return row;
    }
}
