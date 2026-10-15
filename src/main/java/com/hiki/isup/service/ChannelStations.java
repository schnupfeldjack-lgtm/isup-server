package com.hiki.isup.service;

import com.hiki.isup.model.CameraChannel;
import com.hiki.isup.model.DeviceChannel;
import com.hiki.isup.model.StationChannel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把设备通道名按「工位 + 全景/细节」收成打包台。
 *
 * <p>只认名称以「全景」或「细节」结尾的通道。{@code 打包台1全景} 与 {@code 打包台1细节}
 * 合成 {@code channelCode=打包台1}。没有这两后缀的名称（如 IPCamera 03）不进结果。</p>
 */
public final class ChannelStations {

    static final String PANORAMA_SUFFIX = "全景";
    static final String DETAIL_SUFFIX = "细节";

    private ChannelStations() {
    }

    public static List<StationChannel> group(List<DeviceChannel> channels) {
        Map<String, Acc> found = collect(channels);
        List<StationChannel> stations = new ArrayList<>();
        for (Acc acc : found.values()) {
            stations.add(new StationChannel(acc.code, acc.usablePanorama(), acc.usableDetail()));
        }
        return stations;
    }

    /**
     * 用表里的 {@code channel_code} 找这一工位的全景、细节通道号。
     * 缺边或同一边出现多次时，对应通道号为 null，并带上可直接打日志的原因。
     */
    public static Match match(List<DeviceChannel> channels, String channelCode) {
        String code = channelCode == null ? "" : channelCode.trim();
        Acc acc = collect(channels).get(code);
        if (acc == null) {
            return new Match(null, null,
                    missing(CameraChannel.PANORAMA, code),
                    missing(CameraChannel.DETAIL, code));
        }
        return new Match(acc.usablePanorama(), acc.usableDetail(), acc.panoramaError(), acc.detailError());
    }

    public static String missing(CameraChannel role, String channelCode) {
        return "未匹配到" + roleLabel(role) + "通道：" + channelCode + suffix(role);
    }

    public static String duplicate(CameraChannel role, String channelCode) {
        return roleLabel(role) + "通道不唯一：" + channelCode + suffix(role);
    }

    private static Map<String, Acc> collect(List<DeviceChannel> channels) {
        Map<String, Acc> found = new LinkedHashMap<>();
        if (channels == null) {
            return found;
        }
        for (DeviceChannel channel : channels) {
            if (channel == null) {
                continue;
            }
            Side side = split(channel.getName());
            if (side == null) {
                continue;
            }
            Acc acc = found.computeIfAbsent(side.code, Acc::new);
            acc.add(side.role, channel.getChannel());
        }
        return found;
    }

    private static Side split(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.endsWith(PANORAMA_SUFFIX)) {
            return side(trimmed, PANORAMA_SUFFIX, CameraChannel.PANORAMA);
        }
        if (trimmed.endsWith(DETAIL_SUFFIX)) {
            return side(trimmed, DETAIL_SUFFIX, CameraChannel.DETAIL);
        }
        return null;
    }

    private static Side side(String name, String suffix, CameraChannel role) {
        if (name.length() <= suffix.length()) {
            return null;
        }
        String code = name.substring(0, name.length() - suffix.length()).trim();
        if (code.isEmpty()) {
            return null;
        }
        return new Side(code, role);
    }

    private static String roleLabel(CameraChannel role) {
        return role == CameraChannel.PANORAMA ? PANORAMA_SUFFIX : DETAIL_SUFFIX;
    }

    private static String suffix(CameraChannel role) {
        return roleLabel(role);
    }

    private static final class Side {
        private final String code;
        private final CameraChannel role;

        private Side(String code, CameraChannel role) {
            this.code = code;
            this.role = role;
        }
    }

    private static final class Acc {
        private final String code;
        private int panoramaCount;
        private int detailCount;
        private int panoramaChannel;
        private int detailChannel;

        private Acc(String code) {
            this.code = code;
        }

        private void add(CameraChannel role, int channel) {
            if (role == CameraChannel.PANORAMA) {
                panoramaCount++;
                panoramaChannel = channel;
            } else {
                detailCount++;
                detailChannel = channel;
            }
        }

        private Integer usablePanorama() {
            return panoramaCount == 1 ? panoramaChannel : null;
        }

        private Integer usableDetail() {
            return detailCount == 1 ? detailChannel : null;
        }

        private String panoramaError() {
            return error(CameraChannel.PANORAMA, panoramaCount);
        }

        private String detailError() {
            return error(CameraChannel.DETAIL, detailCount);
        }

        private String error(CameraChannel role, int count) {
            if (count == 1) {
                return null;
            }
            if (count == 0) {
                return missing(role, code);
            }
            return duplicate(role, code);
        }
    }

    public record Match(Integer panoramaChannel, Integer detailChannel,
                        String panoramaError, String detailError) {

        public Integer channelOf(CameraChannel role) {
            return role == CameraChannel.PANORAMA ? panoramaChannel : detailChannel;
        }

        public String errorOf(CameraChannel role) {
            return role == CameraChannel.PANORAMA ? panoramaError : detailError;
        }
    }
}
