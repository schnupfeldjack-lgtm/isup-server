package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.util.XmlCharset;
import com.sun.jna.Memory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** 通过设备注册会话透传 ISAPI，不依赖设备 IP 或 HTTP 认证。 */
@Service
public class DeviceIsapiClient {

    private static final int OUT_BUFFER_SIZE = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    @Value("${isup.isapi.timeout-ms:${isup.device-auth.timeout-ms:5000}}")
    private int timeoutMs = DEFAULT_TIMEOUT_MS;

    private final IsupService isupService;
    private final DeviceRegistry deviceRegistry;

    public DeviceIsapiClient(IsupService isupService, DeviceRegistry deviceRegistry) {
        this.isupService = isupService;
        this.deviceRegistry = deviceRegistry;
    }

    public String get(String deviceId, String path) {
        return get(deviceId, path, null);
    }

    public String get(String deviceId, String path, Integer timeoutMs) {
        return request(deviceId, "GET", path, null, timeoutMs);
    }

    public String put(String deviceId, String path, String body) {
        return put(deviceId, path, body, null);
    }

    public String put(String deviceId, String path, String body, Integer timeoutMs) {
        return request(deviceId, "PUT", path, body, timeoutMs);
    }

    public String request(String deviceId, String method, String path, String body, Integer timeoutMs) {
        String id = deviceId == null ? "" : deviceId.trim();
        if (id.isEmpty()) {
            throw new BadRequestException("deviceId 不能为空");
        }
        String verb = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!verb.equals("GET") && !verb.equals("PUT") && !verb.equals("POST")) {
            throw new BadRequestException("ISAPI method 只支持 GET / PUT / POST");
        }
        if (path == null || !path.startsWith("/ISAPI/")
                || path.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new BadRequestException("path 必须是 /ISAPI/ 开头且不含空白或控制字符的设备路径");
        }
        isupService.requireReady("NET_ECMS_ISAPIPassThrough");
        int userId = deviceRegistry.requireLUserId(id, "ISAPI 透传");
        HCISUPCMS cms = isupService.cms();
        String requestLine = verb + " " + path;
        byte[] urlBytes = requestLine.getBytes(StandardCharsets.UTF_8);
        byte[] bodyBytes = verb.equals("GET") || body == null ? new byte[0] : body.getBytes(XmlCharset.ofXml(body));
        try (Memory urlBuffer = new Memory(urlBytes.length + 1L);
             Memory inBuffer = new Memory(bodyBytes.length + 1L);
             Memory outBuffer = new Memory(OUT_BUFFER_SIZE)) {
            urlBuffer.clear();
            inBuffer.clear();
            outBuffer.clear();
            urlBuffer.write(0, urlBytes, 0, urlBytes.length);
            if (bodyBytes.length > 0) {
                inBuffer.write(0, bodyBytes, 0, bodyBytes.length);
            }
            HCISUPCMS.NET_EHOME_PTXML_PARAM param = new HCISUPCMS.NET_EHOME_PTXML_PARAM();
            param.pRequestUrl = urlBuffer;
            param.dwRequestUrlLen = urlBytes.length;
            param.pInBuffer = inBuffer;
            param.dwInSize = bodyBytes.length;
            param.pOutBuffer = outBuffer;
            param.dwOutSize = OUT_BUFFER_SIZE;
            param.dwRecvTimeOut = effectiveTimeout(this.timeoutMs, timeoutMs);
            param.write();
            // 写请求只调用一次；超时或响应异常时结果可能不确定，不自动重发。
            if (!cms.NET_ECMS_ISAPIPassThrough(userId, param)) {
                throw IsupException.sdkFailed("ISAPI 透传", id, null, null,
                        cms.NET_ECMS_GetLastError(), requestLine);
            }
            param.read();
            // SDK 版本可能通过任一字段返回长度；不得把零 returnedXMLLen 当成无响应。
            long outSize = Integer.toUnsignedLong(param.dwOutSize);
            long returnedSize = Integer.toUnsignedLong(param.dwReturnedXMLLen);
            if (outSize > OUT_BUFFER_SIZE || returnedSize > OUT_BUFFER_SIZE) {
                throw IsupException.failed("ISAPI 透传", id, null, null,
                        requestLine + " 响应超过缓冲区容量：dwOutSize=" + outSize + ", dwReturnedXMLLen=" + returnedSize);
            }
            int length = (int) (returnedSize > 0 ? returnedSize : outSize);
            if (length == 0) {
                throw IsupException.failed("ISAPI 透传", id, null, null, requestLine + " 响应长度为零");
            }
            byte[] data = outBuffer.getByteArray(0, length);
            int textLength = 0;
            while (textLength < data.length && data[textLength] != 0) {
                textLength++;
            }
            data = Arrays.copyOf(data, textLength);
            String xml = new String(data, XmlCharset.ofBytes(data)).trim();
            if (xml.isEmpty()) {
                throw IsupException.failed("ISAPI 透传", id, null, null, requestLine + " 响应内容为空");
            }
            return xml;
        }
    }

    static int effectiveTimeout(int configuredTimeoutMs, Integer requestTimeoutMs) {
        int value = requestTimeoutMs == null ? configuredTimeoutMs : requestTimeoutMs;
        return Math.max(1000, Math.min(value, 60000));
    }
}
