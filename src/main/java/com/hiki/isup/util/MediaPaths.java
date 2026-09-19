package com.hiki.isup.util;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 媒体输出路径工具。
 *
 * <p>HLS 切片与下载文件都放在 isup.media-dir 下：</p>
 * <pre>
 * {mediaDir}/preview/{sessionId}/index.m3u8
 * {mediaDir}/playback/{sessionId}/index.m3u8
 * {mediaDir}/download/{taskId}/xxx.mp4
 * </pre>
 */
public final class MediaPaths {

    /** sessionId / taskId 只接受纯 UUID 格式，防止路径穿越 */
    private static final Pattern SAFE_ID = Pattern.compile("^[0-9a-fA-F-]{8,64}$");

    private MediaPaths() {
    }

    public static Path mediaRoot(String mediaDir) {
        return Path.of(mediaDir).toAbsolutePath().normalize();
    }

    public static Path previewDir(String mediaDir, String sessionId) {
        return mediaRoot(mediaDir).resolve("preview").resolve(sessionId);
    }

    public static Path playbackDir(String mediaDir, String sessionId) {
        return mediaRoot(mediaDir).resolve("playback").resolve(sessionId);
    }

    public static Path downloadDir(String mediaDir, String taskId) {
        return mediaRoot(mediaDir).resolve("download").resolve(taskId);
    }

    /**
     * 校验 sessionId / taskId 是否安全（仅允许 UUID 形态），防止路径穿越。
     */
    public static boolean isSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /**
     * 校验文件确实位于媒体根目录之内（防止越权下载任意文件）。
     */
    public static boolean isInsideMediaRoot(String mediaDir, File file) {
        if (file == null) {
            return false;
        }
        Path root = mediaRoot(mediaDir);
        Path target = file.toPath().toAbsolutePath().normalize();
        return target.startsWith(root);
    }

    public static String ensureTrailingSlash(String path) {
        if (path == null) {
            return null;
        }
        return path.endsWith("/") || path.endsWith("\\") ? path : path + "/";
    }
}
