package com.hiki.isup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** 取流链路模式：0-TCP，1-UDP，2-HRUDP */
    private int linkMode = 0;

    private Cms cms = new Cms();
    private Stream stream = new Stream();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Session session = new Session();

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

    public int getLinkMode() {
        return linkMode;
    }

    public void setLinkMode(int linkMode) {
        this.linkMode = linkMode;
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

    /** CMS 注册服务监听配置（TCP 7660） */
    public static class Cms {
        private String listenIp = "0.0.0.0";
        private int listenPort = 7660;

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

    /** 流媒体监听配置（TCP 8003 预览 / 8004 回放下载） */
    public static class Stream {
        private String listenIp = "0.0.0.0";
        private int previewPort = 8003;
        private int playbackPort = 8004;

        public String getListenIp() {
            return listenIp;
        }

        public void setListenIp(String listenIp) {
            this.listenIp = listenIp;
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
        private int hlsTime = 2;
        private int hlsListSize = 6;
        private String hlsFlags = "delete_segments+append_list";
        private String mp4Args = "-f mp4 -movflags +frag_keyframe+empty_moov";
        private String logLevel = "error";

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

        public String getLogLevel() {
            return logLevel;
        }

        public void setLogLevel(String logLevel) {
            this.logLevel = logLevel;
        }
    }

    /** 会话与超时 */
    public static class Session {
        private int startTimeoutSec = 30;
        private int idleTimeoutSec = 120;
        private int downloadMaxSec = 3600;

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
    }
}
