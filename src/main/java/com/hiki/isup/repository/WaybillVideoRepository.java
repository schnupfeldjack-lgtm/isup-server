package com.hiki.isup.repository;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.CameraChannel;
import com.hiki.isup.model.WaybillVideoRecord;
import com.hiki.isup.model.WaybillVideoStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code wms_outbound_waybill_video} 读写。列名只来自枚举白名单，禁止拼接外部输入。
 */
@Repository
public class WaybillVideoRepository {

    static final String UPDATE_BY = "isup-server";

    private static final RowMapper<WaybillVideoRecord> ROW_MAPPER = (rs, rowNum) -> {
        WaybillVideoRecord row = new WaybillVideoRecord();
        row.setId(rs.getString("id"));
        row.setOutboundWaybillId(rs.getString("outbound_waybill_id"));
        row.setWaybillId(rs.getString("waybill_id"));
        row.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
        row.setEndTime(toLocalDateTime(rs.getTimestamp("end_time")));
        row.setStatus(rs.getString("status"));
        row.setChannelCode(rs.getString("channel_code"));
        row.setVideoUrl1(rs.getString("video_url1"));
        row.setVideoUrl2(rs.getString("video_url2"));
        return row;
    };

    private final JdbcTemplate jdbcTemplate;
    private final String table;

    public WaybillVideoRepository(JdbcTemplate jdbcTemplate, IsupProperties properties) {
        this.jdbcTemplate = new JdbcTemplate(java.util.Objects.requireNonNull(jdbcTemplate.getDataSource()));
        this.jdbcTemplate.setQueryTimeout(Math.max(1, properties.getWaybillVideo().getQueryTimeoutSeconds()));
        this.table = properties.getWaybillVideo().getTable();
    }

    public List<WaybillVideoRecord> findPending(int limit) {
        String sql = "SELECT id, outbound_waybill_id, waybill_id, start_time, end_time, status, "
                + "channel_code, video_url1, video_url2 FROM " + table
                + " WHERE status = ? AND channel_code IS NOT NULL AND TRIM(channel_code) <> ''"
                + " ORDER BY create_time ASC, id ASC LIMIT ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, WaybillVideoStatus.PENDING.name(), limit);
    }

    /** 人工恢复前只读核对当前业务快照；不在此处领取或恢复任务。 */
    public WaybillVideoRecord findById(String id) {
        String sql = "SELECT id, outbound_waybill_id, waybill_id, start_time, end_time, status, "
                + "channel_code, video_url1, video_url2 FROM " + table + " WHERE id = ? LIMIT 1";
        List<WaybillVideoRecord> rows = jdbcTemplate.query(sql, ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 领取时同时锁定读到的业务区间、工位及已有地址，防止领取已经变更的记录。 */
    public boolean claimSnapshot(WaybillVideoRecord expected) {
        return updateSnapshot(expected, "status", WaybillVideoStatus.PROCESSING.name(), WaybillVideoStatus.PENDING);
    }

    /** 回写前验证原快照；调用方仅在返回 true 后更新自己的地址快照。 */
    public boolean updateUrlSnapshot(WaybillVideoRecord expected, CameraChannel channel, String url) {
        return updateSnapshot(expected, channel.getUrlColumn(), url, WaybillVideoStatus.PROCESSING);
    }

    public boolean updateStatusSnapshot(WaybillVideoRecord expected, WaybillVideoStatus status) {
        return updateSnapshot(expected, "status", status.name(), WaybillVideoStatus.PROCESSING);
    }

    private boolean updateSnapshot(WaybillVideoRecord expected, String column, String value, WaybillVideoStatus expectedStatus) {
        // column 只来自上面固定的 status 或 CameraChannel 枚举白名单。
        String sql = "UPDATE " + table + " SET " + column + " = ?, update_time = NOW(), update_by = ?"
                + " WHERE id = ? AND status = ? AND start_time <=> ? AND end_time <=> ?"
                + " AND channel_code <=> ? AND video_url1 <=> ? AND video_url2 <=> ?"
                + " AND waybill_id <=> ? AND outbound_waybill_id <=> ?";
        return jdbcTemplate.update(sql, value, UPDATE_BY, expected.getId(), expectedStatus.name(),
                toTimestamp(expected.getStartTime()), toTimestamp(expected.getEndTime()), expected.getChannelCode(),
                expected.getVideoUrl1(), expected.getVideoUrl2(), expected.getWaybillId(), expected.getOutboundWaybillId()) == 1;
    }

    /**
     * 把 PENDING 抢成 PROCESSING。返回 false 表示已被别人抢走或状态已变。
     */
    public boolean claim(String id) {
        String sql = "UPDATE " + table
                + " SET status = ?, update_time = NOW(), update_by = ? WHERE id = ? AND status = ?";
        int updated = jdbcTemplate.update(sql,
                WaybillVideoStatus.PROCESSING.name(), UPDATE_BY, id, WaybillVideoStatus.PENDING.name());
        return updated == 1;
    }

    public boolean updateUrl(String id, CameraChannel channel, String url) {
        String sql = "UPDATE " + table + " SET " + channel.getUrlColumn()
                + " = ?, update_time = NOW(), update_by = ? WHERE id = ? AND status = ?";
        int updated = jdbcTemplate.update(sql, url, UPDATE_BY, id, WaybillVideoStatus.PROCESSING.name());
        return updated == 1;
    }

    public boolean updateStatus(String id, WaybillVideoStatus status) {
        String sql = "UPDATE " + table
                + " SET status = ?, update_time = NOW(), update_by = ? WHERE id = ? AND status = ?";
        int updated = jdbcTemplate.update(sql, status.name(), UPDATE_BY, id,
                WaybillVideoStatus.PROCESSING.name());
        return updated == 1;
    }

    /**
     * 把卡住的 PROCESSING 改回 PENDING。{@code olderThanMinutes < 1} 时不执行。
     *
     * <p>分钟数来自配置，按整数写入 INTERVAL，不拼接外部字符串。</p>
     */
    public int recoverStaleProcessing(int olderThanMinutes) {
        if (olderThanMinutes < 1) {
            return 0;
        }
        int minutes = Math.min(olderThanMinutes, 7 * 24 * 60);
        String sql = "UPDATE " + table
                + " SET status = ?, update_time = NOW(), update_by = ?"
                + " WHERE status = ? AND (update_time IS NULL OR update_time < DATE_SUB(NOW(), INTERVAL "
                + minutes + " MINUTE))";
        return jdbcTemplate.update(sql,
                WaybillVideoStatus.PENDING.name(), UPDATE_BY, WaybillVideoStatus.PROCESSING.name());
    }

    /**
     * 把冷却期已过的 FAILED 改回 PENDING，供本轮 findPending 接着处理。
     * {@code olderThanMinutes < 1} 时不执行。单次最多 {@code limit} 行，避免一次扫改整张表。
     */
    public int recoverFailedForRetry(int olderThanMinutes, int limit) {
        if (olderThanMinutes < 1 || limit < 1) {
            return 0;
        }
        int minutes = Math.min(olderThanMinutes, 7 * 24 * 60);
        int maxRows = Math.min(limit, 100);
        String sql = "UPDATE " + table
                + " SET status = ?, update_time = NOW(), update_by = ?"
                + " WHERE status = ? AND channel_code IS NOT NULL AND TRIM(channel_code) <> ''"
                + " AND (update_time IS NULL OR update_time < DATE_SUB(NOW(), INTERVAL "
                + minutes + " MINUTE)) ORDER BY update_time ASC, id ASC LIMIT ?";
        return jdbcTemplate.update(sql,
                WaybillVideoStatus.PENDING.name(), UPDATE_BY, WaybillVideoStatus.FAILED.name(), maxRows);
    }

    /** 分页检查历史失败，永久隔离条目不会始终占据第一页。这里不修改任何行。 */
    public List<WaybillVideoRecord> findFailedForRetry(int olderThanMinutes, int limit, int offset) {
        if (olderThanMinutes < 1 || limit < 1) return List.of();
        int minutes = Math.min(olderThanMinutes, 7 * 24 * 60);
        String sql = "SELECT id, outbound_waybill_id, waybill_id, start_time, end_time, status, "
                + "channel_code, video_url1, video_url2 FROM " + table
                + " WHERE status = ? AND channel_code IS NOT NULL AND TRIM(channel_code) <> ''"
                + " AND (update_time IS NULL OR update_time < DATE_SUB(NOW(), INTERVAL "
                + minutes + " MINUTE)) ORDER BY update_time ASC, id ASC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, WaybillVideoStatus.FAILED.name(),
                Math.min(limit, 100), Math.max(0, offset));
    }

    /** 只回收通过本地耐久策略检查的候选，保持原 URL，并再次验证冷却期和 FAILED。 */
    public int recoverFailedForRetry(int olderThanMinutes, List<String> eligibleIds) {
        if (olderThanMinutes < 1 || eligibleIds == null || eligibleIds.isEmpty()) return 0;
        List<String> ids = eligibleIds.stream().distinct().limit(100).toList();
        int minutes = Math.min(olderThanMinutes, 7 * 24 * 60);
        String sql = "UPDATE " + table + " SET status = ?, update_time = NOW(), update_by = ?"
                + " WHERE status = ? AND id IN (" + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")"
                + " AND (update_time IS NULL OR update_time < DATE_SUB(NOW(), INTERVAL "
                + minutes + " MINUTE)) ORDER BY update_time ASC, id ASC LIMIT ?";
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(WaybillVideoStatus.PENDING.name());
        args.add(UPDATE_BY);
        args.add(WaybillVideoStatus.FAILED.name());
        args.addAll(ids);
        args.add(ids.size());
        return jdbcTemplate.update(sql, args.toArray());
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Timestamp toTimestamp(LocalDateTime time) {
        return time == null ? null : Timestamp.valueOf(time);
    }
}
