package com.hiki.isup.service;

import java.time.DateTimeException;
import java.time.LocalDateTime;

/**
 * 从海康 PS 描述符里读绝对时间。
 *
 * <p>布局与 SystemTransform 的 {@code ParseHikStreamDescriptor} 一致：
 * 描述符第 2、3 字节是 {@code HK}，其后按位打包年月日时分秒毫秒。
 * 持续记录窗口内的首末帧，用于验证成片时间覆盖。</p>
 */
public final class HikStreamClock {

    private static final int DESCRIPTOR_LENGTH = 12;
    private static final int TAIL_KEEP = DESCRIPTOR_LENGTH - 1;

    private final byte[] tail = new byte[TAIL_KEEP];
    private int tailLength;
    private volatile LocalDateTime firstFrame;
    private volatile LocalDateTime lastFrame;
    private boolean sawDescriptor;
    private volatile boolean reliableOrigin = true;

    public LocalDateTime firstFrame() {
        return firstFrame;
    }

    public boolean hasFirstFrame() {
        return firstFrame != null;
    }

    public LocalDateTime lastFrame() {
        return lastFrame;
    }

    public boolean hasReliableOrigin() {
        return firstFrame != null && reliableOrigin;
    }

    /** 测试直接记下首帧时间。 */
    void note(LocalDateTime time) {
        this.firstFrame = time;
        this.lastFrame = time;
        this.sawDescriptor = true;
        this.reliableOrigin = true;
        this.tailLength = 0;
    }

    public synchronized void accept(byte[] data, int length, LocalDateTime notBefore, LocalDateTime notAfter) {
        if (data == null || length <= 0 || notBefore == null || notAfter == null) {
            return;
        }
        int available = Math.min(length, data.length);
        byte[] window = data;
        int windowLength = available;
        if (tailLength > 0) {
            window = new byte[tailLength + available];
            System.arraycopy(tail, 0, window, 0, tailLength);
            System.arraycopy(data, 0, window, tailLength, available);
            windowLength = tailLength + available;
        }
        for (int start = 0; start <= windowLength - DESCRIPTOR_LENGTH; start++) {
            LocalDateTime hit = parseDescriptor(window, start);
            if (hit == null) {
                continue;
            }
            if (!sawDescriptor) {
                sawDescriptor = true;
                // 预取原片仍包含窗口外首帧，不能以后续命中点冒充 MP4 的零点。
                reliableOrigin = !hit.isBefore(notBefore) && !hit.isAfter(notAfter);
            }
            if (hit.isBefore(notBefore) || hit.isAfter(notAfter)) {
                continue;
            }
            if (firstFrame == null) {
                firstFrame = hit;
            }
            if (lastFrame == null || hit.isAfter(lastFrame)) {
                lastFrame = hit;
            }
        }
        int keep = Math.min(TAIL_KEEP, windowLength);
        System.arraycopy(window, windowLength - keep, tail, 0, keep);
        tailLength = keep;
    }

    static LocalDateTime find(byte[] data, int length, LocalDateTime notBefore, LocalDateTime notAfter) {
        int limit = Math.min(length, data.length) - DESCRIPTOR_LENGTH;
        for (int start = 0; start <= limit; start++) {
            if (data[start + 2] != 0x48 || data[start + 3] != 0x4B) {
                continue;
            }
            LocalDateTime time = parseDescriptor(data, start);
            if (time == null || time.isBefore(notBefore) || time.isAfter(notAfter)) {
                continue;
            }
            return time;
        }
        return null;
    }

    static LocalDateTime parseDescriptor(byte[] data, int start) {
        if (start < 0 || start + DESCRIPTOR_LENGTH > data.length) {
            return null;
        }
        if (data[start + 2] != 0x48 || data[start + 3] != 0x4B) {
            return null;
        }
        int year = (data[start + 6] & 0xff) + 2000;
        int month = (data[start + 7] & 0xff) >>> 4;
        int day = ((data[start + 7] & 0x0f) << 1) | ((data[start + 8] & 0xff) >>> 7);
        int hour = ((data[start + 8] & 0xff) >>> 2) & 0x1f;
        int minute = ((data[start + 8] & 0x03) << 4) | ((data[start + 9] & 0xff) >>> 4);
        int second = ((data[start + 9] & 0x0f) << 2) | ((data[start + 10] & 0xff) >>> 6);
        int millis = (((data[start + 10] & 0xff) << 5) + ((data[start + 11] & 0xff) >>> 3)) & 0x3ff;
        if (month < 1 || month > 12 || day < 1 || day > 31
                || hour > 23 || minute > 59 || second > 59 || millis > 999) {
            return null;
        }
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
