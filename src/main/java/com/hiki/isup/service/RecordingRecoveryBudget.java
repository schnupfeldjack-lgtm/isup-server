package com.hiki.isup.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.LongSupplier;

public final class RecordingRecoveryBudget {
    private static final long HOUR = 3600000L, DAY = 24 * HOUR;
    private final Path file;
    private final LongSupplier clock;

    public RecordingRecoveryBudget(Path file, LongSupplier clock) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock);
    }

    /** Advisory only: reserve rechecks under an interprocess lock. Never creates state. */
    public synchronized boolean available() throws IOException {
        long now = now();
        return permitted(read(now), now);
    }

    /** A successful durable reservation is consumed even if the caller never exits. */
    public synchronized boolean reserve() throws IOException {
        // Check corrupt/future evidence before creating even the lock or directory.
        read(now());
        createDirectory(file.getParent());
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { return false; }
            if (lock == null) return false;
            try (lock) {
                long now = now();
                List<Long> records = read(now);
                if (!permitted(records, now)) return false;
                records.add(now);
                persist(records);
                return true;
            }
        }
    }

    private long now() throws IOException {
        long value = clock.getAsLong();
        if (value < 0) throw new IOException("Invalid recovery clock");
        return value;
    }

    private List<Long> read(long now) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException absent) { return new ArrayList<>(); }
        if (!attributes.isRegularFile() || attributes.size() > 1024) throw new IOException("Invalid recovery budget file");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length < 4 || !lines[0].equals("ISUP-RECOVERY-1") || !lines[lines.length - 1].isEmpty()) {
            throw new IOException("Malformed recovery budget");
        }
        String payload = text.substring(0, text.lastIndexOf(lines[lines.length - 2]));
        if (!lines[lines.length - 2].equals(digest(payload))) throw new IOException("Recovery budget checksum mismatch");
        List<Long> records = new ArrayList<>();
        long previous = -1;
        try {
            for (int i = 1; i < lines.length - 2; i++) {
                if (!lines[i].matches("0|[1-9][0-9]*")) throw new NumberFormatException("timestamp");
                long at = Long.parseLong(lines[i]);
                if (at < previous || at > now) throw new IOException("Recovery budget timestamps invalid or in future");
                previous = at;
                if (now - at < DAY) records.add(at);
            }
        } catch (NumberFormatException malformed) { throw new IOException("Malformed recovery timestamp", malformed); }
        if (lines.length - 3 > 3) throw new IOException("Too many recovery records");
        return records;
    }

    private static boolean permitted(List<Long> records, long now) {
        return records.size() < 3 && (records.isEmpty() || now - records.getLast() >= HOUR);
    }

    private void persist(List<Long> records) throws IOException {
        StringBuilder payload = new StringBuilder("ISUP-RECOVERY-1\n");
        records.forEach(at -> payload.append(at).append('\n'));
        byte[] bytes = (payload.toString() + digest(payload.toString()) + "\n").getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(file.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void createDirectory(Path directory) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) throw new IOException("Invalid recovery budget directory");
        } catch (NoSuchFileException absent) {
            if (directory.getParent() != null) createDirectory(directory.getParent());
            try { Files.createDirectory(directory); }
            catch (FileAlreadyExistsException raced) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw raced;
            }
        }
        if (directory.getParent() != null) forceDirectory(directory.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Matches RecordingRetryPolicy: Windows has no directory FileChannel fsync support.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static String digest(String value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}
