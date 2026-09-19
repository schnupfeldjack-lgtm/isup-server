package com.hiki.isup.model;

/**
 * 会话类型。预览使用 8003 端口监听的通道，回放与下载共用 8004 端口监听的通道，
 * 三者不能混用 session。
 */
public enum SessionType {
    /** 实时预览 */
    PREVIEW,
    /** 按时间段回放 */
    PLAYBACK,
    /** 按时间段下载录像 */
    DOWNLOAD
}
