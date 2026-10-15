package com.hiki.isup.service;

import java.io.IOException;
import java.time.LocalDateTime;

/** 结构与时轴连续，但本次下载的可信时间范围尚未覆盖请求；是否等待重试由业务时间决定。 */
public final class RecordingCoverageException extends IOException {
    private final LocalDateTime requestedStart;
    private final LocalDateTime requestedEnd;
    private final LocalDateTime availableStart;
    private final LocalDateTime availableEnd;

    public RecordingCoverageException(LocalDateTime requestedStart, LocalDateTime requestedEnd,
                                      LocalDateTime availableStart, LocalDateTime availableEnd) {
        super("录像连续时间范围覆盖不足：requested=" + requestedStart + ".." + requestedEnd
                + ", available=" + availableStart + ".." + availableEnd);
        this.requestedStart = requestedStart;
        this.requestedEnd = requestedEnd;
        this.availableStart = availableStart;
        this.availableEnd = availableEnd;
    }

    public LocalDateTime requestedStart() { return requestedStart; }
    public LocalDateTime requestedEnd() { return requestedEnd; }
    public LocalDateTime availableStart() { return availableStart; }
    public LocalDateTime availableEnd() { return availableEnd; }
}
