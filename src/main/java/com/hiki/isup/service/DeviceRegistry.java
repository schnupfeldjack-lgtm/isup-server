package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.model.DeviceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线设备注册表（纯内存，不使用数据库）。
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    /** deviceId -> 设备信息 */
    private final ConcurrentHashMap<String, DeviceInfo> deviceMap = new ConcurrentHashMap<>();
    /** lUserID -> deviceId */
    private final ConcurrentHashMap<Integer, String> lUserMap = new ConcurrentHashMap<>();

    /**
     * 设备上线。
     */
    public DeviceInfo online(int lUserId, String deviceId, String deviceIp, String serialNumber, String deviceName) {
        DeviceInfo info = new DeviceInfo(deviceId, lUserId, deviceIp);
        info.setSerialNumber(serialNumber);
        info.setDeviceName(deviceName);

        DeviceInfo old = deviceMap.put(deviceId, info);
        if (old != null && old.getLUserId() != lUserId) {
            // 同一设备换了登录句柄（重注册），清理旧映射
            lUserMap.remove(old.getLUserId(), deviceId);
            log.info("设备重注册，清理旧 lUserID：deviceId={}, oldLUserID={}, newLUserID={}",
                    deviceId, old.getLUserId(), lUserId);
        }

        // 同一 lUserID 上原本挂着别的 deviceId：说明有两个设备（或同一设备配了多个平台中心）
        // 抢同一个登录句柄，会把彼此顶下线并留下残条目，必须清理并明确告警
        String conflict = lUserMap.put(lUserId, deviceId);
        if (conflict != null && !conflict.equals(deviceId)) {
            deviceMap.remove(conflict);
            log.warn("lUserID={} 上原有设备 {} 被 {} 顶替并移除。"
                            + "若反复出现，说明设备同时注册了多个平台中心（中心1/中心2 都启用），"
                            + "请只保留一个；SDK 日志可看到 Device Already Offline 与 DAS_REREGISTER 循环",
                    lUserId, conflict, deviceId);
        }

        log.info("设备上线：deviceId={}, lUserID={}, ip={}", deviceId, lUserId, deviceIp);
        return info;
    }

    /**
     * 设备下线。
     *
     * @return 下线的 deviceId，未找到返回 null
     */
    public String offline(int lUserId) {
        String deviceId = lUserMap.remove(lUserId);
        if (deviceId == null) {
            log.warn("设备下线回调：未找到 lUserID={} 对应的设备", lUserId);
            return null;
        }
        DeviceInfo removed = deviceMap.remove(deviceId);
        if (removed != null) {
            removed.setOnline(false);
        }
        log.info("设备下线：deviceId={}, lUserID={}", deviceId, lUserId);
        return deviceId;
    }

    public DeviceInfo get(String deviceId) {
        return deviceId == null ? null : deviceMap.get(deviceId);
    }

    /**
     * 根据 deviceId 取登录句柄，设备不在线时抛出明确异常。
     */
    public int requireLUserId(String deviceId, String operation) {
        DeviceInfo info = deviceMap.get(deviceId);
        if (info == null) {
            throw new BadRequestException(operation + " 失败：设备不在线，deviceId=" + deviceId
                    + "，请先确认录像机 ISUP 配置并等待设备注册成功（GET /api/v1/devices 查看在线设备）");
        }
        return info.getLUserId();
    }

    public List<DeviceInfo> list() {
        return new ArrayList<>(deviceMap.values());
    }

    public int onlineCount() {
        return deviceMap.size();
    }

    public void clear() {
        deviceMap.clear();
        lUserMap.clear();
    }
}
