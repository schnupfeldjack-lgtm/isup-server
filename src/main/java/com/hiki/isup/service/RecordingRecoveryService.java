package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.StationChannel;
import com.hiki.isup.model.DeviceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 仅在监督进程已配置、无业务在途且设备字幕已清空时，恢复无法释放的 native 会话。 */
@Service
public class RecordingRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(RecordingRecoveryService.class);
    private final SessionManager sessions;
    private final WaybillVideoSyncService sync;
    private final OverlayService overlays;
    private final DeviceRegistry devices;
    private final ChannelService channels;
    private final DeviceIsapiClient isapi;
    private final RecordingRecoveryGate gate;
    private final RecordingRecoveryBudget budget;
    private final Runnable exit;
    @Value("${isup.recording-recovery.enabled:false}")
    private boolean enabled;
    @Value("${isup.recording-recovery.cleanup-age-seconds:600}")
    private long cleanupAgeSeconds = 600;
    private volatile String state = "IDLE";
    private volatile String lastError;
    private volatile boolean exitRequested;

    @Autowired
    public RecordingRecoveryService(SessionManager sessions, WaybillVideoSyncService sync,
                                    OverlayService overlays, DeviceRegistry devices, ChannelService channels,
                                    DeviceIsapiClient isapi, RecordingRecoveryGate gate, IsupProperties properties) {
        this(sessions, sync, overlays, devices, channels, isapi, gate,
                new RecordingRecoveryBudget(Path.of(properties.getMediaDir(), "recording-recovery.state"),
                        System::currentTimeMillis),
                new LinuxRecoveryExit());
    }

    RecordingRecoveryService(SessionManager sessions, WaybillVideoSyncService sync, OverlayService overlays,
                             DeviceRegistry devices, ChannelService channels, DeviceIsapiClient isapi,
                             RecordingRecoveryGate gate, RecordingRecoveryBudget budget, Runnable exit) {
        this.sessions = sessions;
        this.sync = sync;
        this.overlays = overlays;
        this.devices = devices;
        this.channels = channels;
        this.isapi = isapi;
        this.gate = gate;
        this.budget = budget;
        this.exit = exit;
    }

    @Scheduled(fixedDelay = 15000)
    public synchronized void checkRecovery() {
        if (!enabled || exitRequested) return;
        long minimumAge = TimeUnit.SECONDS.toMillis(Math.max(300, cleanupAgeSeconds));
        if (!sessions.canRecoverProcess(minimumAge)) {
            state = "WAIT_CLEANUP_OR_ACTIVE_WORK";
            return;
        }
        try {
            // Resolve before admission closes or the durable budget is consumed.
            if (exit instanceof LinuxRecoveryExit processExit) processExit.prepare();
            if (!budget.available()) {
                state = "COOLDOWN_OR_LIMIT";
                log.error("录像 native 清理持续失败，受持久化恢复冷却或次数限制，请检查 recordings/diagnostics");
                return;
            }
            long activityVersion = gate.activityVersion();
            state = "CHECKING_DEVICE";
            // SDK 读回可能缓慢，不能持有业务入口门禁；其间出现业务请求则丢弃本次读回。
            DeviceInfo connection = idlePackingConnection();
            if (connection == null) {
                state = "WAIT_PACKING_CLEAR";
                return;
            }
            state = "WAIT_IDLE";
            boolean prepared = gate.tryQuiesce(30, activityVersion, () -> overlays.tryQuiesceForRecovery(
                    () -> sync.prepareProcessRecovery(minimumAge, () -> {
                        if (devices.get(connection.getDeviceId()) != connection || !connection.isOnline()) return false;
                        try {
                            return budget.reserve();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })));
            if (!prepared) return;
            exitRequested = true;
            state = "RESTART_REQUESTED";
            log.error("录像 native 清理无法完成，已确认无在途业务、字幕已清空并记录恢复预算；退出码75交由监督进程重启");
            exit.run();
        } catch (IOException | RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            state = "RECOVERY_BLOCKED";
            log.error("录像恢复预检失败，保留原始清理状态与重试台账：{}", lastError);
        }
    }

    private DeviceInfo idlePackingConnection() {
        List<String> online = devices.onlineDeviceIds();
        if (online.size() != 1) return null;
        String device = online.getFirst();
        DeviceInfo connection = devices.get(device);
        if (connection == null || !connection.isOnline()) return null;
        List<StationChannel> stations = ChannelStations.group(channels.listChannels(device));
        if (stations.isEmpty()) return null;
        for (StationChannel station : stations) {
            if (station.getPanoramaChannel() == null || station.getDetailChannel() == null) return null;
            for (int channel : List.of(station.getPanoramaChannel(), station.getDetailChannel())) {
                String xml;
                try {
                    xml = isapi.get(device, "/ISAPI/System/Video/inputs/channels/" + channel + "/overlays", 2000);
                } catch (RuntimeException primaryPathUnavailable) {
                    xml = isapi.get(device, "/ISAPI/ContentMgmt/InputProxy/channels/" + channel + "/overlays", 2000);
                }
                if (!OverlayService.packingOverlayCleared(xml)) return null;
            }
        }
        return connection;
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("cleanupAgeSeconds", Math.max(300, cleanupAgeSeconds));
        result.put("state", state);
        result.put("exitRequested", exitRequested);
        result.put("lastError", lastError);
        return result;
    }
}
