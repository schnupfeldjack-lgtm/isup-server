package com.hiki.isup.repository;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.CameraChannel;
import com.hiki.isup.model.WaybillVideoRecord;
import com.hiki.isup.model.WaybillVideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WaybillVideoRepositoryTest {

    @Test
    void snapshotWritesBindBusinessFieldsAndBothPriorUrls() {
        com.hiki.isup.model.WaybillVideoRecord row = new com.hiki.isup.model.WaybillVideoRecord();
        row.setId("snapshot-1");
        row.setStartTime(java.time.LocalDateTime.of(2026, 10, 14, 14, 0));
        row.setEndTime(row.getStartTime().plusMinutes(1));
        row.setChannelCode("打包台1");
        row.setVideoUrl1("existing-panorama");
        row.setVideoUrl2(null);
        row.setWaybillId("waybill-1");
        row.setOutboundWaybillId("outbound-1");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(repository, "claimSnapshot", row);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(repository, "updateUrlSnapshot", row, CameraChannel.DETAIL, "new-detail");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(repository, "updateStatusSnapshot", row, WaybillVideoStatus.SUCCESS);
        var calls = org.mockito.Mockito.mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update")).toList();
        assertEquals(3, calls.size());
        for (var call : calls) {
            String statement = (String) call.getArgument(0);
            String condition = statement.substring(statement.indexOf(" WHERE "));
            for (String column : List.of("start_time", "end_time", "channel_code", "video_url1", "video_url2", "waybill_id", "outbound_waybill_id")) {
                assertTrue(condition.contains(column + " <=> ?"), "缺少快照条件 " + column);
            }
            List<Object> parameters = java.util.Arrays.asList(call.getArguments());
            assertTrue(parameters.contains(java.sql.Timestamp.valueOf(row.getStartTime())));
            assertTrue(parameters.contains(java.sql.Timestamp.valueOf(row.getEndTime())));
            assertTrue(parameters.contains("existing-panorama"));
            assertTrue(parameters.contains("打包台1"));
            assertTrue(parameters.contains(null), "空地址必须用 NULL 安全比较");
        }
    }

    @Test
    void changedSnapshotReturnsFalseWithoutAnyIdOnlyFallback() {
        WaybillVideoRecord row = new WaybillVideoRecord();
        row.setId("changed-row");
        assertFalse(repository.claimSnapshot(row));
        assertFalse(repository.updateUrlSnapshot(row, CameraChannel.DETAIL, "new-url"));
        assertFalse(repository.updateStatusSnapshot(row, WaybillVideoStatus.SUCCESS));
        var calls = org.mockito.Mockito.mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update")).toList();
        assertEquals(3, calls.size(), "每个CAS只写一次，不得退回按ID覆盖");
        assertEquals("PENDING", calls.get(0).getArgument(4));
        assertEquals("PROCESSING", calls.get(1).getArgument(4));
        assertEquals("PROCESSING", calls.get(2).getArgument(4));
        assertTrue(((String) calls.get(1).getArgument(0)).startsWith("UPDATE wms_outbound_waybill_video SET video_url2 = ?"));
    }

    @Test
    void snapshotAcceptsOnlyOneAffectedRowAndDoesNotMutateTheExpectedSnapshot() {
        WaybillVideoRecord row = new WaybillVideoRecord();
        row.setId("same-row");
        row.setStatus("PENDING");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1, 0, 2);
        assertTrue(repository.claimSnapshot(row));
        assertFalse(repository.updateUrlSnapshot(row, CameraChannel.PANORAMA, "new-url"));
        assertFalse(repository.updateStatusSnapshot(row, WaybillVideoStatus.SUCCESS));
        assertEquals("PENDING", row.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(row.getVideoUrl1());
    }

    @Test
    void findByIdReadsOnlyTheRequestedRowWithoutUpdatingIt() {
        WaybillVideoRecord row = new WaybillVideoRecord();
        row.setId("failed-row");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("failed-row"))).thenReturn(List.of(row), List.of());
        org.junit.jupiter.api.Assertions.assertSame(row, repository.findById("failed-row"));
        org.junit.jupiter.api.Assertions.assertNull(repository.findById("failed-row"));
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).query(statement.capture(), any(RowMapper.class), eq("failed-row"));
        assertTrue(statement.getValue().endsWith(" WHERE id = ? LIMIT 1"));
        assertFalse(statement.getValue().contains("failed-row"));
        assertFalse(org.mockito.Mockito.mockingDetails(jdbcTemplate).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().equals("update")));
    }

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private IsupProperties properties;
    @Mock
    private IsupProperties.WaybillVideo waybillVideo;

    private WaybillVideoRepository repository;

    @BeforeEach
    void setUp() {
        when(properties.getWaybillVideo()).thenReturn(waybillVideo);
        when(waybillVideo.getTable()).thenReturn("wms_outbound_waybill_video");
        lenient().when(jdbcTemplate.getDataSource()).thenReturn(mock(javax.sql.DataSource.class));
        repository = new WaybillVideoRepository(jdbcTemplate, properties);
        org.springframework.test.util.ReflectionTestUtils.setField(repository, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void queryTimeoutIsAppliedOnlyToRepositoryTemplate() {
        IsupProperties settings = new IsupProperties();
        WaybillVideoRepository isolated = new WaybillVideoRepository(jdbcTemplate, settings);
        JdbcTemplate own = (JdbcTemplate) org.springframework.test.util.ReflectionTestUtils.getField(isolated, "jdbcTemplate");
        org.junit.jupiter.api.Assertions.assertNotSame(jdbcTemplate, own);
        assertEquals(5, own.getQueryTimeout());
        verify(jdbcTemplate, never()).setQueryTimeout(anyInt());
    }

    @Test
    @DisplayName("查询 PENDING 时保留两路 URL 已齐全但终态未回写的行")
    void findPendingSql() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), anyInt())).thenReturn(List.of());

        repository.findPending(5);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class),
                eq(WaybillVideoStatus.PENDING.name()), eq(5));
        String text = sql.getValue();
        assertTrue(text.contains("wms_outbound_waybill_video"));
        assertTrue(text.contains("channel_code IS NOT NULL"));
        assertTrue(text.contains("video_url1"));
        assertTrue(text.contains("video_url2"));
        String condition = text.substring(text.indexOf(" WHERE "));
        assertFalse(condition.contains("video_url1"));
        assertFalse(condition.contains("video_url2"));
        assertTrue(text.contains("LIMIT ?"));
    }

    @Test
    @DisplayName("抢占必须带 PENDING 条件，更新 1 行才算成功")
    void claimRequiresPending() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(repository.claim("id-1"));

        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(0);
        assertFalse(repository.claim("id-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(sql.capture(),
                eq(WaybillVideoStatus.PROCESSING.name()),
                eq(WaybillVideoRepository.UPDATE_BY),
                eq("id-1"),
                eq(WaybillVideoStatus.PENDING.name()));
        assertTrue(sql.getValue().contains("status = ?"));
    }

    @Test
    @DisplayName("通道 1 只更新 video_url1，通道 2 只更新 video_url2")
    void updateUrlUsesEnumColumn() {
        repository.updateUrl("id-1", CameraChannel.PANORAMA, "https://oss/ch1.mp4");
        repository.updateUrl("id-1", CameraChannel.DETAIL, "https://oss/ch2.mp4");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sql.capture(),
                anyString(), eq(WaybillVideoRepository.UPDATE_BY), eq("id-1"),
                eq(WaybillVideoStatus.PROCESSING.name()));
        List<String> statements = sql.getAllValues();
        assertTrue(statements.get(0).contains("video_url1"));
        assertFalse(statements.get(0).contains("video_url2"));
        assertTrue(statements.get(1).contains("video_url2"));
        assertTrue(statements.get(0).contains("status = ?"));
    }

    @Test
    @DisplayName("回写 URL 未命中 PROCESSING 时返回 false，不视为成功")
    void updateUrlNoRowReturnsFalse() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(0);
        assertFalse(repository.updateUrl("id-1", CameraChannel.PANORAMA, "https://oss/ch1.mp4"));
    }

    @Test
    @DisplayName("终态回写必须带 PROCESSING 条件")
    void updateStatusRequiresProcessing() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(1);
        assertTrue(repository.updateStatus("id-1", WaybillVideoStatus.SUCCESS));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                eq(WaybillVideoStatus.SUCCESS.name()),
                eq(WaybillVideoRepository.UPDATE_BY),
                eq("id-1"),
                eq(WaybillVideoStatus.PROCESSING.name()));
        assertTrue(sql.getValue().contains("status = ?"));
    }

    @Test
    @DisplayName("回收超时 PROCESSING：改回 PENDING，分钟数进 INTERVAL 白名单整数")
    void recoverStaleProcessingSql() {
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(3);

        assertEquals(3, repository.recoverStaleProcessing(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                eq(WaybillVideoStatus.PENDING.name()),
                eq(WaybillVideoRepository.UPDATE_BY),
                eq(WaybillVideoStatus.PROCESSING.name()));
        String text = sql.getValue();
        assertTrue(text.contains("DATE_SUB(NOW(), INTERVAL 10 MINUTE)"));
        assertTrue(text.contains("update_time IS NULL"));
        assertFalse(text.contains("INTERVAL ?"));
    }

    @Test
    @DisplayName("stale-processing-minutes<=0 时不回收")
    void recoverDisabled() {
        assertEquals(0, repository.recoverStaleProcessing(0));
        assertEquals(0, repository.recoverStaleProcessing(-3));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("FAILED 回收：改回 PENDING，LIMIT 限制单次行数，分钟数进 INTERVAL")
    void recoverFailedForRetrySql() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any())).thenReturn(2);

        assertEquals(2, repository.recoverFailedForRetry(30, 5));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                eq(WaybillVideoStatus.PENDING.name()),
                eq(WaybillVideoRepository.UPDATE_BY),
                eq(WaybillVideoStatus.FAILED.name()),
                eq(5));
        String text = sql.getValue();
        assertTrue(text.contains("DATE_SUB(NOW(), INTERVAL 30 MINUTE)"));
        assertTrue(text.contains("ORDER BY update_time ASC, id ASC"));
        assertTrue(text.contains("LIMIT ?"));
        assertFalse(text.contains("video_url1"));
        assertFalse(text.contains("video_url2"));
        assertFalse(text.contains("INTERVAL ?"));
    }

    @Test
    @DisplayName("failed-retry-after-minutes<=0 时不回收 FAILED")
    void recoverFailedDisabled() {
        assertEquals(0, repository.recoverFailedForRetry(0, 5));
        assertEquals(0, repository.recoverFailedForRetry(-1, 5));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void failedCandidatesUseStableOrderAndAdvancePastQuarantinedPages() {
        repository.findFailedForRetry(30, 5, 15);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq("FAILED"), eq(5), eq(15));
        assertTrue(sql.getValue().contains("ORDER BY update_time ASC, id ASC LIMIT ? OFFSET ?"));
    }

    @Test
    void retriesOnlyExplicitlyEligibleIdsWithStatusAndCooldownGuards() {
        repository.recoverFailedForRetry(30, List.of("eligible-1", "eligible-2"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("PENDING"), eq(WaybillVideoRepository.UPDATE_BY),
                eq("FAILED"), eq("eligible-1"), eq("eligible-2"), eq(2));
        assertTrue(sql.getValue().contains("id IN (?,?)"));
        assertTrue(sql.getValue().contains("ORDER BY update_time ASC, id ASC"));
        assertTrue(sql.getValue().contains("DATE_SUB(NOW(), INTERVAL 30 MINUTE)"));
        assertFalse(sql.getValue().contains("eligible-1"));
    }
}
