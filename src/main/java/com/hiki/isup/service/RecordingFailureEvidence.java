package com.hiki.isup.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 每类仅保留首个原始失败样本一天；诊断文件不参与录像成功判定或业务重试。 */
final class RecordingFailureEvidence {
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final List<String> CATEGORIES = List.of("structure", "timestamp", "coverage", "decode");
    private static final List<String> SUFFIXES = List.of(".ps", ".properties", ".ps.tmp", ".properties.tmp");
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long RETENTION_MILLIS = 24L * 60 * 60 * 1000;
    private RecordingFailureEvidence() { }

    static Path preserve(Path mediaRoot, Path source, String recordId, int channel,
                         LocalDateTime start, LocalDateTime end, Throwable error,
                         long deadlineNanos) throws IOException {
        checkDeadline(deadlineNanos);
        long waitNanos = deadlineNanos == Long.MAX_VALUE ? TimeUnit.SECONDS.toNanos(1)
                : Math.max(0, Math.min(TimeUnit.SECONDS.toNanos(1), deadlineNanos - System.nanoTime()));
        try {
            if (!LOCK.tryLock(waitNanos, TimeUnit.NANOSECONDS)) return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("等待故障样本采证锁被中断");
            failure.initCause(interrupted);
            throw failure;
        }
        try {
            return preserveLocked(mediaRoot, source, recordId, channel, start, end, error, deadlineNanos);
        } finally { LOCK.unlock(); }
    }

    private static Path preserveLocked(Path mediaRoot, Path source, String recordId, int channel,
                                       LocalDateTime start, LocalDateTime end, Throwable error,
                                       long deadlineNanos) throws IOException {
        checkDeadline(deadlineNanos);
        Path root = mediaRoot.toAbsolutePath().normalize();
        Path original = source.toAbsolutePath().normalize();
        requireDirectoryChain(root);
        if (!original.startsWith(root) || original.equals(root)) throw new IOException("故障样本不在录像根目录内");
        requireDirectoryChain(original.getParent());
        BasicFileAttributes before = requireRegularFile(original);
        if (before.size() == 0 || before.size() > MAX_BYTES) return null;
        Path directory = root.resolve("recording-failures");
        if (original.startsWith(directory)) throw new IOException("不能将故障样本目录作为采证来源");
        pruneLocked(root);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, attributes(root, true));
        }
        requireDirectoryChain(directory);
        restrictPermissions(directory, true);
        String category = category(error);
        Path saved = directory.resolve(category + ".ps");
        Path metadata = directory.resolve(category + ".properties");
        if (Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) return null;
        Path temporary = directory.resolve(category + ".ps.tmp");
        Path temporaryMetadata = directory.resolve(category + ".properties.tmp");
        boolean promoted = false;
        boolean complete = false;
        try {
            checkDeadline(deadlineNanos);
            RecordingStorageGuard.checkRoot(directory);
            MessageDigest digest = sha256();
            long copied = 0;
            long nextStorageCheck = 0;
            try (var input = Files.newInputStream(original, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var channelOutput = Files.newByteChannel(temporary,
                         Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), attributes(directory, false));
                 var output = Channels.newOutputStream(channelOutput)) {
                byte[] buffer = new byte[65_536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    checkDeadline(deadlineNanos);
                    if (copied >= nextStorageCheck) {
                        requireDirectoryChain(directory);
                        RecordingStorageGuard.checkRoot(directory);
                        nextStorageCheck = copied + 8L * 1024 * 1024;
                    }
                    if (count > MAX_BYTES - copied) throw new IOException("故障原始样本复制时超过 64 MiB 上限");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    copied += count;
                }
            }
            checkDeadline(deadlineNanos);
            requireDirectoryChain(original.getParent());
            BasicFileAttributes after = requireRegularFile(original);
            if (copied != before.size() || after.size() != copied
                    || !after.lastModifiedTime().equals(before.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())) {
                throw new IOException("原始录像在故障采证期间发生变化");
            }
            Properties values = new Properties();
            values.setProperty("recordId", bounded(recordId, 512));
            values.setProperty("channel", Integer.toString(channel));
            values.setProperty("start", String.valueOf(start));
            values.setProperty("end", String.valueOf(end));
            values.setProperty("error", bounded(error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage(), 1000));
            values.setProperty("sha256", HexFormat.of().formatHex(digest.digest()));
            values.setProperty("sourceSize", Long.toString(copied));
            values.setProperty("runtimeVersion", bounded(Objects.requireNonNullElse(
                    RecordingFailureEvidence.class.getPackage().getImplementationVersion(), "development"), 256));
            values.setProperty("capturedAt", LocalDateTime.now().toString());
            values.setProperty("category", category);
            try (var channelOutput = Files.newByteChannel(temporaryMetadata,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), attributes(directory, false));
                 var output = Channels.newOutputStream(channelOutput)) {
                values.store(output, "Recording failure evidence");
            }
            checkDeadline(deadlineNanos);
            requireDirectoryChain(directory);
            RecordingStorageGuard.checkRoot(directory);
            // 不回退为非原子拷贝；不支持原子改名时由调用者记录采证失败。
            Files.move(temporary, saved, StandardCopyOption.ATOMIC_MOVE);
            promoted = true;
            Files.move(temporaryMetadata, metadata, StandardCopyOption.ATOMIC_MOVE);
            complete = true;
            return saved;
        } finally {
            // 目标目录若被替换成链接，宁可报告清理失败，也不沿链接删除其他位置。
            requireDirectoryChain(directory);
            deleteRegularIfPresent(temporary);
            deleteRegularIfPresent(temporaryMetadata);
            if (promoted && !complete) deleteRegularIfPresent(saved);
        }
    }

    static void prune(Path mediaRoot) throws IOException {
        if (!LOCK.tryLock()) return;
        try { pruneLocked(mediaRoot); }
        finally { LOCK.unlock(); }
    }

    private static void pruneLocked(Path mediaRoot) throws IOException {
        Path root = mediaRoot.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        requireDirectoryChain(root);
        Path directory = root.resolve("recording-failures");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        requireDirectoryChain(directory);
        restrictPermissions(directory, true);
        // 目录只接受固定槽位，拒绝未知文件及链接，避免忽略额外占用或遍历外部路径。
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean known = CATEGORIES.stream().anyMatch(c -> SUFFIXES.stream().anyMatch(s -> name.equals(c + s)));
                if (!known) throw new IOException("故障样本目录存在未知文件");
                requireRegularFile(entry);
            }
        }
        long now = System.currentTimeMillis();
        for (String category : CATEGORIES) {
            checkDeadline(Long.MAX_VALUE);
            Path sample = directory.resolve(category + ".ps");
            Path metadata = directory.resolve(category + ".properties");
            deleteRegularIfPresent(directory.resolve(category + ".ps.tmp"));
            deleteRegularIfPresent(directory.resolve(category + ".properties.tmp"));
            BasicFileAttributes sampleAttributes = regularFileIfPresent(sample);
            BasicFileAttributes metadataAttributes = regularFileIfPresent(metadata);
            boolean complete = sampleAttributes != null && metadataAttributes != null;
            boolean expired = complete && now - Math.max(sampleAttributes.lastModifiedTime().toMillis(),
                    metadataAttributes.lastModifiedTime().toMillis()) >= RETENTION_MILLIS;
            if (!complete || expired) {
                deleteRegularIfPresent(sample);
                deleteRegularIfPresent(metadata);
            } else {
                if (sampleAttributes.size() > MAX_BYTES || metadataAttributes.size() > 16_384) {
                    throw new IOException("故障样本目录已有文件超过容量上限");
                }
                restrictPermissions(sample, false);
                restrictPermissions(metadata, false);
            }
        }
    }

    private static String category(Throwable error) {
        // 先看整个 cause 链的强类型/时间异常，外层通用 PS 错误不能覆盖它们。
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordingCoverageException) return "coverage";
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("pts") || message.contains("dts") || message.contains("时间轴")
                    || message.contains("墙钟") || message.contains("锚点") || message.contains("时间戳")) return "timestamp";
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (cause instanceof java.io.EOFException || message.contains("ps") || message.contains("eof")
                    || message.contains("包头") || message.contains("截断") || message.contains("描述符")) return "structure";
        }
        return "decode";
    }

    private static void requireDirectoryChain(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("故障样本路径包含链接或非常规目录");
            }
        }
    }

    private static BasicFileAttributes requireRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("故障样本路径不是普通文件");
        }
        return attributes;
    }

    private static BasicFileAttributes regularFileIfPresent(Path path) throws IOException {
        try { return requireRegularFile(path); }
        catch (NoSuchFileException absent) { return null; }
    }

    private static void deleteRegularIfPresent(Path path) throws IOException {
        if (regularFileIfPresent(path) != null) Files.delete(path);
    }

    private static FileAttribute<?>[] attributes(Path directory, boolean isDirectory) throws IOException {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return new FileAttribute<?>[0];
        return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(
                isDirectory ? "rwx------" : "rw-------"))};
    }

    private static void restrictPermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        }
    }

    private static void checkDeadline(long deadlineNanos) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("故障样本采证被中断");
        RecordingDeadline.check(deadlineNanos);
    }

    private static String bounded(String value, int maximum) {
        String text = String.valueOf(value);
        return text.substring(0, Math.min(text.length(), maximum));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
