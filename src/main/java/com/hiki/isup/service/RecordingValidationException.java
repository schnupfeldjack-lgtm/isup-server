package com.hiki.isup.service;

import java.io.IOException;

/** 已确定录像内容或时间映射不可信；重试相同内容不能恢复缺失的证据。 */
public final class RecordingValidationException extends IOException {
    public RecordingValidationException(String message) { super(message); }
    public RecordingValidationException(String message, Throwable cause) { super(message, cause); }
}
