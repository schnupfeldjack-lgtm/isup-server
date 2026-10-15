package com.hiki.isup.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HikStreamClockTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 9, 23, 11, 28, 39, 200_000_000);

    @Test
    void preservesEveryMillisecondBit() {
        for (int millis : new int[]{0, 255, 256, 300, 511, 512, 767, 768, 999}) {
            LocalDateTime time = TIME.withNano(millis * 1_000_000);
            assertEquals(time, HikStreamClock.parseDescriptor(descriptor(time), 0));
        }
    }

    @Test
    @DisplayName("HK 描述符解出与打包相同的绝对时间")
    void parsesPackedDescriptor() {
        byte[] packet = new byte[4];
        packet = concat(packet, descriptor(TIME));

        assertEquals(TIME, HikStreamClock.find(packet, packet.length, TIME.minusSeconds(5), TIME.plusSeconds(5)));
    }

    @Test
    @DisplayName("描述符跨两个包时仍能读到首帧")
    void findsTimeSplitAcrossPackets() {
        byte[] raw = descriptor(TIME);
        HikStreamClock clock = new HikStreamClock();
        LocalDateTime begin = TIME.minusSeconds(8);
        LocalDateTime end = TIME.plusMinutes(1);
        clock.accept(slice(raw, 0, 5), 5, begin, end);
        assertNull(clock.firstFrame());
        clock.accept(slice(raw, 5, raw.length), raw.length - 5, begin, end);

        assertEquals(TIME, clock.firstFrame());
    }

    @Test
    @DisplayName("窗口外的时间不当作首帧")
    void ignoresTimeOutsideWindow() {
        byte[] raw = descriptor(TIME);
        assertNull(HikStreamClock.find(raw, raw.length, TIME.plusSeconds(2), TIME.plusSeconds(10)));
    }

    @Test
    @DisplayName("后续包推进末帧，乱序描述符不改变首帧或回退末帧")
    void tracksLatestTimeAcrossPacketsWithoutChangingFirstFrame() {
        HikStreamClock clock = new HikStreamClock();
        LocalDateTime end = TIME.plusMinutes(1);
        byte[] first = descriptor(TIME);
        byte[] latest = descriptor(TIME.plusSeconds(40));
        byte[] older = descriptor(TIME.minusSeconds(2));
        clock.accept(first, first.length, TIME.minusSeconds(8), end);
        clock.accept(latest, latest.length, TIME.minusSeconds(8), end);
        clock.accept(older, older.length, TIME.minusSeconds(8), end);

        assertEquals(TIME, clock.firstFrame());
        assertEquals(TIME.plusSeconds(40), lastFrame(clock));
    }

    @Test
    @DisplayName("同一包的多个描述符全部参与末帧判断")
    void scansAllDescriptorsWithinOnePacket() {
        HikStreamClock clock = new HikStreamClock();
        byte[] packet = concat(descriptor(TIME), descriptor(TIME.plusSeconds(50)));
        packet = concat(packet, descriptor(TIME.plusSeconds(20)));
        clock.accept(packet, packet.length, TIME.minusSeconds(8), TIME.plusMinutes(1));

        assertEquals(TIME, clock.firstFrame());
        assertEquals(TIME.plusSeconds(50), lastFrame(clock));
    }

    @Test
    @DisplayName("已找到首帧后，跨包末帧描述符在每个拆分位置都不丢失")
    void retainsSplitDescriptorsAfterFirstFrameWasFound() {
        byte[] latest = descriptor(TIME.plusSeconds(50));
        for (int split = 1; split < latest.length; split++) {
            HikStreamClock clock = new HikStreamClock();
            byte[] first = concat(descriptor(TIME), slice(latest, 0, split));
            byte[] second = slice(latest, split, latest.length);
            clock.accept(first, first.length, TIME.minusSeconds(8), TIME.plusMinutes(1));
            assertEquals(TIME, lastFrame(clock), "完整末帧尚未到达，拆分位置=" + split);
            clock.accept(second, second.length, TIME.minusSeconds(8), TIME.plusMinutes(1));

            assertEquals(TIME, clock.firstFrame());
            assertEquals(TIME.plusSeconds(50), lastFrame(clock), "拆分位置=" + split);
        }
    }

    @Test
    @DisplayName("后续窗口外的描述符不能伪造结束覆盖")
    void ignoresLaterDescriptorsOutsideRequestedWindow() {
        HikStreamClock clock = new HikStreamClock();
        byte[] first = descriptor(TIME);
        byte[] outside = descriptor(TIME.plusMinutes(2));
        clock.accept(first, first.length, TIME.minusSeconds(8), TIME.plusMinutes(1));
        clock.accept(outside, outside.length, TIME.minusSeconds(8), TIME.plusMinutes(1));

        assertEquals(TIME, clock.firstFrame());
        assertEquals(TIME, lastFrame(clock));
    }

    @Test
    @DisplayName("已知首帧后仍可接收后续末帧时间")
    void continuesTrackingAfterFirstFrameWasNoted() {
        HikStreamClock clock = new HikStreamClock();
        clock.note(TIME);
        assertEquals(TIME, lastFrame(clock));
        byte[] latest = descriptor(TIME.plusSeconds(50));
        clock.accept(latest, latest.length, TIME.minusSeconds(8), TIME.plusMinutes(1));

        assertEquals(TIME, clock.firstFrame());
        assertEquals(TIME.plusSeconds(50), lastFrame(clock));
    }

    private static LocalDateTime lastFrame(HikStreamClock clock) {
        return (LocalDateTime) assertDoesNotThrow(
                () -> HikStreamClock.class.getMethod("lastFrame").invoke(clock),
                "下载时钟必须公开末帧时间");
    }

    private static byte[] descriptor(LocalDateTime time) {
        int year = time.getYear() - 2000;
        int month = time.getMonthValue();
        int day = time.getDayOfMonth();
        int hour = time.getHour();
        int minute = time.getMinute();
        int second = time.getSecond();
        int millis = time.getNano() / 1_000_000;
        byte[] data = new byte[12];
        data[2] = 0x48;
        data[3] = 0x4B;
        data[6] = (byte) year;
        data[7] = (byte) ((month << 4) | ((day >> 1) & 0x0f));
        data[8] = (byte) (((day & 1) << 7) | ((hour & 0x1f) << 2) | ((minute >> 4) & 0x03));
        data[9] = (byte) (((minute & 0x0f) << 4) | ((second >> 2) & 0x0f));
        data[10] = (byte) (((second & 0x03) << 6) | ((millis >> 5) & 0x3f));
        data[11] = (byte) ((millis & 0x1f) << 3);
        return data;
    }

    private static byte[] slice(byte[] data, int from, int to) {
        byte[] part = new byte[to - from];
        System.arraycopy(data, from, part, 0, part.length);
        return part;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] all = new byte[left.length + right.length];
        System.arraycopy(left, 0, all, 0, left.length);
        System.arraycopy(right, 0, all, left.length, right.length);
        return all;
    }
}
