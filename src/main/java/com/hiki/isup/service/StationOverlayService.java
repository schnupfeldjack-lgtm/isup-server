package com.hiki.isup.service;

import com.hiki.isup.exception.BadRequestException;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.model.CameraChannel;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.request.OverlayTextRequest;
import com.hiki.isup.model.request.OverlayRecipient;
import com.hiki.isup.model.response.OverlayTextResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按工位名下发单号。{@code 打包台1} 会写到 {@code 打包台1全景} 和 {@code 打包台1细节}。
 *
 * <p>两路分别执行，缺少通道或任一路失败时汇总原因返回调用方。</p>
 */
@Service
public class StationOverlayService {

    private static final Logger log = LoggerFactory.getLogger(StationOverlayService.class);

    private final ChannelService channelService;
    private final OverlayService overlayService;
    private final ConcurrentHashMap<StationKey, StationLock> stationLocks = new ConcurrentHashMap<>();

    public StationOverlayService(ChannelService channelService, OverlayService overlayService) {
        this.channelService = channelService;
        this.overlayService = overlayService;
    }

    /**
     * 把单号盖到该工位已匹配上的每一路画面。空单号则这两路只留「快递单号：」。
     */
    public void apply(String deviceId, String channelCode, String waybillNo) {
        apply(deviceId, channelCode, waybillNo, null, null);
    }

    public void apply(String deviceId, String channelCode, String waybillNo,
                      OverlayRecipient recipient, String expectedWaybillNo) {
        apply(deviceId, channelCode, waybillNo, recipient, expectedWaybillNo, null);
    }

    public void apply(String deviceId, String channelCode, String waybillNo,
                      OverlayRecipient recipient, String expectedWaybillNo, String platformOrderNo) {
        String id = deviceId == null ? "" : deviceId.trim();
        String code = channelCode == null ? "" : channelCode.trim();
        if (id.isEmpty() || code.isEmpty() || code.equalsIgnoreCase("undefined") || code.equalsIgnoreCase("null")) {
            throw new BadRequestException("deviceId 和有效的 channelCode 工位名不能为空");
        }
        StationKey key = new StationKey(id, code);
        // 引用数包含等待者，只有最后一个调用退出才回收，避免等待者与新调用使用两把锁。
        StationLock station = stationLocks.compute(key, (ignored, current) -> {
            StationLock retained = current == null ? new StationLock() : current;
            retained.users++;
            return retained;
        });
        station.lock.lock();
        try {
            applyLocked(id, code, waybillNo, recipient, expectedWaybillNo, platformOrderNo);
        } finally {
            station.lock.unlock();
            stationLocks.compute(key, (ignored, current) -> --current.users == 0 ? null : current);
        }
    }

    private void applyLocked(String id, String code, String waybillNo,
                             OverlayRecipient recipient, String expectedWaybillNo, String platformOrderNo) {
        String number = waybillNo == null ? "" : waybillNo.trim();
        List<DeviceChannel> channels = channelService.listChannels(id);
        if (channels == null) {
            channels = List.of();
        }
        ChannelStations.Match matched = ChannelStations.match(channels, code);
        OverlayTextRequest request = OverlayTextRequest.external(number);
        request.setRecipient(recipient);
        request.setExpectedWaybillNo(expectedWaybillNo);
        request.setPlatformOrderNo(platformOrderNo);
        List<String> errors = new ArrayList<>();
        write(id, code, matched, CameraChannel.PANORAMA, request, errors);
        write(id, code, matched, CameraChannel.DETAIL, request, errors);
        if (!errors.isEmpty()) {
            throw IsupException.failed("下发工位字幕", id, null, null,
                    "channelCode=" + code + "；" + String.join("；", errors));
        }
    }

    private void write(String deviceId, String channelCode, ChannelStations.Match matched,
                       CameraChannel role, OverlayTextRequest request, List<String> errors) {
        String error = matched.errorOf(role);
        if (error != null) {
            errors.add(error);
            log.warn("工位通道未写入：deviceId={}, channelCode={}, 原因={}", deviceId, channelCode, error);
            return;
        }
        int channel = matched.channelOf(role);
        try {
            OverlayTextResponse result = overlayService.apply(deviceId, channel, request);
            if (result != null && result.isSuperseded()) {
                return;
            }
            if (result == null || !result.isApplied()) {
                errors.add("channel=" + channel + " 未确认写入：" + (result == null ? "响应为空"
                        : "statusCode=" + result.getDeviceStatusCode() + "，" + result.getDeviceStatusString()));
                log.warn("叠加单号未写上画面：deviceId={}, channelCode={}, channel={}, waybillNo={}",
                        deviceId, channelCode, channel, request.getWaybillNo());
            }
        } catch (RuntimeException e) {
            errors.add("channel=" + channel + "：" + e.getMessage());
            log.warn("叠加单号失败：deviceId={}, channelCode={}, channel={}, 原因={}",
                    deviceId, channelCode, channel, e.toString());
        }
    }

    private record StationKey(String deviceId, String channelCode) {
    }

    private static final class StationLock {
        // 按排队顺序执行完整工位操作；上游没有序号时，无法推断业务上的新旧顺序。
        private final ReentrantLock lock = new ReentrantLock(true);
        private int users;
    }
}
