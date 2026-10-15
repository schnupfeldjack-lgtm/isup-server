package com.hiki.isup.service;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** 有界回收校验/裁切进程，同时保留取消任务的中断标记。 */
final class RecordingProcessCleanup {
    private RecordingProcessCleanup() { }

    static void finish(Process process, Thread reader) throws IOException {
        boolean interrupted = Thread.interrupted();
        IOException failure = null;
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        failure = new IOException("录像处理进程强制终止后仍未退出");
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    failure = new IOException("等待录像处理进程回收时再次被中断", e);
                }
            }
            close(process.getInputStream());
            close(process.getErrorStream());
            close(process.getOutputStream());
            try {
                reader.join(2000);
                if (reader.isAlive()) failure = new IOException("录像处理日志线程未完成回收", failure);
            } catch (InterruptedException e) {
                interrupted = true;
                failure = new IOException("等待录像日志线程回收时被中断", e);
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) throw failure;
    }

    private static void close(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }
}
