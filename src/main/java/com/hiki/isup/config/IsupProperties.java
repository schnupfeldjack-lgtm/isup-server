package com.hiki.isup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ISUP 服务配置。全部支持环境变量覆盖（详见 application.yml）。
 */
@Component
@ConfigurationProperties(prefix = "isup")
public class IsupProperties {

    /** 是否初始化海康 SDK 并开启监听（无 SDK 环境可关闭，仅调试 HTTP API） */
    private boolean enabled = true;

    /** 海康 SDK 动态库目录 */
    private String sdkDir = "./sdk";

    /** 公网 IP：返回给设备用于回连的地址，不能是 0.0.0.0 / 127.0.0.1 */
    private String publicIp = "";

    /** ISUP 5.0 接入密钥（与录像机一致） */
    private String ehomeKey = "";

    /** ERP 调用 HTTP API 的密钥（X-API-Key） */
    private String apiKey = "";

    /** ffmpeg 可执行文件路径 */
    private String ffmpegPath = "ffmpeg";

    /** 媒体输出根目录 */
    private String mediaDir = "./data/media";

    /** HLS 签名 URL 有效期（秒），限制在 60~86400 */
    private int mediaUrlTtlSeconds = 7200;

    /** 取流链路模式：0-TCP，1-UDP，2-HRUDP */
    private int linkMode = 0;


    /**
     * 设备掉线宽限期（秒）。设备端多中心/多 DeviceID 互踢时会高频上下线抖动，
     * 收到下线回调后先标记为「重连中」保留条目，等满这么久还没回来才真正移出列表、
     * 并停掉它的会话。0 = 下线立即移除（旧行为）。
     */
    private int deviceOfflineGraceSec = 20;

    /**
     * 是否向设备下发告警上报主机（NET_EHOME_SERVER_INFO_V50 的告警服务器字段）。
     *
     * <p>开启后 SDK 会走 ISAPI 透传给设备下发告警主机（GET /ISAPI/Event/notification/httpHosts）。
     * 设备不支持或响应慢时该调用超时（SDK 日志 error[10]），接着 SetHost 报
     * ENUM_DEV_SESSIONKEY_ERROR，设备被踢下线 → 立刻重注册 → 再失败，
     * 形成 8 秒一轮的上下线抖动循环，在线列表和预览都会被反复打断。</p>
     *
     * <p>联调预览/回放/下载不需要告警上报，默认关闭；确认设备支持且要接告警事件时再打开。</p>
     */
    private boolean alarmServerEnabled = false;

    /**
     * 是否向设备下发图片服务器（抓图上报用）。
     *
     * <p>与告警主机同理：下发后设备会上报图片，SDK 侧可见
     * `Invalid Param, Callback EHOME50_ERROR` → 连接被销毁 → 设备重连。
     * 暂不需要抓图，默认关闭。</p>
     */
    private boolean pictureServerEnabled = false;

    /**
     * 设备上线（ENUM_DEV_ON）时是否回传可选的告警、图片服务器信息。
     *
     * <p>关闭时仍回传 NET_EHOME_SERVER_INFO_V50 的结构体大小和保活参数；
     * 开启时再分别由 alarmServerEnabled、pictureServerEnabled 控制对应服务器字段。</p>
     *
     * <p>取流地址是在发起预览/回放时单独下发的，不依赖这里，关闭不影响取流。</p>
     */
    private boolean serverInfoEnabled = true;

    /** 下发给设备的心跳周期（秒）。官方 Demo 用 15；0 会让 SDK 走默认值并可能心跳异常 */
    private int keepAliveSec = 15;

    /** 心跳超时次数（配合 keepAliveSec 判断掉线）。官方 Demo 用 6 */
    private int timeOutCount = 6;

    private Cms cms = new Cms();
    private Stream stream = new Stream();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Session session = new Session();
    private Oss oss = new Oss();
    private WaybillVideo waybillVideo = new WaybillVideo();
    private DeviceAuth deviceAuth = new DeviceAuth();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSdkDir() {
        return sdkDir;
    }

    public void setSdkDir(String sdkDir) {
        this.sdkDir = sdkDir;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public String getEhomeKey() {
        return ehomeKey;
    }

    public void setEhomeKey(String ehomeKey) {
        this.ehomeKey = ehomeKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getMediaDir() {
        return mediaDir;
    }

    public void setMediaDir(String mediaDir) {
        this.mediaDir = mediaDir;
    }

    public int getMediaUrlTtlSeconds() {
        return mediaUrlTtlSeconds;
    }

    public void setMediaUrlTtlSeconds(int mediaUrlTtlSeconds) {
        this.mediaUrlTtlSeconds = Math.max(60, Math.min(mediaUrlTtlSeconds, 86400));
    }

    public int getLinkMode() {
        return linkMode;
    }

    public void setLinkMode(int linkMode) {
        this.linkMode = linkMode;
    }


    public int getDeviceOfflineGraceSec() {
        return deviceOfflineGraceSec;
    }

    public void setDeviceOfflineGraceSec(int deviceOfflineGraceSec) {
        this.deviceOfflineGraceSec = Math.max(0, deviceOfflineGraceSec);
    }

    public boolean isAlarmServerEnabled() {
        return alarmServerEnabled;
    }

    public void setAlarmServerEnabled(boolean alarmServerEnabled) {
        this.alarmServerEnabled = alarmServerEnabled;
    }

    public boolean isPictureServerEnabled() {
        return pictureServerEnabled;
    }

    public void setPictureServerEnabled(boolean pictureServerEnabled) {
        this.pictureServerEnabled = pictureServerEnabled;
    }

    public boolean isServerInfoEnabled() {
        return serverInfoEnabled;
    }

    public void setServerInfoEnabled(boolean serverInfoEnabled) {
        this.serverInfoEnabled = serverInfoEnabled;
    }

    public int getKeepAliveSec() {
        return keepAliveSec;
    }

    public void setKeepAliveSec(int keepAliveSec) {
        this.keepAliveSec = Math.max(0, keepAliveSec);
    }

    public int getTimeOutCount() {
        return timeOutCount;
    }

    public void setTimeOutCount(int timeOutCount) {
        this.timeOutCount = Math.max(0, timeOutCount);
    }

    public Cms getCms() {
        return cms;
    }

    public void setCms(Cms cms) {
        this.cms = cms;
    }

    public Stream getStream() {
        return stream;
    }

    public void setStream(Stream stream) {
        this.stream = stream;
    }

    public Ffmpeg getFfmpeg() {
        return ffmpeg;
    }

    public void setFfmpeg(Ffmpeg ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Oss getOss() {
        return oss;
    }

    public void setOss(Oss oss) {
        this.oss = oss;
    }

    public WaybillVideo getWaybillVideo() {
        return waybillVideo;
    }

    public void setWaybillVideo(WaybillVideo waybillVideo) {
        this.waybillVideo = waybillVideo;
    }

    public DeviceAuth getDeviceAuth() {
        return deviceAuth;
    }

    public void setDeviceAuth(DeviceAuth deviceAuth) {
        this.deviceAuth = deviceAuth;
    }

    /** CMS 注册服务监听配置（TCP 8660） */
    public static class Cms {
        private String listenIp = "0.0.0.0";
        private int listenPort = 8660;

        public String getListenIp() {
            return listenIp;
        }

        public void setListenIp(String listenIp) {
            this.listenIp = listenIp;
        }

        public int getListenPort() {
            return listenPort;
        }

        public void setListenPort(int listenPort) {
            this.listenPort = listenPort;
        }
    }

    /** 流媒体监听配置（TCP 8003 预览 / 8661 回放与录像下载推流） */
    public static class Stream {
        private String listenIp = "0.0.0.0";
        /**
         * 是否监听 8003 预览端口。仅录像下载时可关闭，少占一个 SDK 监听与端口；
         * 下载仍依赖 {@link #playbackPort}（8661）接收设备推流。
         */
        private boolean previewListenEnabled = true;
        private int previewPort = 8003;
        private int playbackPort = 8661;

        public String getListenIp() {
            return listenIp;
        }

        public void setListenIp(String listenIp) {
            this.listenIp = listenIp;
        }

        public boolean isPreviewListenEnabled() {
            return previewListenEnabled;
        }

        public void setPreviewListenEnabled(boolean previewListenEnabled) {
            this.previewListenEnabled = previewListenEnabled;
        }

        public int getPreviewPort() {
            return previewPort;
        }

        public void setPreviewPort(int previewPort) {
            this.previewPort = previewPort;
        }

        public int getPlaybackPort() {
            return playbackPort;
        }

        public void setPlaybackPort(int playbackPort) {
            this.playbackPort = playbackPort;
        }
    }

    /** FFmpeg 参数 */
    public static class Ffmpeg {
        /** ISUP PS 码流的输入封装格式 */
        private String psInputFormat = "mpeg";
        /** 编码处理参数，默认不转码 */
        private String codecArgs = "-c copy";
        /**
         * HLS（预览 / 回放）专用编码参数，留空则回退 codecArgs。
         *
         * <p>必须能和 MP4 分开配：浏览器 hls.js 基本解不了 H.265/HEVC（黑屏），
         * 而海康默认主码流就是 HEVC；但 MP4 容器本身支持 HEVC，下载没必要转视频。
         * 所以 HLS 常常要 libx264 转码，MP4 却仍可 copy。</p>
         */
        private String hlsCodecArgs = "";
        /** 历史回放独立编码参数，留空复用 HLS/codecArgs；不影响 preview 和 MP4。 */
        private String playbackCodecArgs = "";
        public String getPlaybackCodecArgs() { return playbackCodecArgs; }
        public void setPlaybackCodecArgs(String value) { playbackCodecArgs = value; }
        /** MP4（录像下载）专用编码参数，留空则回退 codecArgs。 */
        private String mp4CodecArgs = "";
        private int hlsTime = 2;
        private int hlsListSize = 6;
        private String hlsFlags = "delete_segments+append_list";
        private String mp4Args = "-f mp4 -movflags +frag_keyframe+empty_moov";
        /**
         * 传给 ffmpeg 的编码线程数（-threads）。0 表示不传该参数，由 ffmpeg 自行决定。
         *
         * <p>多路并发转码时每路都按核数开线程会造成线程过度订阅，上下文切换的开销
         * 会拖慢整体吞吐；此时显式设为 1 或 2，让并发路数成为并行度的唯一来源。</p>
         */
        private int threads = 0;
        private String logLevel = "error";
        /**
         * 输入探测的最大字节数。已显式指定输入封装格式时无需探测整个容器，
         * 值越大起播越慢，太小则可能探不出音轨。
         */
        private int probeSize = 1000000;
        /** 输入探测的最大时长（微秒），与 probeSize 一起决定起播延迟 */
        private int analyzeDuration = 1000000;
        /**
         * 解封装侧标志。ISUP PS 流常缺 PTS，需要 genpts 补时间戳；
         * igndts 让 ffmpeg 忽略输入 DTS 异常，避免切片时长被算成几十秒。
         */
        private String inputFlags = "+genpts+igndts";
        /** 负时间戳处理策略，留空则不设置该参数 */
        private String avoidNegativeTs = "make_zero";

        public String getPsInputFormat() {
            return psInputFormat;
        }

        public void setPsInputFormat(String psInputFormat) {
            this.psInputFormat = psInputFormat;
        }

        public String getCodecArgs() {
            return codecArgs;
        }

        public void setCodecArgs(String codecArgs) {
            this.codecArgs = codecArgs;
        }

        public String getHlsCodecArgs() {
            return hlsCodecArgs;
        }

        public void setHlsCodecArgs(String hlsCodecArgs) {
            this.hlsCodecArgs = hlsCodecArgs;
        }

        public String getMp4CodecArgs() {
            return mp4CodecArgs;
        }

        public void setMp4CodecArgs(String mp4CodecArgs) {
            this.mp4CodecArgs = mp4CodecArgs;
        }

        public int getHlsTime() {
            return hlsTime;
        }

        public void setHlsTime(int hlsTime) {
            this.hlsTime = hlsTime;
        }

        public int getHlsListSize() {
            return hlsListSize;
        }

        public void setHlsListSize(int hlsListSize) {
            this.hlsListSize = hlsListSize;
        }

        public String getHlsFlags() {
            return hlsFlags;
        }

        public void setHlsFlags(String hlsFlags) {
            this.hlsFlags = hlsFlags;
        }

        public String getMp4Args() {
            return mp4Args;
        }

        public void setMp4Args(String mp4Args) {
            this.mp4Args = mp4Args;
        }

        public int getThreads() {
            return threads;
        }

        public void setThreads(int threads) {
            this.threads = Math.max(0, threads);
        }

        public String getLogLevel() {
            return logLevel;
        }

        public void setLogLevel(String logLevel) {
            this.logLevel = logLevel;
        }

        public int getProbeSize() {
            return probeSize;
        }

        public void setProbeSize(int probeSize) {
            this.probeSize = probeSize;
        }

        public int getAnalyzeDuration() {
            return analyzeDuration;
        }

        public void setAnalyzeDuration(int analyzeDuration) {
            this.analyzeDuration = analyzeDuration;
        }

        public String getInputFlags() {
            return inputFlags;
        }

        public void setInputFlags(String inputFlags) {
            this.inputFlags = inputFlags;
        }

        public String getAvoidNegativeTs() {
            return avoidNegativeTs;
        }

        public void setAvoidNegativeTs(String avoidNegativeTs) {
            this.avoidNegativeTs = avoidNegativeTs;
        }
    }

    /** 会话与超时 */
    public static class Session {
        private int startTimeoutSec = 30;
        private int idleTimeoutSec = 120;
        private int downloadMaxSec = 3600;
        /**
         * 已完成的下载任务与其 MP4 文件的保留时长（分钟）。
         * 到期后文件与会话记录一并清理，避免长时间运行后磁盘与内存无限增长。
         * 设为 0 表示不自动清理。
         */
        private int downloadRetainMinutes = 60;
        /**
         * 同时进行的会话数上限（预览 / 回放 / 下载合计）。
         * 每路 HLS 转码约 1 核；0 表示不限制。
         */
        private int maxActiveSessions = 8;
        private int maxPlaybackPerDevice = 2;

        public int getMaxPlaybackPerDevice() { return maxPlaybackPerDevice; }
        public void setMaxPlaybackPerDevice(int value) { maxPlaybackPerDevice = value; }

        public int getStartTimeoutSec() {
            return startTimeoutSec;
        }

        public void setStartTimeoutSec(int startTimeoutSec) {
            this.startTimeoutSec = startTimeoutSec;
        }

        public int getIdleTimeoutSec() {
            return idleTimeoutSec;
        }

        public void setIdleTimeoutSec(int idleTimeoutSec) {
            this.idleTimeoutSec = idleTimeoutSec;
        }

        public int getDownloadMaxSec() {
            return downloadMaxSec;
        }

        public void setDownloadMaxSec(int downloadMaxSec) {
            this.downloadMaxSec = downloadMaxSec;
        }

        public int getDownloadRetainMinutes() {
            return downloadRetainMinutes;
        }

        public void setDownloadRetainMinutes(int downloadRetainMinutes) {
            this.downloadRetainMinutes = downloadRetainMinutes;
        }

        public int getMaxActiveSessions() {
            return maxActiveSessions;
        }

        public void setMaxActiveSessions(int maxActiveSessions) {
            this.maxActiveSessions = maxActiveSessions;
        }
    }

    /** 阿里云 OSS。密钥只放配置文件，不要写进代码。 */
    public static class Oss {
        private int connectionTimeoutMs = 5000;
        private int socketTimeoutMs = 30000;
        private int connectionRequestTimeoutMs = 5000;
        private int requestTimeoutMs = 60000;
        private String endpoint = "";
        private String bucket = "";
        private String accessKeyId = "";
        private String accessKeySecret = "";
        /** 对象 key 前缀，例如 waybill-video/ */
        private String prefix = "waybill-video/";
        /** 自定义 CDN / 静态域名，有值时回写 URL 用它，不再拼 bucket.endpoint */
        private String staticDomain = "";

        public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(int value) { connectionTimeoutMs = Math.max(1, value); }
        public int getSocketTimeoutMs() { return socketTimeoutMs; }
        public void setSocketTimeoutMs(int value) { socketTimeoutMs = Math.max(1, value); }
        public int getConnectionRequestTimeoutMs() { return connectionRequestTimeoutMs; }
        public void setConnectionRequestTimeoutMs(int value) { connectionRequestTimeoutMs = Math.max(1, value); }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int value) { requestTimeoutMs = Math.max(1, value); }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        /** ERP 配置名 bucketName 的别名 */
        public void setBucketName(String bucketName) {
            this.bucket = bucketName;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        /** ERP 配置名 accessKey 的别名 */
        public void setAccessKey(String accessKey) {
            this.accessKeyId = accessKey;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
        }

        /** ERP 配置名 secretKey 的别名 */
        public void setSecretKey(String secretKey) {
            this.accessKeySecret = secretKey;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getStaticDomain() {
            return staticDomain;
        }

        public void setStaticDomain(String staticDomain) {
            this.staticDomain = staticDomain;
        }
    }

    /** 出库包裹录像定时同步 */
    public static class WaybillVideo {
        /** 显式优先任务清单的去重上限，限制每次优先查询的大小。 */
        public static final int MAX_PRIORITY_IDS = 256;

        /** 可选优先录像行 ID，最多 {@value #MAX_PRIORITY_IDS} 个；只改变读取顺序，不放宽领取、重试或媒体校验。 */
        private List<String> priorityIds = List.of();

        /** 当天与历史按 3:1 公平调度；开启后替代静态 priorityIds，不改变业务状态或录像可选性。 */
        private boolean fairSchedulingEnabled;
        public boolean isFairSchedulingEnabled() { return fairSchedulingEnabled; }
        public void setFairSchedulingEnabled(boolean value) { fairSchedulingEnabled = value; }

        /** 仅为明确指定的设备启用录像文件边界保护；空值保持原下载行为。 */
        private String boundaryGuardDevice = "";

        public String getBoundaryGuardDevice() { return boundaryGuardDevice; }
        public void setBoundaryGuardDevice(String value) {
            boundaryGuardDevice = value == null ? "" : value.trim();
        }

        public List<String> getPriorityIds() { return priorityIds; }
        public void setPriorityIds(List<String> ids) { priorityIds = validatePriorityIds(ids); }

        public static List<String> validatePriorityIds(List<String> ids) {
            if (ids == null || ids.isEmpty()) return List.of();
            java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
            for (String value : ids) {
                String id = value == null ? "" : value.trim();
                if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                    throw new IllegalArgumentException("waybill-video.priority-ids must contain 1-64 character IDs using letters, digits, _ or -");
                }
                distinct.add(id);
                if (distinct.size() > MAX_PRIORITY_IDS) {
                    throw new IllegalArgumentException("waybill-video.priority-ids supports at most "
                            + MAX_PRIORITY_IDS + " distinct IDs");
                }
            }
            return List.copyOf(distinct);
        }

        /** 单行总预算，包括排队、双路下载、校验、裁切与上传。 */
        private int rowTimeoutSeconds = 600;
        private int queryTimeoutSeconds = 5;
        private int retryMaxAttempts = 3;
        private int retryMaxEntries = 10000;
        private int retryMaxBackoffMinutes = 360;
        private int retryMaxAgeDays = 7;
        /** 仅刚完成录制的纯缺尾可按暂时故障重试，历史缺口不能自动放行。 */
        private int recordingTailRetryWindowSeconds = 600;

        public int getRecordingTailRetryWindowSeconds() { return recordingTailRetryWindowSeconds; }
        public void setRecordingTailRetryWindowSeconds(int value) {
            recordingTailRetryWindowSeconds = Math.min(3600, Math.max(0, value));
        }

        public int getRowTimeoutSeconds() { return rowTimeoutSeconds; }
        public void setRowTimeoutSeconds(int value) { rowTimeoutSeconds = Math.max(1, value); }
        public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(int value) { queryTimeoutSeconds = Math.max(1, value); }
        public int getRetryMaxAttempts() { return retryMaxAttempts; }
        public void setRetryMaxAttempts(int value) { retryMaxAttempts = Math.min(100, Math.max(1, value)); }
        public int getRetryMaxEntries() { return retryMaxEntries; }
        public void setRetryMaxEntries(int value) { retryMaxEntries = Math.min(10000, Math.max(1, value)); }
        public int getRetryMaxBackoffMinutes() { return retryMaxBackoffMinutes; }
        public void setRetryMaxBackoffMinutes(int value) { retryMaxBackoffMinutes = Math.max(1, value); }
        public int getRetryMaxAgeDays() { return retryMaxAgeDays; }
        public void setRetryMaxAgeDays(int value) { retryMaxAgeDays = Math.max(1, value); }
        /** 关闭后不注册定时任务，避免测试或无库环境误跑 */
        private boolean enabled = false;
        private String cron = "0 * * * * *";
        /** 要回写的 URL 角色：1=全景 video_url1，2=细节 video_url2。设备通道号按 channel_code 匹配，不看这里 */
        private List<Integer> channels = new ArrayList<>(List.of(1, 2));
        private int batchSize = 5;
        /**
         * 出库同步同时进行的最大下载数（每路占用一个 ISUP/ffmpeg 会话）。
         * 建议不超过 {@link Session#getMaxActiveSessions}；默认可与并发会话上限对齐。
         */
        private int downloadConcurrency = 1;
        /** @deprecated 已由会话终态通知替代；保留配置项兼容旧 yml，不再使用 */
        @Deprecated
        private long pollIntervalMs = 2000;
        /**
         * PROCESSING 超过这么多分钟视为进程中途挂了，下一轮改回 PENDING。
         * 0 = 不回收。
         */
        private int staleProcessingMinutes = 10;
        /**
         * 单轮领取新任务的时间窗口；到期停止领取，已开始任务按单行总预算完成。
         * 0 = 不限制。
         */
        private int maxRunSeconds = 480;
        /**
         * 临时失败的首次退避时间，后续指数退避且受次数/年龄/容量限制。
         * 0 = 不自动回收 FAILED。历史失败分页检查，只有耐久策略允许的 ID 才改回 PENDING。
         */
        private int failedRetryAfterMinutes = 30;
        /**
         * 待处理记录所在表。生产默认 {@code wms_outbound_waybill_video}；
         * dev-test 配成 {@code wms_outbound_waybill_video_test} 避免动生产表。
         */
        private String table = "wms_outbound_waybill_video";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public List<Integer> getChannels() {
            return channels;
        }

        public void setChannels(List<Integer> channels) {
            this.channels = channels;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = Math.max(1, batchSize);
        }

        public int getDownloadConcurrency() {
            return downloadConcurrency;
        }

        public void setDownloadConcurrency(int downloadConcurrency) {
            this.downloadConcurrency = Math.max(1, downloadConcurrency);
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = Math.max(0, pollIntervalMs);
        }

        public int getStaleProcessingMinutes() {
            return staleProcessingMinutes;
        }

        public void setStaleProcessingMinutes(int staleProcessingMinutes) {
            this.staleProcessingMinutes = Math.max(0, staleProcessingMinutes);
        }

        public int getMaxRunSeconds() {
            return maxRunSeconds;
        }

        public void setMaxRunSeconds(int maxRunSeconds) {
            this.maxRunSeconds = Math.max(0, maxRunSeconds);
        }

    public int getFailedRetryAfterMinutes() {
        return failedRetryAfterMinutes;
    }

    public void setFailedRetryAfterMinutes(int failedRetryAfterMinutes) {
        this.failedRetryAfterMinutes = Math.max(0, failedRetryAfterMinutes);
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        if (table == null || table.isBlank() || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        this.table = table;
    }
}

    /**
     * 旧设备 HTTP 认证配置，仅保留配置绑定兼容。
     *
     * <p>字幕、通道查询和通用 ISAPI 代理均通过 ISUP 会话透传，不读取本配置凭据；
     * timeoutMs 仅作为未设置 isup.isapi.timeout-ms 时的兼容默认值。</p>
     */
    public static class DeviceAuth {
        /** 设备登录用户名，海康默认 admin */
        private String username = "admin";
        /** 设备登录密码 */
        private String password = "";
        /** 单次请求超时（毫秒），含连接与读取 */
        private int timeoutMs = 5000;
        /**
         * 按设备覆盖凭据。键是设备 ID（即 {@code GET /api/v1/devices} 返回的 deviceId），
         * 优先级高于 username/password。
         *
         * <p>多设备密码不同时必须配置，比如 NVR 与其下的 IPC 摄像头密码可能不同。
         * 要在 IPC 摄像头自己上做 OSD 叠加，IPC 的 deviceId 必须有覆盖条目。</p>
         */
        private java.util.Map<String, Entry> devices = new java.util.HashMap<>();

        /**
         * 取某台设备的凭据：先查 devices 覆盖，没有用全局 username/password。
         */
        public AuthPair resolveFor(String deviceId) {
            Entry entry = devices == null ? null : devices.get(deviceId);
            String u = entry != null && entry.getUsername() != null && !entry.getUsername().isBlank()
                    ? entry.getUsername() : username;
            String p = entry != null && entry.getPassword() != null
                    ? entry.getPassword() : password;
            return new AuthPair(u, p);
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = Math.max(1000, timeoutMs);
        }

        public java.util.Map<String, Entry> getDevices() {
            return devices;
        }

        public void setDevices(java.util.Map<String, Entry> devices) {
            this.devices = devices;
        }

        /** 单台设备的覆盖凭据 */
        public static class Entry {
            private String username;
            private String password;

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }
        }

        /** 解析结果：避免调用方修改原对象 */
        public record AuthPair(String username, String password) {
        }
    }
}
