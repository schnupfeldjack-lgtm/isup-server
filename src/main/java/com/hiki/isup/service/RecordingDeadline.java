package com.hiki.isup.service;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** 同一行录像的单调时钟预算；超时永远不能变成 SDK 中的 0（无限等待）。 */
final class RecordingDeadline {
    private RecordingDeadline() { }

    static long afterSeconds(int seconds) {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, seconds));
    }

    static long remainingMillis(long deadlineNanos) throws SocketTimeoutException {
        if (deadlineNanos == Long.MAX_VALUE) return Integer.MAX_VALUE;
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("整行录像处理预算已耗尽");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    static void check(long deadlineNanos) throws SocketTimeoutException {
        remainingMillis(deadlineNanos);
    }
}
