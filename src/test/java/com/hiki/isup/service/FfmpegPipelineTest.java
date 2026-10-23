package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FFmpeg 转封装链路集成测试：验证「PS 码流 -&gt; stdin -&gt; ffmpeg -&gt; HLS / MP4」真的能出产物。
 *
 * <p>需要本机安装 ffmpeg，且默认跳过。运行时加参数：</p>
 * <pre>
 *   mvn test -Disup.ffmpeg.it=true -Disup.ffmpeg.it.path=C:/Users/15515/ffmpeg/bin/ffmpeg.exe
 * </pre>
 *
 * <p>不依赖真实海康 SDK 与设备。</p>
 */
class FfmpegPipelineTest {

    private static final String FFMPEG = System.getProperty("isup.ffmpeg.it.path", "ffmpeg");

    @Test
    @EnabledIfSystemProperty(named = "isup.ffmpeg.it", matches = "true")
    void historicalPlaybackDrainsWritesEndlistAndKeepsAllSegments() throws Exception {
        var properties = new IsupProperties(); properties.setFfmpegPath(FFMPEG);
        properties.getFfmpeg().setHlsListSize(2);
        Path ps = generateTestPs("event", 20);
        Path dir = Path.of("target", "test-playback-" + UUID.randomUUID());
        var manager = new SessionManager(new com.hiki.isup.sdk.NativeSdkLoader(), properties);
        var session = manager.create(com.hiki.isup.model.SessionType.PLAYBACK, "local-test", 1);
        session.enableViewerLease(); session.setOutputDir(dir.toString());
        var process = new FfmpegService().startPlaybackHls(properties, dir, session.getSessionId());
        session.setFfmpegProcess(process);
        try {
            pipe(ps, process);
            // 与设备结束信令完全相同的停止路径，必须排空输入再保留尾部。
            manager.stopAsync(session, com.hiki.isup.model.SessionStatus.COMPLETED, null);
            assertEquals(0, process.waitFor(30));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (session.getFfmpegProcess() != null && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(com.hiki.isup.model.SessionStatus.COMPLETED, session.getStatus());
            String playlist = Files.readString(dir.resolve("index.m3u8"));
            assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:EVENT"));
            assertTrue(playlist.contains("#EXT-X-ENDLIST"));
            var segments = playlist.lines().filter(line -> line.endsWith(".ts")).toList();
            assertTrue(segments.size() >= 9, "历史列表须保留全部 20 秒，不能滚动掉开头");
            for (String segment : segments) assertTrue(Files.size(dir.resolve(segment)) > 0);
            assertEquals(0, manager.resourceSnapshot().reservedSlots());
            assertEquals(session, manager.heartbeat(session.getSessionId()));
        } finally { manager.shutdown(); }
    }

    @Test
    @EnabledIfSystemProperty(named = "isup.ffmpeg.it", matches = "true")
    @DisplayName("PS 码流转封装为 HLS：生成 index.m3u8 与 ts 切片")
    void psToHls() throws Exception {
        IsupProperties properties = new IsupProperties();
        properties.setFfmpegPath(FFMPEG);

        Path ps = generateTestPs("hls");
        Path dir = Path.of("target", "test-hls-" + UUID.randomUUID());

        FfmpegProcess process = new FfmpegService().startHls(properties, dir, "it-hls");
        try {
            pipe(ps, process);
            process.closeStdin();
            Integer exit = process.waitFor(60);
            assertNotNull(exit, "ffmpeg 应在 60 秒内退出");
            assertEquals(0, exit, "ffmpeg 退出码应为 0");

            assertTrue(Files.exists(dir.resolve("index.m3u8")), "应生成 index.m3u8");
            String m3u8 = Files.readString(dir.resolve("index.m3u8"));
            assertTrue(m3u8.contains("#EXTM3U"), "m3u8 内容应包含 #EXTM3U");
            assertTrue(Files.list(dir).anyMatch(p -> p.getFileName().toString().endsWith(".ts")),
                    "应生成至少一个 ts 切片");
        } finally {
            process.destroy();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "isup.ffmpeg.it", matches = "true")
    @DisplayName("PS 码流转封装为 MP4：生成可播放的 mp4 文件")
    void psToMp4() throws Exception {
        IsupProperties properties = new IsupProperties();
        properties.setFfmpegPath(FFMPEG);

        Path ps = generateTestPs("mp4");
        Path file = Path.of("target", "test-dl-" + UUID.randomUUID(), "record.mp4");

        FfmpegProcess process = new FfmpegService().startMp4(properties, file, "it-mp4");
        try {
            pipe(ps, process);
            process.closeStdin();
            Integer exit = process.waitFor(60);
            assertNotNull(exit, "ffmpeg 应在 60 秒内退出");
            assertEquals(0, exit, "ffmpeg 退出码应为 0");

            assertTrue(Files.exists(file), "应生成 mp4 文件");
            assertTrue(Files.size(file) > 0, "mp4 文件不应为空");
        } finally {
            process.destroy();
        }
    }

    private void pipe(Path source, FfmpegProcess process) throws Exception {
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(source)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                process.write(buffer, 0, n);
            }
        }
    }

    /**
     * 用 ffmpeg 生成一段 MPEG-PS 测试码流（模拟设备推来的 PS 流）。
     */
    private Path generateTestPs(String tag) throws Exception {
        return generateTestPs(tag, 4);
    }

    private Path generateTestPs(String tag, int seconds) throws Exception {
        Path ps = Path.of("target", "test-" + tag + "-" + UUID.randomUUID() + ".ps");
        Files.createDirectories(ps.getParent());
        ProcessBuilder builder = new ProcessBuilder(
                FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + seconds + ":size=320x240:rate=25",
                "-c:v", "libx264", "-bf", "0", "-g", "50", "-pix_fmt", "yuv420p",
                "-f", "mpeg", ps.toString());
        Process p = builder.start();
        assertTrue(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "生成测试 PS 超时");
        assertEquals(0, p.exitValue(), "生成测试 PS 失败");
        assertTrue(Files.size(ps) > 0, "测试 PS 文件不应为空");
        return ps;
    }
}
