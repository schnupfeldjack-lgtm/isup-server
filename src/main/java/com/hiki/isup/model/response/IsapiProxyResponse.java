package com.hiki.isup.model.response;

/**
 * 通用 ISAPI 代理响应。
 */
public class IsapiProxyResponse {

    private String deviceId;

    /** 实际发出的请求行（方法 + ISAPI 路径） */
    private String requestLine;

    /** 解码后 XML 文本按 UTF-8 编码的字节数 */
    private int responseLength;

    /** 设备响应 XML 原文 */
    private String xml;

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getRequestLine() {
        return requestLine;
    }

    public void setRequestLine(String requestLine) {
        this.requestLine = requestLine;
    }

    public int getResponseLength() {
        return responseLength;
    }

    public void setResponseLength(int responseLength) {
        this.responseLength = responseLength;
    }

    public String getXml() {
        return xml;
    }

    public void setXml(String xml) {
        this.xml = xml;
    }
}
