package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.model.DeviceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线设备注册表测试。
 */
class DeviceRegistryTest {

    private DeviceRegistry registry;

    /** 宽限期设 0：下线即移除，测的是不含抖动的常规行为 */
    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(0);
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
    @DisplayName("掉线宽限期内条目保留为「重连中」，超时后才真正移除，且只在真移除时停会话")
    void offlineGracePeriod() throws Exception {
        DeviceRegistry grace = new DeviceRegistry(1);
        List<String> stopped = new ArrayList<>();

        grace.online(1001, "GW9806925", "192.168.22.211", "SN-A", null);
        assertEquals("GW9806925", grace.offline(1001, stopped::add));

        // 宽限期内：还在列表里，但标记为不在线
        assertNotNull(grace.get("GW9806925"), "抖动时不该立刻从列表消失");
        assertFalse(grace.get("GW9806925").isOnline());
        assertEquals(0, grace.onlineCount(), "在线数只统计真在线的");
        assertTrue(stopped.isEmpty(), "宽限期内不能急着停会话，否则抖一次断一次");

        // 超时未重连：真正移除，并触发会话停止
        Thread.sleep(1500);
        assertNull(grace.get("GW9806925"));
        assertEquals(List.of("GW9806925"), stopped);
    }

    @Test
    @DisplayName("宽限期内重连：取消移除、保住原上线时间")
    void reconnectWithinGracePeriod() throws Exception {
        DeviceRegistry grace = new DeviceRegistry(1);
        List<String> stopped = new ArrayList<>();

        grace.online(1001, "GW9806925", "192.168.22.211", "SN-A", null);
        LocalDateTime firstOnline = grace.get("GW9806925").getOnlineTime();
        grace.offline(1001, stopped::add);
        grace.online(1002, "GW9806925", "192.168.22.211", "SN-A", null);

        Thread.sleep(1500);
        assertNotNull(grace.get("GW9806925"), "重连后不能被延迟任务误删");
        assertTrue(grace.get("GW9806925").isOnline());
        assertEquals(firstOnline, grace.get("GW9806925").getOnlineTime(), "上线时间不该跟着抖动乱跳");
        assertEquals(1002, grace.get("GW9806925").getLUserId());
        assertTrue(stopped.isEmpty(), "重连成功就不该停会话");
    }

    @Test
    @DisplayName("已经开始执行的旧宽限期任务不能删除重连后的新设备")
    void staleGraceTaskCannotRemoveReconnectedDevice() {
        DeviceRegistry grace = new DeviceRegistry(30);
        List<String> stopped = new ArrayList<>();
        try {
            grace.online(1001, "GW9806925", "192.168.22.211", "SN-A", null);
            grace.offline(1001, stopped::add);
            DeviceInfo expectedOffline = grace.get("GW9806925");

            grace.online(1002, "GW9806925", "192.168.22.212", "SN-A", null);
            grace.expireOffline("GW9806925", expectedOffline, stopped::add);

            assertNotNull(grace.get("GW9806925"));
            assertTrue(grace.get("GW9806925").isOnline());
            assertEquals(1002, grace.get("GW9806925").getLUserId());
            assertTrue(stopped.isEmpty(), "旧任务不能停止新连接上的会话");
        } finally {
            grace.shutdown();
        }
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
