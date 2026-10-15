package com.hiki.isup.model;

/**
 * 出库录像摄像头通道。表字段与通道号一一对应，禁止用魔法数字。
 */
public enum CameraChannel {

    /** 全景，写入 {@code video_url1} */
    PANORAMA(1, "video_url1"),

    /** 细节，写入 {@code video_url2} */
    DETAIL(2, "video_url2");

    private final int code;
    private final String urlColumn;

    CameraChannel(int code, String urlColumn) {
        this.code = code;
        this.urlColumn = urlColumn;
    }

    public int getCode() {
        return code;
    }

    /**
     * 该通道对应的表列名，仅用于白名单拼接 UPDATE，禁止把外部输入当列名。
     */
    public String getUrlColumn() {
        return urlColumn;
    }

    public static CameraChannel fromCode(int code) {
        for (CameraChannel channel : values()) {
            if (channel.code == code) {
                return channel;
            }
        }
        throw new IllegalArgumentException("不支持的摄像头通道: " + code + "，仅允许 1(全景) 或 2(细节)");
    }
}
