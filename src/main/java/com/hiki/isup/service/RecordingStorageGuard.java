package com.hiki.isup.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;

/** 下载目录统一空间水位，覆盖原始流、排队文件及转码输出，不删除在用文件。 */
final class RecordingStorageGuard {
    static final long MAX_OUTPUT_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_TEMP_BYTES = 10L * 1024 * 1024 * 1024;
    static final long MIN_FREE_BYTES = 1024L * 1024 * 1024;

    private RecordingStorageGuard() { }

    static void checkOutput(Path output, long cap) throws IOException {
        if (cap <= 0) throw new IllegalArgumentException("录像输出上限无效");
        check(output.toAbsolutePath().normalize().getParent());
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) >= cap)
                throw new IOException("录像输出达到文件上限或文件类型不受支持，不能认定完整下载");
        }
    }

    static void check(Path sessionDirectory) throws IOException {
        Path root = sessionDirectory.toAbsolutePath().normalize().getParent();
        if (root == null) throw new IOException("录像会话目录没有父目录");
        checkRoot(root);
    }

    static void checkRoot(Path downloadRoot) throws IOException {
        checkRoot(downloadRoot, MAX_TEMP_BYTES, MIN_FREE_BYTES,
                path -> Files.getFileStore(path).getUsableSpace());
    }

    static void checkRoot(Path root, long maxBytes, long reserve, FreeSpace freeSpace) throws IOException {
        if (maxBytes <= 0 || reserve < 0) throw new IllegalArgumentException("录像空间预算无效");
        Path existing = root.toAbsolutePath().normalize();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || Files.isSymbolicLink(existing)) throw new IOException("无法确认录像磁盘目录");
        if (freeSpace.bytes(existing) < reserve) throw new IOException("录像磁盘低于保留空间 " + reserve + " bytes，暂停处理");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        final long[] used = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Thread.currentThread().isInterrupted()) throw new IOException("检查录像空间被中断");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) throw new IOException("录像目录存在符号链接，无法证明空间预算");
                if (attrs.isRegularFile()) {
                    if (attrs.size() >= maxBytes - used[0]) throw new IOException("录像临时文件达到总预算 " + maxBytes + " bytes，暂停处理");
                    used[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                // 其他会话正常清理产生的消失不是磁盘故障；其他访问失败必须保守退出。
                if (error instanceof java.nio.file.NoSuchFileException) return FileVisitResult.CONTINUE;
                throw error;
            }
        });
    }

    @FunctionalInterface interface FreeSpace { long bytes(Path path) throws IOException; }
}
