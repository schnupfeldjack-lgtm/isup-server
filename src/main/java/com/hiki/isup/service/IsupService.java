package com.hiki.isup.service;

import com.hiki.isup.callback.DeviceRegisterCallback;
import com.hiki.isup.callback.PlaybackNewLinkCallback;
import com.hiki.isup.callback.PreviewNewLinkCallback;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.sdk.NativeSdkLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import static com.hiki.isup.sdk.NativeSdkLoader.copyTo;

/**
 * ISUP 服务主入口：负责 SDK 初始化与三类监听的启停。
 *
 * <pre>
 * 7660  CMS 注册服务（NET_ECMS_StartListen）
 * 8003  实时预览监听（NET_ESTREAM_StartListenPreview）
 * 8004  回放 / 下载监听（NET_ESTREAM_StartListenPlayBack）
 * </pre>
 *
 * <p>本地监听地址用 listen-ip（可填 0.0.0.0），返回给设备的一律用 public-ip。</p>
 */
@Service
public class IsupService implements CommandLineRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IsupService.class);

    private final IsupProperties properties;
    private final NativeSdkLoader sdkLoader;
    private final DeviceRegisterCallback deviceRegisterCallback;
    private final PreviewNewLinkCallback previewNewLinkCallback;
    private final PlaybackNewLinkCallback playbackNewLinkCallback;
    private final SessionManager sessionManager;
    private final FfmpegService ffmpegService;

    private volatile int cmsListenHandle = -1;
    private volatile int previewListenHandle = -1;
    private volatile int playbackListenHandle = -1;
    private volatile boolean ready = false;

    public IsupService(IsupProperties properties,
                       NativeSdkLoader sdkLoader,
                       DeviceRegisterCallback deviceRegisterCallback,
                       PreviewNewLinkCallback previewNewLinkCallback,
                       PlaybackNewLinkCallback playbackNewLinkCallback,
                       SessionManager sessionManager,
                       FfmpegService ffmpegService) {
        this.properties = properties;
        this.sdkLoader = sdkLoader;
        this.deviceRegisterCallback = deviceRegisterCallback;
        this.previewNewLinkCallback = previewNewLinkCallback;
        this.playbackNewLinkCallback = playbackNewLinkCallback;
        this.sessionManager = sessionManager;
        this.ffmpegService = ffmpegService;
    }

    @Override
    public void run(String... args) {
        if (!properties.isEnabled()) {
            log.warn("isup.enabled=false，跳过海康 SDK 初始化，HTTP API 可用但无法接入真实设备");
            return;
        }
        try {
            start();
        } catch (Exception e) {
            // 启动失败不应导致整个 Spring 容器崩溃，保留 HTTP 接口便于排查
            log.error("ISUP 服务启动失败，HTTP API 仍可访问但设备无法注册", e);
        }
    }

    public synchronized void start() {
        if (ready) {
            return;
        }
        validateConfig();

        sdkLoader.load(properties);

        startCmsListen();
        startPreviewListen();
        startPlaybackListen();

        IsupProperties.Session session = properties.getSession();
        sessionManager.configureTimeouts(session.getStartTimeoutSec(), session.getIdleTimeoutSec(), session.getDownloadMaxSec());

        if (!ffmpegService.checkAvailable(properties)) {
            log.warn("ffmpeg 不可用，请检查 isup.ffmpeg-path 配置（当前值：{}）；预览/回放/下载将无法输出媒体",
                    properties.getFfmpegPath());
        }

        ready = true;
        log.info("ISUP 服务启动完成：publicIp={}, CMS={}:{}, Preview={}:{}, Playback={}:{}",
                properties.getPublicIp(),
                properties.getCms().getListenIp(), properties.getCms().getListenPort(),
                properties.getStream().getListenIp(), properties.getStream().getPreviewPort(),
                properties.getStream().getListenIp(), properties.getStream().getPlaybackPort());
    }

    /**
     * 公网地址校验：绝不能把 0.0.0.0 / 127.0.0.1 返回给设备。
     */
    private void validateConfig() {
        String publicIp = properties.getPublicIp();
        if (publicIp == null || publicIp.isBlank()) {
            throw new IllegalStateException("必须配置 isup.public-ip（环境变量 ISUP_PUBLIC_IP），该地址会返回给设备用于回连");
        }
        String ip = publicIp.trim();
        if ("0.0.0.0".equals(ip) || "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip) || "::".equals(ip)) {
            throw new IllegalStateException("isup.public-ip 不能是 " + ip + "，必须是设备可访问的公网地址");
        }
        if (properties.getEhomeKey() == null || properties.getEhomeKey().isBlank()) {
            throw new IllegalStateException("必须配置 isup.ehome-key（环境变量 ISUP_EHOME_KEY），且与录像机上的密钥一致");
        }
    }

    private void startCmsListen() {
        HCISUPCMS cms = sdkLoader.cms();
        HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM param = new HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM();
        String listenIp = properties.getCms().getListenIp();
        copyTo(param.struAddress.szIP, listenIp);
        param.struAddress.wPort = (short) properties.getCms().getListenPort();
        param.fnCB = deviceRegisterCallback;
        param.write();

        int handle = cms.NET_ECMS_StartListen(param);
        if (handle < 0) {
            int errorCode = cms.NET_ECMS_GetLastError();
            throw IsupException.sdkFailed("NET_ECMS_StartListen", null, null, null, errorCode,
                    "listenIp=" + listenIp + ", port=" + properties.getCms().getListenPort());
        }
        cmsListenHandle = handle;
        log.info("CMS 注册监听成功：listenIp={}, port={}, handle={}", listenIp, properties.getCms().getListenPort(), handle);
    }

    private void startPreviewListen() {
        HCISUPStream stream = sdkLoader.stream();
        HCISUPStream.NET_EHOME_LISTEN_PREVIEW_CFG cfg = new HCISUPStream.NET_EHOME_LISTEN_PREVIEW_CFG();
        String listenIp = properties.getStream().getListenIp();
        copyTo(cfg.struIPAdress.szIP, listenIp);
        cfg.struIPAdress.wPort = (short) properties.getStream().getPreviewPort();
        cfg.fnNewLinkCB = previewNewLinkCallback;
        cfg.byLinkMode = (byte) properties.getLinkMode();
        cfg.write();

        int handle = stream.NET_ESTREAM_StartListenPreview(cfg);
        if (handle < 0) {
            int errorCode = stream.NET_ESTREAM_GetLastError();
            throw IsupException.sdkFailed("NET_ESTREAM_StartListenPreview", null, null, null, errorCode,
                    "listenIp=" + listenIp + ", port=" + properties.getStream().getPreviewPort());
        }
        previewListenHandle = handle;
        log.info("预览监听成功：listenIp={}, port={}, handle={}", listenIp, properties.getStream().getPreviewPort(), handle);
    }

    private void startPlaybackListen() {
        HCISUPStream stream = sdkLoader.stream();
        HCISUPStream.NET_EHOME_PLAYBACK_LISTEN_PARAM param = new HCISUPStream.NET_EHOME_PLAYBACK_LISTEN_PARAM();
        String listenIp = properties.getStream().getListenIp();
        copyTo(param.struIPAdress.szIP, listenIp);
        param.struIPAdress.wPort = (short) properties.getStream().getPlaybackPort();
        param.fnNewLinkCB = playbackNewLinkCallback;
        param.byLinkMode = (byte) properties.getLinkMode();
        param.write();

        int handle = stream.NET_ESTREAM_StartListenPlayBack(param);
        if (handle < 0) {
            int errorCode = stream.NET_ESTREAM_GetLastError();
            throw IsupException.sdkFailed("NET_ESTREAM_StartListenPlayBack", null, null, null, errorCode,
                    "listenIp=" + listenIp + ", port=" + properties.getStream().getPlaybackPort());
        }
        playbackListenHandle = handle;
        log.info("回放/下载监听成功：listenIp={}, port={}, handle={}", listenIp, properties.getStream().getPlaybackPort(), handle);
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 业务操作前校验 SDK 是否可用。
     */
    public void requireReady(String operation) {
        if (!ready || !sdkLoader.isLoaded()) {
            throw IsupException.failed(operation, null, null, null,
                    "ISUP SDK 未就绪（可能缺少 SDK 文件或监听未启动），请检查启动日志与 GET /api/health");
        }
    }

    public HCISUPCMS cms() {
        return sdkLoader.cms();
    }

    public HCISUPStream stream() {
        return sdkLoader.stream();
    }

    @Override
    public void destroy() {
        log.info("开始关闭 ISUP 服务");
        sessionManager.stopAll("服务关闭");
        if (sdkLoader.isLoaded()) {
            sdkLoader.unload(cmsListenHandle, previewListenHandle, playbackListenHandle);
        }
        cmsListenHandle = -1;
        previewListenHandle = -1;
        playbackListenHandle = -1;
        ready = false;
        log.info("ISUP 服务已关闭");
    }
}
