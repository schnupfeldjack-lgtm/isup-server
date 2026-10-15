package com.hiki.isup.callback;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.hiki.isup.service.DeviceRegistry;
import com.hiki.isup.service.SessionManager;
import com.sun.jna.Memory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.hiki.isup.sdk.NativeSdkLoader.copyTo;
import static com.hiki.isup.sdk.NativeSdkLoader.readString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceRegisterCallbackTest {

    @ParameterizedTest
    @CsvSource({
            "false, false, false", "false, false, true",
            "false, true, false", "false, true, true",
            "true, false, false", "true, false, true",
            "true, true, false", "true, true, true"
    })
    void onlineAlwaysReturnsKeepAliveAndGatesOptionalServers(
            boolean serverEnabled, boolean alarmEnabled, boolean pictureEnabled) {
        IsupProperties properties = new IsupProperties();
        properties.setPublicIp("203.0.113.10");
        properties.setKeepAliveSec(23);
        properties.setTimeOutCount(7);
        properties.setServerInfoEnabled(serverEnabled);
        properties.setAlarmServerEnabled(alarmEnabled);
        properties.setPictureServerEnabled(pictureEnabled);
        DeviceRegistry registry = mock(DeviceRegistry.class);
        DeviceRegisterCallback callback = new DeviceRegisterCallback(properties, registry,
                mock(SessionManager.class), mock(NativeSdkLoader.class));
        HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12 registration = new HCISUPCMS.NET_EHOME_DEV_REG_INFO_V12();
        copyTo(registration.struRegInfo.byDeviceID, "test-device");
        registration.write();
        HCISUPCMS.NET_EHOME_SERVER_INFO_V50 response = new HCISUPCMS.NET_EHOME_SERVER_INFO_V50();

        try (Memory buffer = new Memory(response.size())) {
            buffer.clear();
            assertTrue(callback.invoke(3, HCISUPCMS.EHOME_REGISTER_TYPE.ENUM_DEV_ON,
                    registration.getPointer(), registration.size(), buffer, response.size(), null));
            response.getPointer().write(0, buffer.getByteArray(0, response.size()), 0, response.size());
            response.read();

            assertEquals(response.size(), response.dwSize);
            assertEquals(23, response.dwKeepAliveSec);
            assertEquals(7, response.dwTimeOutCount);
            boolean alarm = serverEnabled && alarmEnabled;
            boolean picture = serverEnabled && pictureEnabled;
            assertEquals(alarm ? 1 : 0, response.dwAlarmServerType);
            assertEquals(alarm ? properties.getPublicIp() : "", readString(response.struTCPAlarmSever.szIP));
            assertEquals(alarm ? properties.getPublicIp() : "", readString(response.struUDPAlarmSever.szIP));
            assertEquals(alarm ? properties.getCms().getListenPort() : 0,
                    Short.toUnsignedInt(response.struTCPAlarmSever.wPort));
            assertEquals(alarm ? properties.getCms().getListenPort() : 0,
                    Short.toUnsignedInt(response.struUDPAlarmSever.wPort));
            assertEquals(picture ? 4 : 0, response.dwPicServerType);
            assertEquals(picture ? properties.getPublicIp() : "", readString(response.struPictureSever.szIP));
            assertEquals(picture ? 6011 : 0, Short.toUnsignedInt(response.struPictureSever.wPort));
            verify(registry).online(3, "test-device", "", "", "");
        }
    }
}
