package com.hiki.isup.service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 与下载 writer 同步保存原始 PS；不在 SDK 回调中进行磁盘 IO。 */
final class RecordingSourceCapture extends OutputStream {
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long DISK_RESERVE = 512L * 1024 * 1024;
    private final Path path;
    private final long limit;
    private final long reserve;
    private final OutputStream output;
    private long written;
    private long nextDiskCheck;

    RecordingSourceCapture(Path path) throws IOException {
        this(path, MAX_BYTES, DISK_RESERVE);
    }

    RecordingSourceCapture(Path path, long limit, long reserve) throws IOException {
        if (limit <= 0 || reserve < 0) throw new IllegalArgumentException("录像保存上限无效");
        this.path = path.toAbsolutePath();
        this.limit = limit;
        this.reserve = reserve;
        Files.createDirectories(this.path.getParent());
        checkDisk();
        output = new BufferedOutputStream(Files.newOutputStream(this.path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 256 * 1024);
    }

    @Override public void write(int value) throws IOException {
        write(new byte[]{(byte)value}, 0, 1);
    }

    @Override public void write(byte[] data, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, data.length);
        if (length > limit - written) throw new IOException("原始录像超过保存上限 " + limit + " bytes");
        if (written >= nextDiskCheck) {
            checkDisk();
            nextDiskCheck = written + 8L * 1024 * 1024;
        }
        output.write(data, offset, length);
        written += length;
    }

    private void checkDisk() throws IOException {
        RecordingStorageGuard.check(path.getParent());
        if (Files.getFileStore(path.getParent()).getUsableSpace() < reserve) {
            throw new IOException("录像目录可用空间不足，至少保留 " + reserve + " bytes");
        }
    }

    @Override public void flush() throws IOException { output.flush(); }
    @Override public void close() throws IOException { output.close(); }
}
