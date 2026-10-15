package com.hiki.isup.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;

import static com.hiki.isup.service.PsRecordingTimelineTest.anchor;
import static com.hiki.isup.service.PsRecordingTimelineTest.join;
import static org.junit.jupiter.api.Assertions.*;

class PsRecordingTimelineClockJitterTest {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 15, 9, 24, 40, 701_000_000);

    @Test
    void realSeventeenAnchorSequenceRemainsContinuousWithinOriginalMappingTolerance() throws Exception {
        // 2026-10-15 失败原片提取的 HK 元数据；PTS 每次严格增加 4 秒，无 DTS 回退。
        String[] wallTimes = {
                "09:24:40.701", "09:24:44.651", "09:24:48.681", "09:24:52.641",
                "09:24:56.691", "09:25:00.661", "09:25:04.691", "09:25:08.641",
                "09:25:12.671", "09:25:16.692", "09:25:20.651", "09:25:24.661",
                "09:25:28.651", "09:25:32.731", "09:25:36.631", "09:25:40.651", "09:25:44.681"
        };
        byte[][] packets = new byte[wallTimes.length][];
        for (int i = 0; i < wallTimes.length; i++) {
            packets[i] = anchor(LocalDateTime.parse("2026-10-15T" + wallTimes[i]), 900_000L + i * 360_000L);
        }
        var timeline = parse(packets);
        var cut = assertDoesNotThrow(() -> timeline.plan(LocalDateTime.parse("2026-10-15T09:24:48"),
                LocalDateTime.parse("2026-10-15T09:25:34")));
        assertEquals(17, cut.anchors().size());
        assertEquals(17.299, cut.startPtsSeconds(), 1e-9);
        assertEquals(63.299, cut.endPtsSeconds(), 1e-9);
    }

    @Test
    void oppositeEightyMillisecondErrorsStillShareOneFixedOrigin() throws Exception {
        var timeline = parse(anchor(BASE, 900_000),
                anchor(BASE.plusNanos(3_920_000_000L), 1_260_000),
                anchor(BASE.plusNanos(8_080_000_000L), 1_620_000),
                anchor(BASE.plusSeconds(12), 1_980_000));
        var cut = assertDoesNotThrow(() -> timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(11)));
        assertEquals(4, cut.anchors().size());
        assertEquals(11, cut.startPtsSeconds(), 1e-9);
        assertEquals(21, cut.endPtsSeconds(), 1e-9);
    }

    @Test
    void smallSuccessiveDriftCannotAccumulateBeyondOriginalEightyMilliseconds() throws Exception {
        var timeline = parse(anchor(BASE, 900_000),
                anchor(BASE.plusNanos(4_040_000_000L), 1_260_000),
                anchor(BASE.plusNanos(8_080_000_000L), 1_620_000),
                anchor(BASE.plusNanos(12_120_000_000L), 1_980_000),
                anchor(BASE.plusNanos(16_160_000_000L), 2_340_000));
        assertThrows(RecordingValidationException.class,
                () -> timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(15)));
    }

    @Test
    void smallWallClockRollbackStillBreaksEvenWithinOriginalMappingTolerance() throws Exception {
        var timeline = parse(anchor(BASE, 900_000),
                anchor(BASE.plusNanos(80_000_000), 907_200),
                anchor(BASE.plusNanos(40_000_000), 910_800),
                anchor(BASE.plusNanos(160_000_000), 914_400));
        assertThrows(RecordingValidationException.class,
                () -> timeline.plan(BASE.plusNanos(20_000_000), BASE.plusNanos(140_000_000)));
    }

    @Test
    void oneMillisecondBeyondOriginToleranceStillBreaksWithSmallAdjacentError() throws Exception {
        var timeline = parse(anchor(BASE, 900_000),
                anchor(BASE.plusNanos(4_040_000_000L), 1_260_000),
                anchor(BASE.plusNanos(8_081_000_000L), 1_620_000),
                anchor(BASE.plusNanos(12_081_000_000L), 1_980_000));
        assertThrows(RecordingValidationException.class,
                () -> timeline.plan(BASE.plusSeconds(1), BASE.plusSeconds(11)));
    }

    private static PsRecordingTimeline parse(byte[]... packets) throws Exception {
        return PsRecordingTimeline.parse(new ByteArrayInputStream(join(packets)));
    }
}
