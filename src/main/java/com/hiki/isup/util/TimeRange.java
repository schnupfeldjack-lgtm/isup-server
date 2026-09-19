package com.hiki.isup.util;

import com.hiki.isup.exception.BadRequestException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 回放 / 下载时间范围解析与校验。
 *
 * <p>时间按录像机设备本地时间处理，服务端只做格式与合理性校验。</p>
 */
public final class TimeRange {

    /** 单次回放 / 下载允许的最大时间跨度（小时） */
    public static final int MAX_RANGE_HOURS = 24;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SPACE_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private TimeRange() {
    }

    /**
     * 解析时间字符串，支持：
     * <ul>
     *   <li>yyyy-MM-dd'T'HH:mm:ss</li>
     *   <li>yyyy-MM-dd HH:mm:ss</li>
     *   <li>yyyy-MM-dd'T'HH:mm</li>
     *   <li>yyyy-MM-dd HH:mm</li>
     * </ul>
     */
    public static LocalDateTime parse(String text, String fieldName) {
        if (text == null || text.isBlank()) {
            throw new BadRequestException(fieldName + " 不能为空");
        }
        String trimmed = text.trim();
        DateTimeFormatter[] formatters = trimmed.contains("T") || trimmed.contains("t")
                ? new DateTimeFormatter[]{ISO, ISO_MINUTE}
                : new DateTimeFormatter[]{SPACE, SPACE_MINUTE};
        for (DateTimeFormatter formatter : formatters) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        throw new BadRequestException(fieldName + " 格式非法，应为 yyyy-MM-dd'T'HH:mm:ss 或 yyyy-MM-dd HH:mm:ss，实际值：" + text);
    }

    /**
     * 校验时间范围：起始时间必须早于结束时间，且跨度不超过 {@link #MAX_RANGE_HOURS} 小时。
     *
     * @return 长度为 2 的数组：[start, end]
     */
    public static LocalDateTime[] validate(String startText, String endText) {
        LocalDateTime start = parse(startText, "startTime");
        LocalDateTime end = parse(endText, "endTime");
        if (!start.isBefore(end)) {
            throw new BadRequestException("startTime 必须早于 endTime：startTime=" + start + ", endTime=" + end);
        }
        if (start.plusHours(MAX_RANGE_HOURS).isBefore(end)) {
            throw new BadRequestException("时间跨度过大，单次最多支持 " + MAX_RANGE_HOURS + " 小时：startTime="
                    + start + ", endTime=" + end);
        }
        return new LocalDateTime[]{start, end};
    }
}
