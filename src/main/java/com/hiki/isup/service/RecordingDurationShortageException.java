package com.hiki.isup.service;

import java.io.IOException;

/** 视频包时长不足；仅表示片段偏短，不推断缺少头部或尾部及其绝对时间。 */
public final class RecordingDurationShortageException extends IOException {
    public RecordingDurationShortageException(String message) { super(message); }
}
