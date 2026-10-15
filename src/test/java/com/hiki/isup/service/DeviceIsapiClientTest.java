package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.sdk.HCISUPCMS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceIsapiClientTest {
    @Mock private IsupService isupService;
    @Mock private DeviceRegistry registry;
    @Mock private HCISUPCMS cms;
    private DeviceIsapiClient client;

    @BeforeEach
    void setUp() {
        client = new DeviceIsapiClient(isupService, registry);
    }

    private void ready() {
        when(registry.requireLUserId("DLWH", "ISAPI 透传")).thenReturn(7);
        when(isupService.cms()).thenReturn(cms);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST"})
    void sendsMethodBodyAndTimeoutThroughRegisteredSession(String method) {
        ready();
        String body = "<?xml version=\"1.0\" encoding=\"GBK\"?><displayText>快递单号：YLUS0778515392</displayText>";
        byte[] expected = body.getBytes(Charset.forName("GBK"));
        when(cms.NET_ECMS_ISAPIPassThrough(eq(7), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM p = call.getArgument(1);
            p.read();
            assertEquals(method + " /ISAPI/test", new String(p.pRequestUrl.getByteArray(0, p.dwRequestUrlLen), StandardCharsets.UTF_8));
            assertEquals(expected.length, p.dwInSize);
            assertArrayEquals(expected, p.pInBuffer.getByteArray(0, p.dwInSize));
            assertEquals(4321, p.dwRecvTimeOut);
            respond(p, "<ResponseStatus><statusCode>1</statusCode></ResponseStatus>".getBytes(StandardCharsets.UTF_8), false);
            return true;
        });
        assertTrue(client.request(" DLWH ", method, "/ISAPI/test", body, 4321).contains("<statusCode>1</statusCode>"));
        verify(cms, times(1)).NET_ECMS_ISAPIPassThrough(eq(7), any());
        verify(registry, never()).get(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsEitherLengthFieldAndDecodesChinese(boolean returnedLength) {
        ready();
        String xml = "<?xml version=\"1.0\" encoding=\"GB2312\"?><name>打包台1全景</name>";
        when(cms.NET_ECMS_ISAPIPassThrough(eq(7), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM p = call.getArgument(1);
            assertEquals(0, p.dwInSize);
            assertEquals(5000, p.dwRecvTimeOut);
            respond(p, xml.getBytes(Charset.forName("GB2312")), returnedLength);
            return true;
        });
        assertEquals(xml, client.get("DLWH", "/ISAPI/test"));
    }

    @Test
    void sdkFailurePreservesCodeAndNeverResendsWrite() {
        ready();
        when(cms.NET_ECMS_ISAPIPassThrough(eq(7), any())).thenReturn(false);
        when(cms.NET_ECMS_GetLastError()).thenReturn(23);
        IsupException error = assertThrows(IsupException.class,
                () -> client.put("DLWH", "/ISAPI/test", "<body/>"));
        assertEquals(23, error.getErrorCode());
        assertTrue(error.getMessage().contains("PUT /ISAPI/test"));
        verify(cms, times(1)).NET_ECMS_ISAPIPassThrough(eq(7), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65537})
    void rejectsInvalidLengthWithoutRepeatingWrite(int size) {
        ready();
        when(cms.NET_ECMS_ISAPIPassThrough(eq(7), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM p = call.getArgument(1);
            p.dwOutSize = size;
            p.write();
            return true;
        });
        assertThrows(IsupException.class, () -> client.put("DLWH", "/ISAPI/test", "<body/>"));
        verify(cms, times(1)).NET_ECMS_ISAPIPassThrough(eq(7), any());
    }

    @Test
    void capacityWithoutResponseIsNotSuccess() {
        ready();
        when(cms.NET_ECMS_ISAPIPassThrough(eq(7), any())).thenReturn(true);
        assertThrows(IsupException.class, () -> client.get("DLWH", "/ISAPI/test"));
    }

    @Test
    void nulTerminatorDoesNotLeakUnusedBuffer() {
        ready();
        when(cms.NET_ECMS_ISAPIPassThrough(eq(7), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM p = call.getArgument(1);
            byte[] xml = "<ok/>".getBytes(StandardCharsets.UTF_8);
            p.pOutBuffer.write(0, xml, 0, xml.length);
            return true;
        });
        assertEquals("<ok/>", client.get("DLWH", "/ISAPI/test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.0.2.1/ISAPI/test", "//192.0.2.1/test", "/ISAPI/test GET", "/ISAPI/test\r\nPUT /x"})
    void invalidPathNeverReachesSdk(String path) {
        assertThrows(BadRequestException.class, () -> client.get("DLWH", path));
        verifyNoInteractions(cms, registry, isupService);
    }

    @Test
    void clampsTimeout() {
        assertEquals(5000, DeviceIsapiClient.effectiveTimeout(5000, null));
        assertEquals(1000, DeviceIsapiClient.effectiveTimeout(5000, 1));
        assertEquals(60000, DeviceIsapiClient.effectiveTimeout(5000, 999999));
    }

    @Test
    void configuredTimeoutAppliesWhenRequestDoesNotOverride() {
        ready();
        org.springframework.test.util.ReflectionTestUtils.setField(client, "timeoutMs", 12000);
        when(cms.NET_ECMS_ISAPIPassThrough(eq(7), any())).thenAnswer(call -> {
            HCISUPCMS.NET_EHOME_PTXML_PARAM p = call.getArgument(1);
            assertEquals(12000, p.dwRecvTimeOut);
            respond(p, "<ok/>".getBytes(StandardCharsets.UTF_8), false);
            return true;
        });
        assertEquals("<ok/>", client.get("DLWH", "/ISAPI/test"));
    }

    private static void respond(HCISUPCMS.NET_EHOME_PTXML_PARAM p, byte[] xml, boolean returnedLength) {
        p.pOutBuffer.write(0, xml, 0, xml.length);
        if (returnedLength) {
            p.dwReturnedXMLLen = xml.length;
        } else {
            p.dwOutSize = xml.length;
            p.dwReturnedXMLLen = 0;
        }
        p.write();
    }
}
