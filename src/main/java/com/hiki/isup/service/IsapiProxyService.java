package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.model.request.IsapiProxyRequest;
import com.hiki.isup.model.response.IsapiProxyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ISAPI 代理：把一条任意 ISAPI 请求发给指定设备并原样带回响应。
 *
 * <p>通过已注册设备的 ISUP 会话透传，不依赖设备 HTTP 地址或账密。
 * 对接设备新能力（叠加、抓图、参数）时先用它摸清这台设备实际支持的路径和真实报文结构，
 * 再决定怎么封装，不要凭型号猜。</p>
 */
@Service
public class IsapiProxyService {

    private static final Logger log = LoggerFactory.getLogger(IsapiProxyService.class);

    private final DeviceIsapiClient isapiClient;

    public IsapiProxyService(DeviceIsapiClient isapiClient) {
        this.isapiClient = isapiClient;
    }

    /**
     * 转发一条 ISAPI 请求。
     */
    public IsapiProxyResponse forward(String deviceId, IsapiProxyRequest request) {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty()) {
            throw new BadRequestException("deviceId 不能为空");
        }
        if (request == null) {
            throw new BadRequestException("请求体不能为空");
        }
        String method = request.getMethod() == null || request.getMethod().isBlank()
                ? "GET" : request.getMethod().trim().toUpperCase(Locale.ROOT);
        String path = request.getPath() == null ? "" : request.getPath().trim();
        if (path.isEmpty()) {
            throw new BadRequestException("path 不能为空，例如 /ISAPI/System/Video/inputs/channels/1/overlays");
        }
        if (!path.startsWith("/")) {
            throw new BadRequestException("path 必须以 / 开头，当前值=" + path);
        }
        if (!method.equals("GET") && !method.equals("PUT") && !method.equals("POST")) {
            throw new BadRequestException("method 只支持 GET / PUT / POST，当前值=" + method);
        }

        String body = method.equals("GET") ? null : request.getBody() == null ? "" : request.getBody();
        String xml = isapiClient.request(id, method, path, body, request.getTimeoutMs());

        IsapiProxyResponse response = new IsapiProxyResponse();
        response.setDeviceId(id);
        response.setRequestLine(method + " " + path);
        response.setXml(xml);
        response.setResponseLength(xml.getBytes(StandardCharsets.UTF_8).length);
        log.info("ISUP 透传完成：deviceId={}, requestLine={}, 响应长度={}",
                id, response.getRequestLine(), response.getResponseLength());
        return response;
    }
}
