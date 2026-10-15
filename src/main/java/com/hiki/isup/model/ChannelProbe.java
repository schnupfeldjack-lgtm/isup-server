package com.hiki.isup.model;

/**
 * 单通道探测结果。
 *
 * <p>{@code hasRecording} 表示探测时段内确实收到了录像数据；
 * 收不到数据不代表通道一定不存在，也可能是该时段没有录像。</p>
 */
public class ChannelProbe {

    /** 通道号 */
    private int channel;

    /** 探测时段内是否收到录像数据 */
    private boolean hasRecording;

    /** SDK 错误码，仅在调用失败时有值 */
    private Integer errorCode;

    /** 结果说明 */
    private String message;

    /** 通道名：本地配置里有就带上，便于人工核对 */
    private String name;

    public ChannelProbe() {
    }

    public ChannelProbe(int channel, boolean hasRecording, Integer errorCode, String message) {
        this.channel = channel;
        this.hasRecording = hasRecording;
        this.errorCode = errorCode;
        this.message = message;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public boolean isHasRecording() {
        return hasRecording;
    }

    public void setHasRecording(boolean hasRecording) {
        this.hasRecording = hasRecording;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }
}
