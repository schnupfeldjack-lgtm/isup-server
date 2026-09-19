package com.hiki.isup.model;

/**
 * 会话 / 下载任务状态机。
 *
 * <pre>
 * STARTING  --设备回连并推流--> RUNNING
 * STARTING  --超时/失败--------> FAILED
 * RUNNING   --客户端停止-------> STOPPED
 * RUNNING   --下载完成---------> COMPLETED
 * RUNNING   --异常/超时--------> FAILED
 * </pre>
 */
public enum SessionStatus {
    /** 已发起请求，等待设备回连推流 */
    STARTING,
    /** 正在接收码流 */
    RUNNING,
    /** 下载完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 被客户端主动停止 */
    STOPPED
}
