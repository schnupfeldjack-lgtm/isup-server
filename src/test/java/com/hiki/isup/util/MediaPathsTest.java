package com.hiki.isup.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 媒体路径工具测试（含路径穿越防护）。
 */
class MediaPathsTest {

    private static final String MEDIA_DIR = "./data/media";

    @Test
    @DisplayName("预览/回放/下载目录分别落在 media-dir 下的对应子目录")
    void directories() {
        String sessionId = UUID.randomUUID().toString();
        Path root = MediaPaths.mediaRoot(MEDIA_DIR);

        assertTrue(MediaPaths.previewDir(MEDIA_DIR, sessionId).startsWith(root.resolve("preview")));
        assertTrue(MediaPaths.playbackDir(MEDIA_DIR, sessionId).startsWith(root.resolve("playback")));
        assertTrue(MediaPaths.downloadDir(MEDIA_DIR, sessionId).startsWith(root.resolve("download")));
        assertEquals("index.m3u8", MediaPaths.previewDir(MEDIA_DIR, sessionId).resolve("index.m3u8").getFileName().toString());
    }

    @Test
    @DisplayName("UUID 形态的 id 合法，路径穿越尝试一律拒绝")
    void safeId() {
        assertTrue(MediaPaths.isSafeId(UUID.randomUUID().toString()));
        assertFalse(MediaPaths.isSafeId("../etc/passwd"));
        assertFalse(MediaPaths.isSafeId("..%2f..%2fetc"));
        assertFalse(MediaPaths.isSafeId("abc/def"));
        assertFalse(MediaPaths.isSafeId(null));
        assertFalse(MediaPaths.isSafeId("a"));
    }

    @Test
    @DisplayName("只能下载位于 media-dir 内的文件")
    void insideMediaRoot() {
        String taskId = UUID.randomUUID().toString();
        Path inside = MediaPaths.downloadDir(MEDIA_DIR, taskId).resolve("video.mp4");
        assertTrue(MediaPaths.isInsideMediaRoot(MEDIA_DIR, inside.toFile()));

        File outside = new File("C:/Windows/win.ini");
        assertFalse(MediaPaths.isInsideMediaRoot(MEDIA_DIR, outside));
    }

    @Test
    @DisplayName("资源映射 location 必须以斜杠结尾")
    void trailingSlash() {
        assertEquals("/data/media/", MediaPaths.ensureTrailingSlash("/data/media"));
        assertEquals("/data/media/", MediaPaths.ensureTrailingSlash("/data/media/"));
    }
}
