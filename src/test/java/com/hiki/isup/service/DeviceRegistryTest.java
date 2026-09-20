package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.model.DeviceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线设备注册表测试。
 */
class DeviceRegistryTest {

    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry();
    }

    @Test
    @DisplayName("设备上线后可查询到 lUserID 与 IP")
    void online() {
        registry.online(1001, "GW4206623", "1.2.3.4", "SN123", "门口摄像机");

        DeviceInfo info = registry.get("GW4206623");
        assertNotNull(info);
        assertEquals(1001, info.getLUserId());
        assertEquals("1.2.3.4", info.getDeviceIp());
        assertTrue(info.isOnline());
        assertNotNull(info.getOnlineTime());
        assertEquals(1, registry.onlineCount());
    }

    @Test
    @DisplayName("设备下线后从注册表移除")
    void offline() {
        registry.online(1001, "GW4206623", "1.2.3.4", "SN123", null);
        String removed = registry.offline(1001);

        assertEquals("GW4206623", removed);
        assertNull(registry.get("GW4206623"));
        assertEquals(0, registry.onlineCount());
        assertNull(registry.offline(1001), "重复下线应安全返回 null");
    }

    @Test
    @DisplayName("设备重注册（lUserID 变化）时旧映射被清理")
    void reRegister() {
        registry.online(1001, "GW4206623", "1.2.3.4", "SN123", null);
        registry.online(1002, "GW4206623", "1.2.3.5", "SN123", null);

        assertEquals(1, registry.onlineCount());
        assertEquals(1002, registry.get("GW4206623").getLUserId());
        // 旧 lUserID 下线时不应误删新注册的同一设备
        assertNull(registry.offline(1001));
        assertNotNull(registry.get("GW4206623"));
    }

    @Test
    @DisplayName("同一 lUserID 被另一台设备顶替时，旧设备被移除（设备配了多个平台中心会触发）")
    void sameLUserIdTakenOverByAnotherDevice() {
        registry.online(0, "GW9806924", "192.168.22.211", "SN-A", null);
        // 同一台摄像头启用了中心1/中心2，两个 DeviceID 抢同一个 lUserID
        registry.online(0, "GW9806925", "192.168.22.211", "SN-A", null);

        assertEquals(1, registry.onlineCount(), "旧设备应被移除，避免留下永远在线的残条目");
        assertNull(registry.get("GW9806924"));
        assertNotNull(registry.get("GW9806925"));

        // 下线回调只带 lUserID，应精确移除当前占用的那个
        assertEquals("GW9806925", registry.offline(0));
        assertEquals(0, registry.onlineCount());
    }

    @Test
    @DisplayName("设备不在线时取 lUserID 抛出明确异常")
    void requireLUserId() {
        registry.online(1001, "GW4206623", "1.2.3.4", "SN123", null);

        assertEquals(1001, registry.requireLUserId("GW4206623", "预览"));

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> registry.requireLUserId("NOT_EXIST", "预览"));
        assertTrue(e.getMessage().contains("设备不在线"), e.getMessage());
        assertTrue(e.getMessage().contains("NOT_EXIST"), e.getMessage());
        assertTrue(e.getMessage().contains("预览"), e.getMessage());
    }
}
