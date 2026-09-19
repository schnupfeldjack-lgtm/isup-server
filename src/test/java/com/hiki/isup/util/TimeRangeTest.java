package com.hiki.isup.util;

import com.hiki.isup.exception.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间范围解析与校验测试。
 */
class TimeRangeTest {

    @Test
    @DisplayName("解析带 T 的 ISO 格式")
    void parseIso() {
        LocalDateTime t = TimeRange.parse("2026-09-19T14:00:00", "startTime");
        assertEquals(2026, t.getYear());
        assertEquals(9, t.getMonthValue());
        assertEquals(19, t.getDayOfMonth());
        assertEquals(14, t.getHour());
        assertEquals(0, t.getMinute());
        assertEquals(0, t.getSecond());
    }

    @Test
    @DisplayName("解析空格分隔格式")
    void parseSpace() {
        LocalDateTime t = TimeRange.parse("2026-09-19 14:10:30", "endTime");
        assertEquals(14, t.getHour());
        assertEquals(10, t.getMinute());
        assertEquals(30, t.getSecond());
    }

    @Test
    @DisplayName("解析到分钟精度的格式")
    void parseMinutePrecision() {
        assertEquals(0, TimeRange.parse("2026-09-19T14:00", "startTime").getSecond());
        assertEquals(0, TimeRange.parse("2026-09-19 14:00", "startTime").getSecond());
    }

    @Test
    @DisplayName("空值与非法格式应被拒绝")
    void invalidFormat() {
        assertThrows(BadRequestException.class, () -> TimeRange.parse(null, "startTime"));
        assertThrows(BadRequestException.class, () -> TimeRange.parse("  ", "startTime"));
        assertThrows(BadRequestException.class, () -> TimeRange.parse("2026/09/19 14:00:00", "startTime"));
        assertThrows(BadRequestException.class, () -> TimeRange.parse("not-a-time", "startTime"));
    }

    @Test
    @DisplayName("正常时间范围校验通过")
    void validateOk() {
        LocalDateTime[] range = TimeRange.validate("2026-09-19T14:00:00", "2026-09-19T14:10:00");
        assertEquals(LocalDateTime.of(2026, 9, 19, 14, 0, 0), range[0]);
        assertEquals(LocalDateTime.of(2026, 9, 19, 14, 10, 0), range[1]);
    }

    @Test
    @DisplayName("起始时间必须早于结束时间")
    void startMustBeforeEnd() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> TimeRange.validate("2026-09-19T14:10:00", "2026-09-19T14:00:00"));
        assertTrue(e.getMessage().contains("startTime 必须早于 endTime"), e.getMessage());

        // 相等也不允许
        assertThrows(BadRequestException.class,
                () -> TimeRange.validate("2026-09-19T14:00:00", "2026-09-19T14:00:00"));
    }

    @Test
    @DisplayName("时间跨度过大应被拒绝")
    void rangeTooLarge() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> TimeRange.validate("2026-09-19T00:00:00", "2026-09-20T01:00:00"));
        assertTrue(e.getMessage().contains("时间跨度过大"), e.getMessage());
    }

    @Test
    @DisplayName("跨天但在允许范围内可以通过")
    void crossDayAllowed() {
        LocalDateTime[] range = TimeRange.validate("2026-09-19T23:00:00", "2026-09-20T01:00:00");
        assertEquals(23, range[0].getHour());
        assertEquals(1, range[1].getHour());
    }
}
