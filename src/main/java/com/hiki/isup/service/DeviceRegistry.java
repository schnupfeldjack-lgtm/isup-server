package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.model.DeviceInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 在线设备注册表（纯内存，不使用数据库）。
 *
 * <p>设备端若同时启用了「中心1」「中心2」（或同一台配了多个 DeviceID），两个注册会抢
 * 同一个登录句柄互相顶下线，SDK 侧表现为 Device Already Offline + DAS_REREGISTER 反复循环。
 * 此时若收到下线回调就立刻删条目，在线列表会高频空掉、预览也会被反复掐断。
 * 因此下线走「宽限期」：先标 online=false 保留条目，超时未重连才真正移除（并停掉它的会话）。</p>
 */
@Component
public class DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistry.class);

    /** 无配置时的默认宽限期（秒） */
    private static final int DEFAULT_GRACE_SEC = 20;

    /** deviceId -> 设备信息（含宽限期内等待重连的条目） */
    private final ConcurrentHashMap<String, DeviceInfo> deviceMap = new ConcurrentHashMap<>();
    /** lUserID -> deviceId */
    private final ConcurrentHashMap<Integer, String> lUserMap = new ConcurrentHashMap<>();
    /** deviceId -> 延迟移除任务 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();
    /** 当前 online=true 的设备数，避免 health 等接口每次全表扫描 */
    private final AtomicInteger onlineCounter = new AtomicInteger();

    private final int graceSec;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "device-grace-remover");
        t.setDaemon(true);
        return t;
    });

    public DeviceRegistry() {
        this(DEFAULT_GRACE_SEC);
    }

    /** @param offlineGraceSec 下线宽限期秒数，0 表示下线即移除 */
    public DeviceRegistry(int offlineGraceSec) {
        this.graceSec = Math.max(0, offlineGraceSec);
    }

    @Autowired
    public DeviceRegistry(IsupProperties properties) {
        this(properties == null ? DEFAULT_GRACE_SEC : properties.getDeviceOfflineGraceSec());
    }

    /**
     * 设备上线。
     */
    public DeviceInfo online(int lUserId, String deviceId, String deviceIp, String serialNumber, String deviceName) {
        DeviceInfo info = new DeviceInfo(deviceId, lUserId, deviceIp);
        info.setSerialNumber(serialNumber);
        info.setDeviceName(deviceName);

        DeviceInfo existing = deviceMap.get(deviceId);
        boolean wasOnline = existing != null && existing.isOnline();
        if (existing != null && !existing.isOnline()) {
            // 宽限期内重连：沿用原上线时间，避免列表里的上线时间跟着抖动乱跳。
            // 这里不比对 lUserID——抖动重注册时 SDK 基本都会换一个登录句柄
            info.setOnlineTime(existing.getOnlineTime());
            log.info("设备在宽限期内重连：deviceId={}, lUserID={}（原 {}）", deviceId, lUserId, existing.getLUserId());
        }
        cancelRemoval(deviceId);

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
            DeviceInfo displaced = deviceMap.remove(conflict);
            if (displaced != null && displaced.isOnline()) {
                onlineCounter.decrementAndGet();
            }
            cancelRemoval(conflict);
            log.warn("lUserID={} 上原有设备 {} 被 {} 顶替并移除。"
                            + "若反复出现，说明设备同时注册了多个平台中心（中心1/中心2 都启用），"
                            + "请在设备端只保留一个中心（两个中心用同一个 DeviceID）；"
                            + "SDK 日志可看到 Device Already Offline 与 DAS_REREGISTER 循环",
                    lUserId, conflict, deviceId);
        }

        if (!wasOnline) {
            onlineCounter.incrementAndGet();
        }
        log.debug("设备上线：deviceId={}, lUserID={}, ip={}", deviceId, lUserId, deviceIp);
        return info;
    }

    /**
     * 设备下线。
     *
     * @return 下线的 deviceId，未找到返回 null
     */
    public String offline(int lUserId) {
        return offline(lUserId, null);
    }

    /**
     * 设备下线。
     *
     * @param onGraceExpired 宽限期结束仍未重连时执行（用于停掉该设备的会话）；可为 null
     * @return 下线的 deviceId，未找到返回 null
     */
    public String offline(int lUserId, Consumer<String> onGraceExpired) {
        return offlineConnection(lUserId, onGraceExpired == null ? null
                : connection -> onGraceExpired.accept(connection.getDeviceId()));
    }

    /** 清理回调携带本次离线的连接快照，不能只凭可复用的设备 ID 或登录句柄清理会话。 */
    public String offlineConnection(int lUserId, Consumer<DeviceInfo> onGraceExpired) {
        String deviceId = lUserMap.remove(lUserId);
        if (deviceId == null) {
            log.warn("设备下线回调：未找到 lUserID={} 对应的设备", lUserId);
            return null;
        }
        DeviceInfo info = deviceMap.get(deviceId);
        if (info == null) {
            return deviceId;
        }
        // 该 lUserID 已不属于这个设备（设备重注册后旧句柄的下线回调），不能误删当前在线的条目
        if (info.getLUserId() != lUserId) {
            log.info("收到旧 lUserID={} 的下线回调，设备 {} 已用新句柄在线，忽略", lUserId, deviceId);
            return null;
        }

        if (info.isOnline()) {
            onlineCounter.decrementAndGet();
        }
        info.setOnline(false);
        info.setOfflineTime(LocalDateTime.now());

        if (graceSec <= 0) {
            if (!deviceMap.remove(deviceId, info)) {
                return deviceId;
            }
            log.info("设备下线：deviceId={}, lUserID={}", deviceId, lUserId);
            if (onGraceExpired != null) {
                onGraceExpired.accept(info);
            }
            return deviceId;
        }

        DeviceInfo expectedOffline = info;
        ScheduledFuture<?> future = scheduler.schedule(
                () -> expireOffline(deviceId, expectedOffline, onGraceExpired == null ? null
                        : ignored -> onGraceExpired.accept(expectedOffline)), graceSec, TimeUnit.SECONDS);

        ScheduledFuture<?> prev = pendingRemovals.put(deviceId, future);
        if (prev != null) {
            prev.cancel(false);
        }
        log.info("设备下线回调：deviceId={}, lUserID={}，宽限 {}s 等待重连", deviceId, lUserId, graceSec);
        return deviceId;
    }

    /**
     * 只清理由本次掉线安排的那条记录。任务已经开始后即使 cancel(false) 返回，
     * 重连也会用新对象替换映射，条件删除便不会误删新连接。
     */
    void expireOffline(String deviceId, DeviceInfo expectedOffline, Consumer<String> onGraceExpired) {
        try {
            if (expectedOffline == null || expectedOffline.isOnline()
                    || !deviceMap.remove(deviceId, expectedOffline)) {
                return;
            }
            pendingRemovals.remove(deviceId);
            log.info("设备掉线超过宽限期 {}s，已移出列表：deviceId={}", graceSec, deviceId);
            if (onGraceExpired != null) {
                onGraceExpired.accept(deviceId);
            }
        } catch (Exception e) {
            log.error("执行设备宽限期清理失败：deviceId={}", deviceId, e);
        }
    }

    public DeviceInfo get(String deviceId) {
        return deviceId == null ? null : deviceMap.get(deviceId);
    }

    /**
     * 根据 deviceId 取登录句柄，设备不在线时抛出明确异常。
     *
     * <p>宽限期内（online=false 但条目还在）仍然放行：抖动场景下设备大概率马上重连，
     * 直接拒绝会让预览在抖动瞬间就失败；真的连不上会由会话超时兜底。</p>
     */
    public int requireLUserId(String deviceId, String operation) {
        return requireConnection(deviceId, operation).getLUserId();
    }

    /** 登录句柄与连接身份必须从同一快照取得，重连即使复用句柄也会产生新快照。 */
    public DeviceInfo requireConnection(String deviceId, String operation) {
        DeviceInfo info = deviceMap.get(deviceId);
        if (info == null) {
            throw new BadRequestException(operation + " 失败：设备不在线，deviceId=" + deviceId
                    + "，请先确认录像机 ISUP 配置并等待设备注册成功（GET /api/v1/devices 查看在线设备）");
        }
        if (!info.isOnline()) {
            log.warn("{}：设备 {} 处于掉线宽限期内，仍尝试取流（设备端上下线抖动会这样）", operation, deviceId);
        }
        return info;
    }

    /**
     * 列表：包含宽限期内等待重连的条目（online=false），避免抖动时列表整个空掉。
     */
    public List<DeviceInfo> list() {
        return new ArrayList<>(deviceMap.values());
    }

    /**
     * 当前在线的 deviceId 列表（不含宽限期内等待重连的）。
     * 供同步任务等只需在线设备 ID 的调用方使用，避免先全量拷贝 DeviceInfo 再逐个过滤。
     */
    public List<String> onlineDeviceIds() {
        List<String> ids = new ArrayList<>();
        for (DeviceInfo info : deviceMap.values()) {
            if (info != null && info.isOnline()
                    && info.getDeviceId() != null && !info.getDeviceId().isBlank()) {
                ids.add(info.getDeviceId());
            }
        }
        return ids;
    }

    /** 真正在线的数量（不含宽限期内等待重连的） */
    public int onlineCount() {
        return onlineCounter.get();
    }

    public void clear() {
        pendingRemovals.values().forEach(f -> f.cancel(false));
        pendingRemovals.clear();
        deviceMap.clear();
        lUserMap.clear();
        onlineCounter.set(0);
    }

    private void cancelRemoval(String deviceId) {
        ScheduledFuture<?> future = pendingRemovals.remove(deviceId);
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
