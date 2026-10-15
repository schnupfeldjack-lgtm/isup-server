package com.hiki.isup.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackWindowCutTest {

    @Test
    @DisplayName("从首帧钟点裁到目标窗口")
    void cutsFromFirstFrameToWindow() {
        LocalDateTime first = LocalDateTime.of(2026, 9, 23, 11, 28, 39, 200_000_000);
        LocalDateTime start = LocalDateTime.of(2026, 9, 23, 11, 28, 42);
        LocalDateTime end = LocalDateTime.of(2026, 9, 23, 11, 29, 3);

        PlaybackWindowCut cut = PlaybackWindowCut.plan(first, start, end);

        assertEquals(2.8, cut.offsetSeconds(), 0.0001);
        assertEquals(21.0, cut.durationSeconds(), 0.0001);
    }

    @Test
    @DisplayName("首帧已经晚于目标起点时拒绝裁切")
    void rejectsLateFirstFrame() {
        LocalDateTime first = LocalDateTime.of(2026, 9, 23, 11, 28, 43);
        LocalDateTime start = LocalDateTime.of(2026, 9, 23, 11, 28, 42);
        LocalDateTime end = LocalDateTime.of(2026, 9, 23, 11, 29, 3);

        assertThrows(IllegalArgumentException.class, () -> PlaybackWindowCut.plan(first, start, end));
    }
}
