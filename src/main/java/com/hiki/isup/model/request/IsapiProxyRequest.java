package com.hiki.isup.model.request;

/**
 * 通用 ISAPI 代理请求。
 *
 * <p>用于探测设备支持的 ISAPI 地址与真实 XML 结构，也用于下发配置。
 * 路径要写 ISAPI 全路径（含 {@code /ISAPI/} 前缀）。</p>
 */
public class IsapiProxyRequest {

    /** HTTP 方法，默认 GET */
    private String method = "GET";

    /** ISAPI 路径，如 {@code /ISAPI/System/Video/inputs/channels/1/overlays} */
    private String path;

    /** 请求体 XML，PUT / POST 时填 */
    private String body;

    /** 接收超时（毫秒），不传用服务端默认值 */
    private Integer timeoutMs;

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
