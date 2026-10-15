package com.hiki.isup.service;

import com.hiki.isup.model.request.IsapiProxyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsapiProxyServiceTest {

    @Mock
    private DeviceIsapiClient isapiClient;

    @Test
    @DisplayName("代理把 POST 方法、请求体和请求级超时原样交给设备客户端")
    void forwardsPostAndTimeout() {
        when(isapiClient.request("dev-1", "POST", "/ISAPI/test", "<body/>", 4321))
                .thenReturn("<ok/>");
        IsapiProxyRequest request = new IsapiProxyRequest();
        request.setMethod("POST");
        request.setPath("/ISAPI/test");
        request.setBody("<body/>");
        request.setTimeoutMs(4321);

        String xml = new IsapiProxyService(isapiClient)
                .forward("dev-1", request).getXml();

        assertEquals("<ok/>", xml);
        verify(isapiClient).request("dev-1", "POST", "/ISAPI/test", "<body/>", 4321);
    }
}
