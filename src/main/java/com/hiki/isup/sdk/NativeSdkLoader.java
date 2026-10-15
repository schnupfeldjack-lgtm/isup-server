package com.hiki.isup.sdk;

import com.hiki.isup.config.IsupProperties;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
 * <p>文件来源有两个，外部目录优先：</p>
 * <ol>
 *   <li>外部目录 {@code isup.sdk-dir}（默认 {@code ./sdk}）——换 SDK 不用重新打包；</li>
 *   <li>打进 jar 的 {@code src/main/resources/native/win64} 或 {@code native/linux64}
 *       ——启动时按需释放到 sdk-dir 缺失的那个文件，实现"开箱即用"。</li>
 * </ol>
 *
 * <p>初始化顺序严格遵循海康 Demo：
 * SetSDKInitCfg(crypto) -&gt; SetSDKInitCfg(ssl) -&gt; Init() -&gt; SetSDKLocalCfg(HCAapSDKCom)。</p>
 */
@Component
public class NativeSdkLoader {

    private static final Logger log = LoggerFactory.getLogger(NativeSdkLoader.class);

    /** 内置 SDK 在 classpath 中的根目录，按平台分子目录 */
    private static final String BUNDLED_ROOT = "native/";

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
        boolean linux = isLinux();
        if (!sdkDir.exists() && !sdkDir.mkdirs()) {
            throw new IllegalStateException("海康 SDK 目录不存在且无法创建：" + absSdkDir);
        }
        // 外部目录优先：只把本地缺的文件从 jar 里补出来
        extractBundledSdk(sdkDir, linux);
        // 让 JNA 在该目录里找库（Linux 下 libHCISUPCMS.so 依赖同目录的其它 so）
        System.setProperty("jna.library.path", absSdkDir);
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

    /**
     * 把打进 jar 的 SDK 文件释放到 sdk-dir。
     *
     * <p>只补本地缺失的文件：目标已存在就跳过，因此外部目录始终优先，
     * 线上换 SDK 只需往 ./sdk 丢文件，不必重新打包。</p>
     */
    private void extractBundledSdk(File sdkDir, boolean linux) {
        String root = BUNDLED_ROOT + (linux ? "linux64" : "win64") + "/";
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources("classpath*:" + root + "**");
        } catch (IOException e) {
            log.warn("扫描内置 SDK 资源失败，只使用外部目录：{}", e.getMessage());
            return;
        }
        int copied = 0;
        for (Resource resource : resources) {
            String rel = relativePath(resource, root);
            // 跳过目录条目（url 以 / 结尾）：否则目录会被当成 0 字节文件释放，
            // 导致其下子文件释放失败（parent 是文件而非目录）
            if (rel == null || rel.isBlank() || rel.endsWith("/")) {
                continue;
            }
            File target = new File(sdkDir, rel);
            if (target.exists()) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                File parent = target.getParentFile();
                if (parent != null) {
                    Files.createDirectories(parent.toPath());
                }
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                log.warn("释放内置 SDK 文件失败：{} -> {}，原因={}", rel, target, e.getMessage());
            }
        }
        if (copied > 0) {
            log.info("已从 jar 释放 {} 个海康 SDK 文件到 {}", copied, sdkDir.getAbsolutePath());
        }
    }

    /** 取资源在平台目录下的相对路径（兼容目录部署与 jar 内嵌两种 URL 形态） */
    private static String relativePath(Resource resource, String root) {
        try {
            String url = resource.getURL().toString();
            int idx = url.indexOf(root);
            if (idx < 0) {
                return null;
            }
            return URLDecoder.decode(url.substring(idx + root.length()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

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
            throw new IllegalStateException("缺少海康 SDK 文件 " + name + "：" + path
                    + "。请放进该目录，或放到 src/main/resources/native/"
                    + (isLinux() ? "linux64" : "win64") + "/ 后重新打包");
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
