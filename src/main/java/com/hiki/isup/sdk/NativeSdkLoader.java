package com.hiki.isup.sdk;

import com.hiki.isup.config.IsupProperties;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 海康 ISUP SDK 动态库加载器（同时兼容 Linux x86_64 与 Windows）。
 *
 * <p>Linux 需要的文件（放到 sdk-dir 目录下）：</p>
 * <pre>
 *   libHCISUPCMS.so  libHCISUPStream.so  libcrypto.so  libssl.so  HCAapSDKCom/
 * </pre>
 * <p>Windows 需要的文件：</p>
 * <pre>
 *   HCISUPCMS.dll  HCISUPStream.dll  libeay32.dll  ssleay32.dll  HCAapSDKCom/
 * </pre>
 *
 * <p>初始化顺序严格遵循海康 Demo：
 * SetSDKInitCfg(crypto) -&gt; SetSDKInitCfg(ssl) -&gt; Init() -&gt; SetSDKLocalCfg(HCAapSDKCom)。</p>
 */
@Component
public class NativeSdkLoader {

    private static final Logger log = LoggerFactory.getLogger(NativeSdkLoader.class);

    /** 组件库目录配置类型 */
    private static final int LOCAL_CFG_COMPONENT_DIR = 5;
    private static final int CRYPTO_SLOT = 0;
    private static final int SSL_SLOT = 1;
    private static final int LOG_LEVEL = 3;

    private volatile HCISUPCMS cms;
    private volatile HCISUPStream stream;
    private volatile boolean loaded = false;

    public boolean isLoaded() {
        return loaded;
    }

    public HCISUPCMS cms() {
        if (cms == null) {
            throw new IllegalStateException("HCISUPCMS 尚未加载，请检查 isup.sdk-dir 配置与 SDK 文件是否齐全");
        }
        return cms;
    }

    public HCISUPStream stream() {
        if (stream == null) {
            throw new IllegalStateException("HCISUPStream 尚未加载，请检查 isup.sdk-dir 配置与 SDK 文件是否齐全");
        }
        return stream;
    }

    /**
     * 加载并初始化 CMS 与 Stream 两个动态库。
     */
    public synchronized void load(IsupProperties properties) {
        if (loaded) {
            return;
        }
        File sdkDir = new File(properties.getSdkDir());
        String absSdkDir = sdkDir.getAbsolutePath();
        if (!sdkDir.exists()) {
            throw new IllegalStateException("海康 SDK 目录不存在：" + absSdkDir
                    + "，请将 HCISUPCMS / HCISUPStream 等文件放入该目录");
        }
        boolean linux = isLinux();
        log.info("开始加载海康 SDK，系统={}，SDK 目录={}", linux ? "Linux" : "Windows", absSdkDir);

        String cmsLib = new File(sdkDir, linux ? "libHCISUPCMS.so" : "HCISUPCMS.dll").getAbsolutePath();
        String streamLib = new File(sdkDir, linux ? "libHCISUPStream.so" : "HCISUPStream.dll").getAbsolutePath();
        String cryptoLib = new File(sdkDir, linux ? "libcrypto.so" : "libeay32.dll").getAbsolutePath();
        String sslLib = new File(sdkDir, linux ? "libssl.so" : "ssleay32.dll").getAbsolutePath();
        String componentDir = new File(sdkDir, "HCAapSDKCom").getAbsolutePath();

        checkFile(cmsLib, "HCISUPCMS");
        checkFile(streamLib, "HCISUPStream");
        checkFile(cryptoLib, linux ? "libcrypto.so" : "libeay32.dll");
        checkFile(sslLib, linux ? "libssl.so" : "ssleay32.dll");

        String logDir = new File("./logs/EHomeSDKLog").getAbsolutePath();

        // ---------- CMS ----------
        cms = Native.loadLibrary(cmsLib, HCISUPCMS.class);
        setPathCfg(cms, CRYPTO_SLOT, cryptoLib, "NET_ECMS_SetSDKInitCfg(crypto)");
        setPathCfg(cms, SSL_SLOT, sslLib, "NET_ECMS_SetSDKInitCfg(ssl)");
        if (!cms.NET_ECMS_Init()) {
            throw new IllegalStateException("NET_ECMS_Init 失败，错误码：" + cms.NET_ECMS_GetLastError());
        }
        setComponentDir(cms, componentDir, "NET_ECMS_SetSDKLocalCfg");
        cms.NET_ECMS_SetLogToFile(LOG_LEVEL, logDir, false);
        log.info("CMS SDK 初始化完成，版本号={}", cms.NET_ECMS_GetBuildVersion());

        // ---------- Stream ----------
        stream = Native.loadLibrary(streamLib, HCISUPStream.class);
        setStreamPathCfg(stream, CRYPTO_SLOT, cryptoLib, "NET_ESTREAM_SetSDKInitCfg(crypto)");
        setStreamPathCfg(stream, SSL_SLOT, sslLib, "NET_ESTREAM_SetSDKInitCfg(ssl)");
        if (!stream.NET_ESTREAM_Init()) {
            throw new IllegalStateException("NET_ESTREAM_Init 失败，错误码：" + stream.NET_ESTREAM_GetLastError());
        }
        setStreamComponentDir(stream, componentDir, "NET_ESTREAM_SetSDKLocalCfg");
        stream.NET_ESTREAM_SetLogToFile(LOG_LEVEL, logDir, false);
        log.info("Stream SDK 初始化完成，版本号={}", stream.NET_ESTREAM_GetBuildVersion());

        loaded = true;
        log.info("海康 ISUP SDK 加载完成");
    }

    /**
     * 释放 SDK：停止监听 -&gt; Fini。
     */
    public synchronized void unload(int cmsListenHandle, int previewListenHandle, int playbackListenHandle) {
        try {
            if (stream != null) {
                if (previewListenHandle >= 0) {
                    log.info("NET_ESTREAM_StopListenPreview，handle={}", previewListenHandle);
                    stream.NET_ESTREAM_StopListenPreview(previewListenHandle);
                }
                if (playbackListenHandle >= 0) {
                    log.info("NET_ESTREAM_StopListenPlayBack，handle={}", playbackListenHandle);
                    stream.NET_ESTREAM_StopListenPlayBack(playbackListenHandle);
                }
                stream.NET_ESTREAM_Fini();
                log.info("NET_ESTREAM_Fini 完成");
            }
        } catch (Exception e) {
            log.error("释放 Stream SDK 失败", e);
        }
        try {
            if (cms != null) {
                if (cmsListenHandle >= 0) {
                    log.info("NET_ECMS_StopListen，handle={}", cmsListenHandle);
                    cms.NET_ECMS_StopListen(cmsListenHandle);
                }
                cms.NET_ECMS_Fini();
                log.info("NET_ECMS_Fini 完成");
            }
        } catch (Exception e) {
            log.error("释放 CMS SDK 失败", e);
        }
        loaded = false;
        cms = null;
        stream = null;
    }

    // ==================== 内部工具 ====================

    private void setPathCfg(HCISUPCMS sdk, int slot, String path, String operation) {
        HCISUPCMS.BYTE_ARRAY array = new HCISUPCMS.BYTE_ARRAY(256);
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, array.byValue, 0, bytes.length);
        array.write();
        if (!sdk.NET_ECMS_SetSDKInitCfg(slot, array.getPointer())) {
            log.error("{} 失败，path={}, errorCode={}", operation, path, sdk.NET_ECMS_GetLastError());
        }
    }

    private void setStreamPathCfg(HCISUPStream sdk, int slot, String path, String operation) {
        HCISUPCMS.BYTE_ARRAY array = new HCISUPCMS.BYTE_ARRAY(256);
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, array.byValue, 0, bytes.length);
        array.write();
        if (!sdk.NET_ESTREAM_SetSDKInitCfg(slot, array.getPointer())) {
            log.error("{} 失败，path={}, errorCode={}", operation, path, sdk.NET_ESTREAM_GetLastError());
        }
    }

    private void setComponentDir(HCISUPCMS sdk, String dir, String operation) {
        HCISUPCMS.BYTE_ARRAY array = new HCISUPCMS.BYTE_ARRAY(256);
        byte[] bytes = dir.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, array.byValue, 0, bytes.length);
        array.write();
        if (!sdk.NET_ECMS_SetSDKLocalCfg(LOCAL_CFG_COMPONENT_DIR, array.getPointer())) {
            log.error("{} 失败，dir={}, errorCode={}", operation, dir, sdk.NET_ECMS_GetLastError());
        }
    }

    private void setStreamComponentDir(HCISUPStream sdk, String dir, String operation) {
        HCISUPCMS.BYTE_ARRAY array = new HCISUPCMS.BYTE_ARRAY(256);
        byte[] bytes = dir.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, array.byValue, 0, bytes.length);
        array.write();
        if (!sdk.NET_ESTREAM_SetSDKLocalCfg(LOCAL_CFG_COMPONENT_DIR, array.getPointer())) {
            log.error("{} 失败，dir={}, errorCode={}", operation, dir, sdk.NET_ESTREAM_GetLastError());
        }
    }

    private static void checkFile(String path, String name) {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalStateException("缺少海康 SDK 文件 " + name + "：" + path);
        }
    }

    public static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /** 将字符串写入结构体中的 byte[] 字段（保持数组长度不变，避免破坏结构体大小） */
    public static void copyTo(byte[] target, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(bytes.length, target.length);
        System.arraycopy(bytes, 0, target, 0, len);
    }

    /** 读取结构体 byte[] 字段中的字符串 */
    public static String readString(byte[] source) {
        int end = 0;
        while (end < source.length && source[end] != 0) {
            end++;
        }
        return new String(source, 0, end, StandardCharsets.UTF_8).trim();
    }

    /** 空指针保护 */
    public static boolean isNull(Pointer p) {
        return p == null || Pointer.NULL.equals(p);
    }
}
