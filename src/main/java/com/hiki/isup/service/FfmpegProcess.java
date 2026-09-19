package com.hiki.isup.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个 ffmpeg 子进程。ISUP 码流通过 stdin 写入，ffmpeg 输出 HLS 或 MP4。
 *
 * <p>必须保证进程最终被销毁，否则会留下僵尸进程持续占用 CPU / 文件句柄。</p>
 */
public class FfmpegProcess {

    private static final Logger log = LoggerFactory.getLogger(FfmpegProcess.class);

    private final Process process;
    private final OutputStream stdin;
    private final String commandLine;
    private final AtomicBoolean stdinClosed = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    FfmpegProcess(Process process, String commandLine) {
        this.process = process;
        this.commandLine = commandLine;
        this.stdin = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
    }

    /**
     * 启动 stderr 消费线程，避免 ffmpeg 输出打满管道导致进程卡死。
     */
    void startStderrPump(String sessionId) {
        Thread t = new Thread(() -> pump(process.getErrorStream(), sessionId), "ffmpeg-stderr-" + sessionId);
        t.setDaemon(true);
        t.start();
    }

    private static void pump(InputStream in, String sessionId) {
        try (InputStream is = in) {
            byte[] buf = new byte[4096];
            int count = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                // ffmpeg -loglevel error 下输出很少，这里限量打印，避免刷爆日志
                if (count++ < 50) {
                    log.warn("[ffmpeg][{}] {}", sessionId, new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                }
            }
        } catch (IOException e) {
            log.debug("ffmpeg stderr 读取结束，sessionId={}", sessionId);
        }
    }

    /**
     * 写入码流数据。已经关闭或进程已退出时静默丢弃。
     */
    public synchronized void write(byte[] data, int offset, int len) {
        if (stdinClosed.get() || destroyed.get()) {
            return;
        }
        try {
            stdin.write(data, offset, len);
        } catch (IOException e) {
            log.warn("写入 ffmpeg stdin 失败（可能进程已退出），本次丢弃 {} 字节", len);
            closeStdinQuietly();
        }
    }

    /**
     * 关闭 stdin，通知 ffmpeg 输入结束（下载完成时用）。
     */
    public void closeStdin() {
        if (stdinClosed.compareAndSet(false, true)) {
            closeStdinQuietly();
        }
    }

    private void closeStdinQuietly() {
        try {
            stdin.flush();
        } catch (IOException ignored) {
            // ignore
        }
        try {
            stdin.close();
        } catch (IOException e) {
            log.debug("关闭 ffmpeg stdin 异常", e);
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public Integer exitValue() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return null;
        }
    }

    /**
     * 等待进程自然退出（关闭 stdin 后调用）。
     *
     * @return 正常退出返回 exit code，超时返回 null
     */
    public Integer waitFor(long timeoutSeconds) {
        try {
            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return process.exitValue();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 强制销毁进程并释放流。
     */
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            closeStdinQuietly();
            try {
                ProcessHandle handle = process.toHandle();
                handle.descendants().forEach(ProcessHandle::destroy);
            } catch (Exception ignored) {
                // ignore
            }
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            log.info("ffmpeg 进程已销毁，命令={}", commandLine);
        }
    }

    public String getCommandLine() {
        return commandLine;
    }

    static String toCommandLine(List<String> command) {
        return String.join(" ", command);
    }
}
