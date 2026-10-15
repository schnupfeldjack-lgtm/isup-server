package com.hiki.isup.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * 单实例耐久重试台账。活动记录有界，终态按 ID 的 SHA-256 前缀分片保存。
 * 主快照原子指向已强制落盘的不可变分片；分片丢失/损坏不能被解释为新任务。
 * 整个主文件和 .archive 目录须一起保留；不能靠删除台账恢复任务。
 */
final class RecordingRetryPolicy {
    private static final long MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RECOVERIES = 64;
    private final Path file;
    private final Path archive;
    private final int maxAttempts;
    private final int maxEntries;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final long maxAgeMillis;
    private final LongSupplier clock;
    private final DirectorySync directorySync;
    private Map<String, Entry> entries = new HashMap<>();
    private Map<String, Shard> shards = new HashMap<>();
    private Map<String, Long> shardStorageBytes = new HashMap<>();
    private long cachedStorageBytes;
    private long storageSampledAt;
    private String committedRootDigest;
    private String committedRootFormat;
    private IOException uncertainRootWrite;

    RecordingRetryPolicy(Path file, int maxAttempts, int maxEntries, long baseDelayMillis,
                         long maxDelayMillis, long maxAgeMillis, LongSupplier clock) throws IOException {
        this(file, maxAttempts, maxEntries, baseDelayMillis, maxDelayMillis, maxAgeMillis,
                clock, RecordingRetryPolicy::forceDirectory);
    }

    RecordingRetryPolicy(Path file, int maxAttempts, int maxEntries, long baseDelayMillis,
                         long maxDelayMillis, long maxAgeMillis, LongSupplier clock, DirectorySync directorySync)
            throws IOException {
        this.file = file.toAbsolutePath().normalize();
        archive = this.file.resolveSibling(this.file.getFileName() + ".archive");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxEntries = Math.max(1, maxEntries);
        this.baseDelayMillis = Math.max(1, baseDelayMillis);
        this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
        this.maxAgeMillis = Math.max(1, maxAgeMillis);
        this.clock = clock;
        this.directorySync = directorySync;
        load();
        sampleStorageAfterLoad();
    }

    synchronized boolean canAttempt(String id, String fingerprint) throws IOException {
        verifyCurrentRoot();
        validateIdentity(id, fingerprint);
        Entry current = find(id);
        if (current != null && current.fingerprint.equals(fingerprint) && !current.completed) {
            if (current.grant) {
                ensureCapacity(id);
                return true;
            }
            if (terminal(current)) {
                if (entries.containsKey(id)) change(id, current, true);
                return false;
            }
            return clock.getAsLong() >= current.nextAt;
        }
        ensureCapacity(id);
        return true;
    }

    synchronized boolean beginAttempt(String id, String fingerprint) throws IOException {
        if (!canAttempt(id, fingerprint)) return false;
        ensureCapacity(id);
        Entry old = find(id);
        long now = clock.getAsLong();
        boolean same = old != null && old.fingerprint.equals(fingerprint) && !old.completed;
        int attempts = same ? old.attempts + 1 : 1;
        long nextAt = Math.max(same ? old.firstAt : now, safeAdd(now, delayFor(attempts)));
        Entry next = new Entry(fingerprint, attempts, old == null ? 1 : old.totalAttempts + 1,
                same ? old.firstAt : now, nextAt, same && old.grant, revision(),
                "处理中", false, false, old == null ? List.of() : old.audit);
        change(id, next, false);
        return true;
    }

    synchronized void failed(String id, boolean permanent) throws IOException { failed(id, permanent, null); }

    synchronized void failed(String id, boolean permanent, String reason) throws IOException {
        verifyCurrentRoot();
        Entry old = find(id);
        if (old == null || old.completed) return;
        String failure = reason == null || reason.isBlank()
                ? (old.reason.equals("处理中") ? (permanent ? "永久校验失败" : "暂时失败") : old.reason)
                : bounded(reason, 1000, "失败原因");
        Entry next = new Entry(old.fingerprint, old.attempts, old.totalAttempts, old.firstAt,
                Math.max(old.nextAt, safeAdd(clock.getAsLong(), delayFor(old.attempts))),
                old.permanent || permanent, revision(), failure, old.grant, false, old.audit);
        change(id, next, next.grant || terminal(next));
    }

    synchronized void succeeded(String id) throws IOException {
        verifyCurrentRoot();
        Entry old = find(id);
        if (old == null) return;
        Entry retained = old.audit.isEmpty() ? null : new Entry(old.fingerprint, old.attempts,
                old.totalAttempts, old.firstAt, old.nextAt, false, revision(), "处理成功", false, true, old.audit);
        change(id, retained, retained != null);
    }

    synchronized Snapshot inspect(String id) throws IOException {
        verifyCurrentRoot();
        bounded(id, 512, "记录 ID");
        Entry entry = find(id);
        return entry == null ? null : snapshot(id, entry);
    }

    /** 只授予一次额外尝试；不清历史次数，不重置年龄，审计与授权一起原子提交。 */
    synchronized Snapshot release(String id, String expectedRevision, String expectedFingerprint, String reason)
            throws IOException {
        verifyCurrentRoot();
        bounded(id, 512, "记录 ID");
        reason = bounded(reason, 1000, "恢复原因");
        Entry old = find(id);
        if (old == null || old.completed || old.grant || !terminal(old)) {
            throw new IllegalArgumentException("只允许恢复已隔离且没有待消费授权的已知记录");
        }
        if (!old.revision.equals(expectedRevision) || !old.fingerprint.equals(expectedFingerprint)) {
            throw new IllegalArgumentException("记录版本或输入已变化，请重新查询后核对");
        }
        if (old.audit.size() >= MAX_RECOVERIES) throw new IllegalArgumentException("该记录恢复次数已达上限，需人工核查");
        List<RecoveryAudit> history = new ArrayList<>(old.audit);
        history.add(new RecoveryAudit(clock.getAsLong(), old.revision, reason));
        Entry next = new Entry(old.fingerprint, old.attempts, old.totalAttempts, old.firstAt,
                Math.max(old.firstAt, clock.getAsLong()), old.permanent, revision(), "人工恢复待执行：" + reason,
                true, false, List.copyOf(history));
        change(id, next, true);
        return snapshot(id, next);
    }

    synchronized Stats stats() throws IOException {
        long quarantined = 0, pending = 0;
        for (Shard shard : shards.values()) {
            quarantined += shard.quarantined;
            pending += shard.grants;
        }
        int active = 0;
        for (Entry e : entries.values()) {
            if (terminal(e)) quarantined++;
            else active++;
        }
        return new Stats(active, maxEntries, quarantined, pending,
                active >= Math.max(1, (int) Math.ceil(maxEntries * .8)),
                active >= maxEntries, cachedStorageBytes, storageSampledAt, true);
    }

    /** 启动时完整校验后的元数据快照；GET 不再扫描历史分片或执行磁盘 I/O。 */
    private void sampleStorageAfterLoad() throws IOException {
        Map<String, Long> sizes = new HashMap<>();
        for (Map.Entry<String, Shard> item : shards.entrySet()) {
            sizes.put(item.getKey(), Files.size(shardPath(item.getKey(), item.getValue())));
        }
        updateStorageSummary(Files.size(file), sizes);
    }

    private void updateStorageSummary(long rootBytes, Map<String, Long> sizes) {
        shardStorageBytes = sizes;
        cachedStorageBytes = rootBytes + sizes.values().stream().mapToLong(Long::longValue).sum();
        storageSampledAt = clock.getAsLong();
    }

    private Snapshot snapshot(String id, Entry e) {
        String reason = e.reason;
        if (!e.completed && !e.grant && terminal(e)) {
            String state = e.permanent ? "永久失败" : e.attempts >= maxAttempts ? "次数耗尽" : "超过重试年龄";
            reason = state + "：" + reason;
        }
        return new Snapshot(id, e.fingerprint, e.revision, e.attempts, e.totalAttempts, e.firstAt,
                e.nextAt, !e.completed && (terminal(e) || e.grant), e.grant, reason, e.audit);
    }

    private void ensureCapacity(String id) throws IOException {
        if (entries.containsKey(id) || entries.size() < maxEntries) return;
        archiveTerminal();
        if (!entries.containsKey(id) && entries.size() >= maxEntries) {
            throw new IOException("录像活动重试台账达到容量上限 " + maxEntries + "，暂停领取，需核查积压");
        }
    }

    private boolean terminal(Entry e) {
        return !e.completed && !e.grant && (e.permanent || e.attempts >= maxAttempts
                || clock.getAsLong() - e.firstAt >= maxAgeMillis);
    }

    private Entry find(String id) throws IOException {
        Entry active = entries.get(id);
        return active != null ? active : readShard(bucket(id)).get(id);
    }

    private void archiveTerminal() throws IOException {
        Map<String, Entry> next = new HashMap<>(entries);
        Map<String, Map<String, Entry>> updates = new HashMap<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (!terminal(item.getValue())) continue;
            String bucket = bucket(item.getKey());
            if (!updates.containsKey(bucket)) updates.put(bucket, readShard(bucket));
            updates.get(bucket).put(item.getKey(), item.getValue());
            next.remove(item.getKey());
        }
        if (!updates.isEmpty()) commit(next, updates);
    }

    private void change(String id, Entry value, boolean archived) throws IOException {
        String bucket = bucket(id);
        Map<String, Entry> next = new HashMap<>(entries);
        Map<String, Entry> saved = readShard(bucket);
        boolean hadArchived = saved.remove(id) != null;
        next.remove(id);
        if (value != null) { if (archived) saved.put(id, value); else next.put(id, value); }
        commit(next, hadArchived || archived ? Map.of(bucket, saved) : Map.of());
    }

    private void load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) throw new IOException("录像主台账丢失，拒绝清零");
            createDirectoryDurably(file.getParent());
            createDirectoryDurably(archive);
            persistRoot(entries, shards);
            return;
        }
        Properties saved = read(file, null);
        String format = saved.getProperty("@format");
        saved.remove("@format");
        if (!"1".equals(format) && !"2".equals(format)) throw new IOException("录像台账版本无效，拒绝清零");
        committedRootDigest = checksum(saved);
        committedRootFormat = format;
        boolean legacy = "1".equals(format);
        try {
            for (String key : saved.stringPropertyNames()) {
                if (key.startsWith("@archive.")) {
                    if (legacy) throw new IllegalArgumentException("legacy shard");
                    String prefix = key.substring(9);
                    if (!prefix.matches("[0-9a-f]{2}")) throw new IllegalArgumentException("shard name");
                    String[] values = saved.getProperty(key).split(",", -1);
                    if (values.length != 4 || !values[0].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("shard");
                    Shard shard = new Shard(values[0], Integer.parseInt(values[1]), Integer.parseInt(values[2]), Integer.parseInt(values[3]));
                    if (shard.count < 0 || shard.quarantined < 0 || shard.grants < 0
                            || shard.quarantined > shard.count || shard.grants > shard.quarantined) throw new IllegalArgumentException("shard count");
                    shards.put(prefix, shard);
                } else {
                    String id = decode(key);
                    bounded(id, 512, "记录 ID");
                    entries.put(id, parse(saved.getProperty(key), legacy));
                }
            }
        } catch (RuntimeException malformed) { throw new IOException("录像台账内容损坏，拒绝清零", malformed); }
        if (legacy && !Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) createDirectoryDurably(archive);
        requireDirectory(archive);
        for (String prefix : shards.keySet()) readShard(prefix);
        // 上次进程可能在 rename 成功、目录同步失败后退出；确认当前引用已持久化再删旧分片。
        for (String prefix : shards.keySet()) directorySync.force(archive.resolve(prefix));
        directorySync.force(archive);
        directorySync.force(file.getParent());
        cleanupAll();
        archiveTerminal();
        if (entries.size() > maxEntries) throw new IOException("录像活动台账超过配置容量，拒绝清零");
        if (legacy) persistRoot(entries, shards);
    }

    private Map<String, Entry> readShard(String prefix) throws IOException {
        Shard shard = shards.get(prefix);
        if (shard == null) return new HashMap<>();
        requireDirectory(archive);
        requireDirectory(archive.resolve(prefix));
        Properties saved = read(shardPath(prefix, shard), shard.digest);
        if (!"archive-1".equals(saved.remove("@format"))) throw new IOException("录像隔离分片版本损坏");
        Map<String, Entry> result = new HashMap<>();
        long quarantined = 0, grants = 0;
        try {
            for (String key : saved.stringPropertyNames()) {
                String id = decode(key);
                bounded(id, 512, "记录 ID");
                if (!bucket(id).equals(prefix)) throw new IllegalArgumentException("wrong shard");
                Entry entry = parse(saved.getProperty(key), false);
                result.put(id, entry);
                if (!entry.completed) quarantined++;
                if (entry.grant) grants++;
            }
        } catch (RuntimeException malformed) { throw new IOException("录像隔离分片内容损坏，拒绝清零", malformed); }
        if (result.size() != shard.count || quarantined != shard.quarantined || grants != shard.grants) {
            throw new IOException("录像隔离分片数量不匹配，拒绝清零");
        }
        return result;
    }

    private void commit(Map<String, Entry> next, Map<String, Map<String, Entry>> updates) throws IOException {
        verifyCurrentRoot();
        requireDirectory(archive);
        cleanupAll();
        Map<String, Shard> nextShards = new HashMap<>(shards);
        Map<String, Long> nextShardSizes = new HashMap<>(shardStorageBytes);
        for (Map.Entry<String, Map<String, Entry>> update : updates.entrySet()) {
            Properties values = serialize(update.getValue());
            String digest = checksum(values);
            long quarantined = update.getValue().values().stream().filter(e -> !e.completed).count();
            long grants = update.getValue().values().stream().filter(e -> e.grant).count();
            Shard shard = new Shard(digest, update.getValue().size(), (int) quarantined, (int) grants);
            Path dir = archive.resolve(update.getKey());
            createDirectoryDurably(dir);
            nextShardSizes.put(update.getKey(), write(shardPath(update.getKey(), shard), values, "archive-1"));
            nextShards.put(update.getKey(), shard);
        }
        long rootBytes = persistRoot(next, nextShards);
        entries = next;
        shards = nextShards;
        updateStorageSummary(rootBytes, nextShardSizes);
        cleanupAll();
    }

    private long persistRoot(Map<String, Entry> next, Map<String, Shard> nextShards) throws IOException {
        if (committedRootDigest != null) verifyCurrentRoot();
        else if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("录像主台账被其他写入者创建，拒绝覆盖");
        Properties values = serialize(next);
        nextShards.forEach((prefix, s) -> values.setProperty("@archive." + prefix,
                s.digest + "," + s.count + "," + s.quarantined + "," + s.grants));
        String digest = checksum(values);
        long bytes;
        try { bytes = write(file, values, "2"); }
        catch (IOException failure) {
            // rename 可能已经成功；目录同步未确认时不能再清理任何旧分片。
            uncertainRootWrite = failure;
            throw failure;
        }
        committedRootDigest = digest;
        committedRootFormat = "2";
        return bytes;
    }

    /** 外部删除、损坏或回退主快照后，不得用内存副本重建并覆盖故障证据。 */
    private void verifyCurrentRoot() throws IOException {
        if (uncertainRootWrite != null) {
            throw new IOException("录像主快照提交结果未确认，保留全部分片并暂停；需重启后完整核查", uncertainRootWrite);
        }
        if (committedRootDigest == null) throw new IOException("录像主台账尚未初始化");
        Properties saved = read(file, committedRootDigest);
        if (!Objects.equals(committedRootFormat, saved.getProperty("@format"))) {
            throw new IOException("录像主台账格式已被外部改变，拒绝覆盖");
        }
    }

    private static Properties serialize(Map<String, Entry> records) {
        Properties values = new Properties();
        records.forEach((id, e) -> {
            StringBuilder audit = new StringBuilder();
            for (RecoveryAudit a : e.audit) audit.append(a.at).append('|').append(a.fromRevision)
                    .append('|').append(encode(a.reason)).append('\n');
            values.setProperty(encode(id), encode(e.fingerprint) + "," + e.attempts + "," + e.totalAttempts
                    + "," + e.firstAt + "," + e.nextAt + "," + e.permanent + "," + e.revision
                    + "," + encode(e.reason) + "," + e.grant + "," + e.completed + "," + encode(audit.toString()));
        });
        return values;
    }

    private Entry parse(String text, boolean legacy) {
        String[] v = text.split(",", -1);
        Entry e;
        if (legacy) {
            if (v.length != 5) throw new IllegalArgumentException("entry fields");
            e = new Entry(decode(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[1]), Long.parseLong(v[2]),
                    Long.parseLong(v[3]), bool(v[4]), revision(), "迁移自 v3 台账", false, false, List.of());
        } else {
            if (v.length != 11) throw new IllegalArgumentException("entry fields");
            List<RecoveryAudit> audit = new ArrayList<>();
            String history = decode(v[10]);
            for (String line : history.split("\n")) {
                if (line.isEmpty()) continue;
                String[] a = line.split("\\|", -1);
                if (a.length != 3) throw new IllegalArgumentException("audit fields");
                UUID.fromString(a[1]);
                long at = Long.parseLong(a[0]);
                if (at < 0) throw new IllegalArgumentException("audit time");
                audit.add(new RecoveryAudit(at, a[1], bounded(decode(a[2]), 1000, "恢复原因")));
            }
            if (audit.size() > MAX_RECOVERIES) throw new IllegalArgumentException("audit limit");
            UUID.fromString(v[6]);
            e = new Entry(decode(v[0]), Integer.parseInt(v[1]), Long.parseLong(v[2]), Long.parseLong(v[3]),
                    Long.parseLong(v[4]), bool(v[5]), v[6], decode(v[7]), bool(v[8]), bool(v[9]), List.copyOf(audit));
        }
        bounded(e.fingerprint, 4096, "输入指纹");
        if (e.attempts < 1 || e.totalAttempts < e.attempts || e.firstAt < 0 || e.nextAt < e.firstAt
                || e.reason.length() > 1100 || e.grant && (e.completed || e.audit.isEmpty())) {
            throw new IllegalArgumentException("retry bounds");
        }
        return e;
    }

    private static Properties read(Path path, String expectedDigest) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_SNAPSHOT_BYTES) {
            throw new IOException("录像台账缺失或类型/大小无效，拒绝清零：" + path);
        }
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { saved.load(input); }
        catch (IllegalArgumentException malformed) { throw new IOException("录像台账格式损坏，拒绝清零", malformed); }
        String format = (String) saved.remove("@format");
        String digest = (String) saved.remove("@checksum");
        if (format == null || digest == null || !digest.equals(checksum(saved))
                || expectedDigest != null && !expectedDigest.equals(digest)) {
            throw new IOException("录像台账完整性校验失败，拒绝清零：" + path);
        }
        saved.setProperty("@format", format);
        return saved;
    }

    private long write(Path path, Properties values, String format) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("录像台账不能是符号链接");
        values.setProperty("@checksum", checksum(values));
        values.setProperty("@format", format);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, "ISUP recording retry state; preserve main file and archive together");
        if (bytes.size() > MAX_SNAPSHOT_BYTES) throw new IOException("录像台账快照达到文件容量上限，需人工核查");
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        if (Files.isSymbolicLink(temporary)) throw new IOException("录像台账临时文件不能是符号链接");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data = ByteBuffer.wrap(bytes.toByteArray());
                while (data.hasRemaining()) output.write(data);
                output.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            directorySync.force(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
        return bytes.size();
    }

    private void createDirectoryDurably(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = directory.getParent();
            if (parent != null) createDirectoryDurably(parent);
            Files.createDirectory(directory);
        }
        requireDirectory(directory);
        // 已存在的分片目录也同步父目录，覆盖上一次 mkdir 后同步失败的情况。
        if (directory.getParent() != null) directorySync.force(directory.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Windows 不支持此目录 FileChannel 路径；本地测试不等于 Linux 掉电持久性实测。
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void cleanupAll() throws IOException {
        requireDirectory(archive);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(archive)) {
            for (Path dir : dirs) {
                String prefix = dir.getFileName().toString();
                if (!prefix.matches("[0-9a-f]{2}")) throw new IOException("录像隔离目录存在未知项目，需人工核查");
                requireDirectory(dir);
                Shard referenced = shards.get(prefix);
                Path keep = referenced == null ? null : shardPath(prefix, referenced);
                try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
                    for (Path candidate : files) {
                        if (!candidate.getFileName().toString().matches("[0-9a-f]{64}\\.properties(?:\\.tmp)?")
                                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("录像隔离分片目录存在未知项目，拒绝自动删除");
                        }
                        if (!candidate.equals(keep)) Files.delete(candidate);
                    }
                }
            }
        }
    }

    private Path shardPath(String prefix, Shard shard) { return archive.resolve(prefix).resolve(shard.digest + ".properties"); }
    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("录像隔离目录丢失或无效：" + path);
    }
    private long delayFor(int attempts) {
        long delay = baseDelayMillis;
        for (int i = 1; i < attempts && delay < maxDelayMillis; i++) {
            delay = delay > maxDelayMillis / 2 ? maxDelayMillis : Math.min(maxDelayMillis, delay * 2);
        }
        return delay;
    }
    private static long safeAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    private static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("boolean");
        return Boolean.parseBoolean(value);
    }
    private static void validateIdentity(String id, String fingerprint) { bounded(id, 512, "记录 ID"); bounded(fingerprint, 4096, "输入指纹"); }
    private static String bounded(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException(name + "不能为空且长度不能超过 " + limit);
        return value;
    }
    private static String revision() { return UUID.randomUUID().toString(); }
    private static String bucket(String id) throws IOException { return digest(id).substring(0, 2); }
    private static String checksum(Properties values) throws IOException {
        StringBuilder canonical = new StringBuilder();
        values.stringPropertyNames().stream().sorted().forEach(key -> {
            String value = values.getProperty(key);
            canonical.append(key.length()).append(':').append(key).append(value.length()).append(':').append(value);
        });
        return digest(canonical.toString());
    }
    private static String digest(String value) throws IOException {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private record Entry(String fingerprint, int attempts, long totalAttempts, long firstAt, long nextAt,
                         boolean permanent, String revision, String reason, boolean grant, boolean completed,
                         List<RecoveryAudit> audit) { }
    private record Shard(String digest, int count, int quarantined, int grants) { }
    @FunctionalInterface interface DirectorySync { void force(Path directory) throws IOException; }
    public record RecoveryAudit(long at, String fromRevision, String reason) { }
    public record Snapshot(String id, String fingerprint, String revision, int attempts, long totalAttempts,
                           long firstAt, long nextAt, boolean quarantined, boolean recoveryPending,
                           String reason, List<RecoveryAudit> recoveryAudit) { }
    /** storageBytes 是最近启动/提交的缓存摘要，不代表磁盘当前仍完整可读。 */
    public record Stats(int active, int capacity, long quarantined, long pendingReleases,
                        boolean nearCapacity, boolean atCapacity, long storageBytes,
                        long storageSampledAt, boolean storageCached) { }
}
