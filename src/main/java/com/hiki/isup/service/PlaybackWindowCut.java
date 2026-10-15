package com.hiki.isup.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 用首帧绝对时间，把成片切到目标窗口。
 * 首帧晚于目标起点超过一帧（80 毫秒）时无法补画面。
 */
final class PlaybackWindowCut {

    private static final long LATE_FRAME_NANOS = 80_000_000L;

    private final double offsetSeconds;
    private final double durationSeconds;

    private PlaybackWindowCut(double offsetSeconds, double durationSeconds) {
        this.offsetSeconds = offsetSeconds;
        this.durationSeconds = durationSeconds;
    }

    static PlaybackWindowCut plan(LocalDateTime firstFrame, LocalDateTime desiredStart, LocalDateTime desiredEnd) {
        if (firstFrame == null || desiredStart == null || desiredEnd == null) {
            throw new IllegalArgumentException("裁切时间不完整");
        }
        if (!desiredEnd.isAfter(desiredStart)) {
            throw new IllegalArgumentException("目标结束时间必须晚于开始时间");
        }
        if (firstFrame.isAfter(desiredStart.plusNanos(LATE_FRAME_NANOS))) {
            throw new IllegalArgumentException("首帧 " + firstFrame + " 晚于目标起点 " + desiredStart);
        }
        LocalDateTime cutFrom = firstFrame.isAfter(desiredStart) ? firstFrame : desiredStart;
        double offset = nanos(firstFrame, cutFrom) / 1_000_000_000d;
        double duration = nanos(cutFrom, desiredEnd) / 1_000_000_000d;
        if (duration <= 0) {
            throw new IllegalArgumentException("裁切时长无效");
        }
        return new PlaybackWindowCut(offset, duration);
    }

    double offsetSeconds() {
        return offsetSeconds;
    }

    double durationSeconds() {
        return durationSeconds;
    }

    private static long nanos(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toNanos();
    }
}
