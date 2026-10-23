package com.hiki.isup.service;

import com.hiki.isup.callback.DeviceRegisterCallback;
import com.hiki.isup.callback.CmsExceptionCallback;
import com.hiki.isup.callback.PlaybackNewLinkCallback;
import com.hiki.isup.callback.PreviewNewLinkCallback;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.exception.IsupException;
import com.hiki.isup.sdk.HCISUPCMS;
import com.hiki.isup.sdk.HCISUPStream;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.hiki.isup.util.MediaPaths;
import com.sun.jna.Memory;
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
 * 8660  CMS 注册服务（NET_ECMS_StartListen）
 * 8003  实时预览监听（NET_ESTREAM_StartListenPreview）
 * 8661  回放 / 下载监听（NET_ESTREAM_StartListenPlayBack）
 * </pre>
 *
 * <p>本地监听地址用 listen-ip（可填 0.0.0.0），返回给设备的一律用 public-ip。</p>
 */
@Service
public class IsupService implements CommandLineRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(IsupService.class);
    /** 未能确认 native 停止时，注册/新链路回调也必须保持强引用直至进程退出。 */
    private static final java.util.Set<IsupService> RETAINED_NATIVE_SERVICES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final IsupProperties properties;
    private final NativeSdkLoader sdkLoader;
    private final DeviceRegisterCallback deviceRegisterCallback;
    private final PreviewNewLinkCallback previewNewLinkCallback;
    private final PlaybackNewLinkCallback playbackNewLinkCallback;
    private final SessionManager sessionManager;
    private final FfmpegService ffmpegService;
    private final CmsExceptionCallback cmsExceptionCallback;
    private final HCISUPStream.EXCEPTION_CALLBACK streamExceptionCallback;

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
        this(properties, sdkLoader, deviceRegisterCallback, previewNewLinkCallback, playbackNewLinkCallback,
                sessionManager, ffmpegService, new CmsExceptionCallback(sessionManager, null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IsupService(IsupProperties properties, NativeSdkLoader sdkLoader,
                       DeviceRegisterCallback deviceRegisterCallback, PreviewNewLinkCallback previewNewLinkCallback,
                       PlaybackNewLinkCallback playbackNewLinkCallback, SessionManager sessionManager,
                       FfmpegService ffmpegService, CmsExceptionCallback cmsExceptionCallback) {
        this.properties = properties;
        this.sdkLoader = sdkLoader;
        this.deviceRegisterCallback = deviceRegisterCallback;
        this.previewNewLinkCallback = previewNewLinkCallback;
        this.playbackNewLinkCallback = playbackNewLinkCallback;
        this.sessionManager = sessionManager;
        this.ffmpegService = ffmpegService;
        this.cmsExceptionCallback = cmsExceptionCallback;
        this.streamExceptionCallback = (type, userId, handle, userData) -> {
            try {
                int error = sdkLoader.stream().NET_ESTREAM_GetLastError();
                playbackNewLinkCallback.onStreamException(type, userId, handle, error);
            } catch (Throwable error) {
                log.error("SMS exception callback failed", error);
            }
        };
    }

    @Override
    public void run(String... args) {
        purgeLeftoverMediaDirs();
        if (!properties.isEnabled()) {
            log.warn("isup.enabled=false，跳过海康 SDK 初始化，HTTP API 可用但无法接入真实设备");
            return;
        }
        try {
            start();
        } catch (IllegalStateException e) {
            // 配置或 SDK 环境问题：只打印原因和排查清单，不打长堆栈
            log.error("\n================ ISUP 服务启动失败 ================\n"
                            + "原因：{}\n"
                            + "--------------------------------------------------\n"
                            + "请依次检查：\n"
                            + "  1. isup.public-ip 是否为录像机可访问的公网地址（不能是 0.0.0.0 / 127.0.0.1）\n"
                            + "  2. isup.ehome-key 是否与录像机「平台接入」里配置的密钥一致\n"
                            + "  3. isup.sdk-dir 目录下是否放齐 SDK 文件\n"
                            + "     Linux: libHCISUPCMS.so libHCISUPStream.so libcrypto.so libssl.so HCAapSDKCom/\n"
                            + "     Windows: HCISUPCMS.dll HCISUPStream.dll libeay32.dll ssleay32.dll HCAapSDKCom\\\n"
                            + "  4. 8660 / 8003 / 8661 端口是否被占用或未放行\n"
                            + "配置文件：jar 同级的 config/application.yml（修改后重启生效）\n"
                            + "==================================================\n"
                            + "HTTP 接口仍可访问（GET /api/health），但设备无法注册。",
                    e.getMessage());
        } catch (Exception e) {
            // 启动失败不应导致整个 Spring 容器崩溃，保留 HTTP 接口便于排查
            log.error("ISUP 服务启动失败，HTTP API 仍可访问但设备无法注册", e);
        }
    }

    /**
     * 清理上一进程遗留的临时媒体目录。会话表只存在于内存，重启后 HLS 切片目录无人认领。
     */
    private void purgeLeftoverMediaDirs() {
        try {
            sessionManager.purgeTempMediaDirs(MediaPaths.mediaRoot(properties.getMediaDir()));
        } catch (Exception e) {
            log.warn("清理残留媒体目录失败", e);
        }
    }

    public synchronized void start() {
        if (ready) {
            return;
        }
        validateConfig();

        sdkLoader.load(properties);
        // Pin before registration: native may call back even when later startup steps fail.
        RETAINED_NATIVE_SERVICES.add(this);
        if (!sdkLoader.stream().NET_ESTREAM_SetExceptionCallBack(0, null, streamExceptionCallback, null)) {
            throw new IllegalStateException("Register SMS exception callback failed: "
                    + sdkLoader.stream().NET_ESTREAM_GetLastError());
        }
        if (!sdkLoader.cms().NET_ECMS_SetExceptionCallBack(0, null, cmsExceptionCallback, null)) {
            throw new IllegalStateException("Register CMS exception callback failed: " + sdkLoader.cms().NET_ECMS_GetLastError());
        }
        // applyLocalConfig(sdkLoader.cms());  // 临时回滚：枚举值不对会搞坏 SDK 状态

        startCmsListen();
        // 实时预览暂时不用：不监听 8003。恢复时取消下面整段注释即可
        // if (properties.getStream().isPreviewListenEnabled()) {
        //     startPreviewListen();
        // } else {
        //     log.info("未启用预览监听（isup.stream.preview-listen-enabled=false），录像下载仍使用回放端口 {}",
        //             properties.getStream().getPlaybackPort());
        // }
        log.info("已跳过预览监听（8003 已注释），录像下载仍使用回放端口 {}",
                properties.getStream().getPlaybackPort());
        startPlaybackListen();

        if (!ffmpegService.checkAvailable(properties)) {
            log.warn("ffmpeg 不可用，请检查 isup.ffmpeg-path 配置（当前值：{}）；预览/回放/下载将无法输出媒体",
                    properties.getFfmpegPath());
        }

        ready = true;
        String previewEndpoint = previewListenHandle >= 0
                ? properties.getStream().getListenIp() + ":" + properties.getStream().getPreviewPort()
                : "disabled";
        log.info("ISUP 服务启动完成：publicIp={}, CMS={}:{}, Preview={}, Playback={}:{}",
                properties.getPublicIp(),
                properties.getCms().getListenIp(), properties.getCms().getListenPort(),
                previewEndpoint,
                properties.getStream().getListenIp(), properties.getStream().getPlaybackPort());
    }

    /**
     * 把 SDK 默认参数改成"不要求安全验证、不下发告警/图片服务器"。
     * 否则设备会推 alarm/picture 数据过来（WriteToCache push Failed → 重注册风暴），
     * 码流也会因访问安全策略被拒收（StartPushRealStream 超时 errorCode=10）。
     *
     * <p>每个 enumType 单独 try/catch 并打印结果，错误的 enumType 不会让 SDK 崩溃。
     * 枚举值按海康公开头文件 NET_ECMS_LOCAL_CFG_TYPE_*（5/7~14）填入。</p>
     */
    private void applyLocalConfig(HCISUPCMS cms) {
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_KEEPALIVE_SECONDS, 15);
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_KEEPALIVE_COUNT, 6);
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_CMS_ACCESS_SECURITY, 0);
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_ALARM_ACCESS_SECURITY, 0);
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_STREAM_ACCESS_SECURITY, 0);
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_ALARM_PICTURE_SEPARATE, 1);
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_ALARM_SERVER_TYPE, 0);
        setLocalCfgInt(cms, HCISUPCMS.SDK_LOCAL_CFG_PICTURE_SERVER_TYPE, 0);
    }

    private void setLocalCfgInt(HCISUPCMS cms, int enumType, int value) {
        Memory mem = new Memory(4);
        try {
            mem.setInt(0, value);
            boolean ok = cms.NET_ECMS_SetSDKLocalCfg(enumType, mem);
            log.info("SetSDKLocalCfg(type={}, value={}) -> {}", enumType, value,
                    ok ? "OK" : "FAILED err=" + cms.NET_ECMS_GetLastError());
        } catch (Throwable t) {
            log.warn("SetSDKLocalCfg(type={}, value={}) 异常：{}", enumType, value, t.toString());
        } finally {
            mem.close();
        }
    }

    /**
     * 公网地址校验：绝不能把 0.0.0.0 / 127.0.0.1 返回给设备。
     */
    private void validateConfig() {
        String publicIp = properties.getPublicIp();
        if (publicIp == null || publicIp.isBlank()) {
            throw new IllegalStateException("未配置 isup.public-ip。"
                    + "请在 config/application.yml 中设置 isup.public-ip，或用 --isup.public-ip=你的公网IP / 环境变量 ISUP_PUBLIC_IP 指定。"
                    + "该地址是返回给录像机用于回连的公网地址");
        }
        String ip = publicIp.trim();
        if ("0.0.0.0".equals(ip) || "127.0.0.1".equals(ip) || "localhost".equalsIgnoreCase(ip) || "::".equals(ip)) {
            throw new IllegalStateException("isup.public-ip 不能是 " + ip + "。"
                    + "listen-ip 才填 0.0.0.0（本地监听），public-ip 必须填设备能访问到的公网地址");
        }
        if (properties.getEhomeKey() == null || properties.getEhomeKey().isBlank()) {
            throw new IllegalStateException("未配置 isup.ehome-key。"
                    + "请在 config/application.yml 中设置 isup.ehome-key，值必须与录像机「平台接入」里的密钥完全一致");
        }
    }

    private void startCmsListen() {
        HCISUPCMS cms = sdkLoader.cms();
        HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM param = new HCISUPCMS.NET_EHOME_CMS_LISTEN_PARAM();
        String listenIp = properties.getCms().getListenIp();
        copyTo(param.struAddress.szIP, listenIp);
        param.struAddress.wPort = (short) properties.getCms().getListenPort();
        param.fnCB = deviceRegisterCallback;
        param.dwKeepAliveSec = properties.getKeepAliveSec();
        param.dwTimeOutCount = properties.getTimeOutCount();
        param.write();

        int handle = cms.NET_ECMS_StartListen(param);
        if (handle < 0) {
            int errorCode = cms.NET_ECMS_GetLastError();
            throw IsupException.sdkFailed("NET_ECMS_StartListen", null, null, null, errorCode,
                    "listenIp=" + listenIp + ", port=" + properties.getCms().getListenPort());
        }
        cmsListenHandle = handle;
        log.info("CMS 注册监听成功：listenIp={}, port={}, handle={}, keepAliveSec={}, timeOutCount={}",
                listenIp, properties.getCms().getListenPort(), handle, param.dwKeepAliveSec, param.dwTimeOutCount);
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

    /**
     * 实时预览前校验 8003 是否在听。关掉预览监听时 CMS 信令仍可能成功，
     * 但设备回连 8003 会立刻失败（常见 errorCode=3715）。
     */
    public void requirePreviewListen(String operation) {
        requireReady(operation);
        if (previewListenHandle < 0) {
            throw IsupException.failed(operation, null, null, null,
                    "预览监听未启动（8003 在 IsupService.start() 里已注释）。"
                            + "恢复实时预览：取消该注释后重启");
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
        ready = false;
        sessionManager.stopAll("服务关闭");
        if (sdkLoader.isLoaded()) {
            RETAINED_NATIVE_SERVICES.add(this);
            if (!sessionManager.prepareSdkUnload(5000)) {
                log.error("ISUP native 调用或本地写入尚未停止，保留 SDK 与回调至进程退出，禁止并发卸载");
                return;
            }
            sdkLoader.unload(cmsListenHandle, previewListenHandle, playbackListenHandle);
            RETAINED_NATIVE_SERVICES.remove(this);
        }
        cmsListenHandle = -1;
        previewListenHandle = -1;
        playbackListenHandle = -1;
        log.info("ISUP 服务已关闭");
    }
}
