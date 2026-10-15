package com.hiki.isup.service;

import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个 ffmpeg 子进程。ISUP 码流通过 stdin 写入，ffmpeg 输出 HLS 或 MP4。
 *
 * <p>码流来自 SDK 的 native 回调线程，而管道写入在 ffmpeg 消费变慢时会阻塞。
 * 若在回调线程里直接写，背压会沿回调线程顶回 SDK，影响该 SDK 上所有设备的注册与取流。
 * 因此写入统一经由有界队列交给独立的 writer 线程：</p>
 * <ul>
 *   <li>实时流（预览 / 回放）：队列满时丢弃新包，宁可丢帧也不阻塞回调线程</li>
 *   <li>下载：文件必须完整，队列满时等待，并周期性确认进程仍然存活</li>
 * </ul>
 *
 * <p>必须保证进程最终被销毁，否则会留下僵尸进程持续占用 CPU / 文件句柄。</p>
 */
public class FfmpegProcess {

    private static final Logger log = LoggerFactory.getLogger(FfmpegProcess.class);

    /** 实时流（HLS）待写队列容量（包数） */
    private static final int QUEUE_CAPACITY_LIVE = 512;
    /** 下载（MP4）队列更大，减少磁盘抖动时占满 SDK 回调线程 */
    private static final int QUEUE_CAPACITY_DOWNLOAD = 2048;
    private static final int STDIN_BUFFER_LIVE = 64 * 1024;
    private static final int STDIN_BUFFER_DOWNLOAD = 256 * 1024;
    /** 复用缓冲的常规大小，PS 码流单包通常远小于此 */
    private static final int POOL_BUFFER_SIZE = 8 * 1024;
    private static final int POOL_MAX_BUFFERS = 32;
    private static final long POOL_MAX_GLOBAL_BYTES = 2L * 1024 * 1024;
    private static final AtomicLong POOLED_BYTES = new AtomicLong();
    private static final MemoryBudget DEFAULT_MEMORY_BUDGET = new MemoryBudget(
            1024 * 1024, 32L * 1024 * 1024, 128L * 1024 * 1024);
    private static final ScheduledExecutorService OUTPUT_TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "recording-output-timer"); thread.setDaemon(true); return thread;
    });
    private static final ThreadPoolExecutor OUTPUT_CHECKS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> {
                Thread thread = new Thread(r, "recording-output-check"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final long OUTPUT_CHECK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** 下载场景入队时单次阻塞等待上限（毫秒） */
    private static final long ENQUEUE_POLL_MILLIS = 50;
    /**
     * 下载场景入队的累计等待上限（毫秒）。
     *
     * <p>下载不能丢包，队列满时回调线程必须等待；但 ffmpeg 若卡住不消费也不退出，
     * 等待将没有终点，而该回调线程属于 SDK，被占住会影响同一 SDK 上的其他设备。
     * 超过这个时长就放弃本包并停止后续写入，由会话的下载超时兜底。</p>
     */
    private static final long ENQUEUE_MAX_WAIT_MILLIS = 1_000;
    /** 单个会话最多打印的 stderr 行数 */
    private static final int STDERR_MAX_LOGS = 8;

    private final Process process;
    private final OutputStream stdin;
    private final OutputStream recordingSource;
    private final String commandLine;
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicBoolean stdinClosed = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final AtomicBoolean writeFailed = new AtomicBoolean(false);

    /** 会话 ID，仅用于日志关联，startStderrPump 时补上 */
    private volatile String sessionId = "-";

    private final BlockingQueue<Packet> queue;
    private final Queue<byte[]> bufferPool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pooledCount = new AtomicInteger();
    private final Object poolMonitor = new Object();
    private final Object enqueueMonitor = new Object();
    /** 由 enqueueMonitor 保护，包含预留预算、复制 native 数据和等待入队。 */
    private int activeWrites;
    private long closeRequestedNanos;
    private final MemoryBudget memoryBudget;
    private final AtomicLong bufferedBytes = new AtomicLong();
    private volatile OutputGuard outputGuard;
    private volatile ScheduledFuture<?> outputMonitor;
    private volatile GuardCheck guardCheck;
    private final boolean dropWhenFull;
    private final AtomicLong droppedPackets = new AtomicLong();
    private final Thread writerThread;
    private final java.util.concurrent.CountDownLatch writerFinished = new java.util.concurrent.CountDownLatch(1);

    FfmpegProcess(Process process, String commandLine, boolean dropWhenFull) {
        this(process, commandLine, dropWhenFull, null);
    }

    FfmpegProcess(Process process, String commandLine, boolean dropWhenFull, OutputStream recordingSource) {
        this(process, commandLine, dropWhenFull, recordingSource, DEFAULT_MEMORY_BUDGET);
    }

    FfmpegProcess(Process process, String commandLine, boolean dropWhenFull, OutputStream recordingSource,
                  MemoryBudget memoryBudget) {
        int queueCapacity = dropWhenFull ? QUEUE_CAPACITY_LIVE : QUEUE_CAPACITY_DOWNLOAD;
        int stdinBuffer = dropWhenFull ? STDIN_BUFFER_LIVE : STDIN_BUFFER_DOWNLOAD;
        this.process = process;
        this.recordingSource = recordingSource;
        this.commandLine = commandLine;
        this.dropWhenFull = dropWhenFull;
        this.memoryBudget = memoryBudget;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        if (process == null && recordingSource == null) throw new IllegalArgumentException("媒体写入目标不能为空");
        this.stdin = new BufferedOutputStream(process == null ? OutputStream.nullOutputStream() : process.getOutputStream(), stdinBuffer);
        this.writerThread = new Thread(this::drainLoop, "ffmpeg-stdin-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /** 打包专用：复用有界异步写入，仅保存 PS，不再启动一个无用的中间 MP4 进程。 */
    static FfmpegProcess capture(OutputStream source, String sessionId) {
        FfmpegProcess capture = new FfmpegProcess(null, "raw-ps-capture", false, source);
        capture.sessionId = sessionId;
        return capture;
    }

    /**
     * 启动 stderr 消费线程，避免 ffmpeg 输出打满管道导致进程卡死。
     */
    void startStderrPump(String sessionId) {
        this.sessionId = sessionId;
        if (process == null) return;
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
                if (count++ < STDERR_MAX_LOGS) {
                    log.warn("[ffmpeg][{}] {}", sessionId, new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                }
            }
        } catch (IOException e) {
            log.debug("ffmpeg stderr 读取结束，sessionId={}", sessionId);
        }
    }

    /**
     * 提交一段码流。
     *
     * <p>实时流在队列满时丢弃该包；下载流在队列满时等待，但等待有上限：
     * ffmpeg 已退出或长时间不消费时放弃本包并停止后续写入，
     * 否则回调线程会被无限期占住，影响同一 SDK 上的其他设备。</p>
     */
    /**
     * 从 JNA native 指针直接读入缓冲池并入队，避免 {@code getByteArray} 的额外分配与拷贝。
     */
    public void write(Pointer pointer, int len) {
        if (pointer == null || len <= 0 || !beginWrite()) {
            return;
        }
        try {
            Packet packet = reservePacket(len);
            if (packet == null) return;
            try {
                pointer.read(0, packet.buf, 0, len);
                enqueue(packet);
            } catch (RuntimeException | Error e) {
                releasePacket(packet);
                markWriteFailed("读取 native 录像数据失败");
                throw e;
            }
        } finally {
            finishWrite();
        }
    }

    public void write(byte[] data, int offset, int len) {
        if (len <= 0 || !beginWrite()) {
            return;
        }
        try {
            Packet packet = reservePacket(len);
            if (packet == null) return;
            try {
                System.arraycopy(data, offset, packet.buf, 0, len);
                enqueue(packet);
            } catch (RuntimeException | Error e) {
                releasePacket(packet);
                markWriteFailed("复制录像数据失败");
                throw e;
            }
        } finally {
            finishWrite();
        }
    }

    private boolean beginWrite() {
        synchronized (enqueueMonitor) {
            if (stdinClosed.get() || destroyed.get() || writeFailed.get()) return false;
            activeWrites++;
            return true;
        }
    }

    private void finishWrite() {
        synchronized (enqueueMonitor) {
            activeWrites--;
        }
    }

    private void enqueue(Packet packet) {
        long waitStart = System.nanoTime();
        // closeStdin 只拒绝新写入，已获准的包仍须入队，不能静默丢失尾包。
        while (!destroyed.get() && !writeFailed.get()) {
            if (!isAlive()) {
                markWriteFailed("ffmpeg 进程已退出，停止向 stdin 写入码流");
                break;
            }
            if (System.nanoTime() - waitStart >= TimeUnit.MILLISECONDS.toNanos(ENQUEUE_MAX_WAIT_MILLIS)) {
                markWriteFailed("ffmpeg 消费停滞超过 " + ENQUEUE_MAX_WAIT_MILLIS + " 毫秒，停止写入以释放回调线程");
                break;
            }
            try {
                synchronized (enqueueMonitor) {
                    if (destroyed.get() || writeFailed.get()) break;
                    if (queue.offer(packet)) return;
                }
                if (dropWhenFull) { recordDropped(); break; }
                TimeUnit.MILLISECONDS.sleep(ENQUEUE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markWriteFailed("录像入队等待被中断，不能认定完整下载");
                break;
            }
        }
        releasePacket(packet);
    }

    private Packet reservePacket(int len) {
        if (len > memoryBudget.maxPacketBytes) {
            rejectPacket("录像单包超过内存上限 " + memoryBudget.maxPacketBytes + " bytes");
            return null;
        }
        int bytes = Math.max(POOL_BUFFER_SIZE, len);
        if (!reserve(bufferedBytes, bytes, memoryBudget.perProcessBytes)) {
            rejectPacket("录像单路缓存达到内存上限"); return null;
        }
        if (!reserve(memoryBudget.usedBytes, bytes, memoryBudget.globalBytes)) {
            bufferedBytes.addAndGet(-bytes);
            rejectPacket("录像全局缓存达到内存上限"); return null;
        }
        try { return new Packet(acquireBuffer(bytes), len); }
        catch (RuntimeException | Error e) {
            bufferedBytes.addAndGet(-bytes);
            memoryBudget.usedBytes.addAndGet(-bytes);
            markWriteFailed("录像缓冲分配失败，拒绝完整下载");
            throw e;
        }
    }

    private void rejectPacket(String reason) {
        if (dropWhenFull) recordDropped(); else markWriteFailed(reason);
    }

    private void recordDropped() {
        long dropped = droppedPackets.incrementAndGet();
        if (dropped == 1 || dropped % 1000 == 0)
            log.warn("ffmpeg 消费滞后，丢弃码流包以保持实时性：sessionId={}, 累计丢弃={} 包", sessionId, dropped);
    }

    private static boolean reserve(AtomicLong counter, long bytes, long limit) {
        while (true) {
            long used = counter.get();
            if (bytes > limit - used) return false;
            if (counter.compareAndSet(used, used + bytes)) return true;
        }
    }

    private void releasePacket(Packet packet) {
        if (!packet.released.compareAndSet(false, true)) return;
        bufferedBytes.addAndGet(-packet.buf.length);
        memoryBudget.usedBytes.addAndGet(-packet.buf.length);
        releaseBuffer(packet.buf);
    }

    /**
     * 标记写入不可用，后续码流直接丢弃。同一个进程只打一次日志，避免每包刷屏。
     */
    private void markWriteFailed(String reason) {
        if (writeFailed.compareAndSet(false, true)) {
            log.warn("{}：sessionId={}, 命令={}", reason, sessionId, commandLine);
        }
        closeStdin();
    }

    /**
     * 请求结束输入。已开始的写入和队列中的数据由 writer 排空后再关闭 stdin。
     * 请求本身不等待 native 复制；在途复制卡住时由 writer 有界等待后标记失败。
     */
    public void closeStdin() {
        synchronized (enqueueMonitor) {
            if (stdinClosed.compareAndSet(false, true)) {
                closeRequestedNanos = System.nanoTime();
                closeRequested.set(true);
            }
        }
    }

    /**
     * writer 线程主循环：排空队列，收到结束请求且队列空后关闭 stdin。
     */
    private void drainLoop() {
        try {
            while (true) {
                if (writeFailed.get() || destroyed.get()) break;
                Packet packet = queue.poll(200, TimeUnit.MILLISECONDS);
                if (packet != null) {
                    try {
                        if (recordingSource != null) {
                            recordingSource.write(packet.buf, 0, packet.len);
                        }
                        stdin.write(packet.buf, 0, packet.len);
                        // 队列已排空时立即推送，避免数据滞留在缓冲里拉长起播延迟；
                        // 仍在积压时交给缓冲批量写，保住吞吐
                        if (queue.isEmpty()) {
                            stdin.flush();
                        }
                    } finally {
                        releasePacket(packet);
                    }
                    continue;
                }
                synchronized (enqueueMonitor) {
                    // poll 返回空后可能又有包入队；与入队/入口关闭共同判定才能安全发送 EOF。
                    if (closeRequested.get() && queue.isEmpty()) {
                        if (activeWrites == 0) break;
                        if (System.nanoTime() - closeRequestedNanos
                                >= TimeUnit.MILLISECONDS.toNanos(ENQUEUE_MAX_WAIT_MILLIS)) {
                            markWriteFailed("录像收尾等待在途写入超过 " + ENQUEUE_MAX_WAIT_MILLIS + " 毫秒，拒绝完整下载");
                            break;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            markWriteFailed("录像 writer 被中断，不能认定完整下载");
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            if (writeFailed.compareAndSet(false, true)) {
                log.warn("写入 ffmpeg stdin 失败，ffmpeg 可能已退出，后续码流将被丢弃。"
                                + "请往上找本会话的 [ffmpeg][{}] stderr 输出定位原因。（命令={}）",
                        sessionId, commandLine);
            }
        } catch (Error fatal) {
            markWriteFailed("录像 writer 异常终止，拒绝完整下载");
            throw fatal;
        } finally {
            closeStdin();
            try {
                // 必须先关闭原始流再发送 stdin EOF；waitFor(0) 后原始录像才能被解析。
                try {
                    if (recordingSource != null) {
                        try {
                            recordingSource.close();
                        } catch (IOException | RuntimeException e) {
                            markWriteFailed("原始录像写入/关闭失败：" + e.getMessage());
                        } catch (Error fatal) {
                            markWriteFailed("原始录像关闭异常终止，拒绝完整下载");
                            throw fatal;
                        }
                    }
                } finally {
                    closeStdinQuietly();
                }
            } finally {
                // 关闭流本身出错也不能跳过队列预算归还及完成通知。
                try {
                    discardQueuedPackets();
                    clearPool();
                } finally {
                    writerFinished.countDown();
                }
            }
        }
    }

    private byte[] acquireBuffer(int len) {
        if (len == POOL_BUFFER_SIZE) {
            synchronized (poolMonitor) {
                byte[] pooled = bufferPool.poll();
                if (pooled != null) {
                    pooledCount.decrementAndGet(); POOLED_BYTES.addAndGet(-pooled.length); return pooled;
                }
            }
        }
        return new byte[len];
    }

    private void releaseBuffer(byte[] buf) {
        synchronized (poolMonitor) {
            if (buf.length != POOL_BUFFER_SIZE || pooledCount.get() >= POOL_MAX_BUFFERS
                    || stdinClosed.get() || destroyed.get()) return;
            if (!reserve(POOLED_BYTES, buf.length, POOL_MAX_GLOBAL_BYTES)) return;
            bufferPool.offer(buf); pooledCount.incrementAndGet();
        }
    }

    private void clearPool() {
        synchronized (poolMonitor) {
            byte[] buffer;
            while ((buffer = bufferPool.poll()) != null) POOLED_BYTES.addAndGet(-buffer.length);
            pooledCount.set(0);
        }
    }

    private void discardQueuedPackets() {
        synchronized (enqueueMonitor) {
            Packet packet;
            while ((packet = queue.poll()) != null) releasePacket(packet);
        }
    }

    private void closeStdinQuietly() {
        try {
            try {
                stdin.flush();
            } catch (IOException | RuntimeException ignored) {
                markWriteFailed("ffmpeg stdin 最后缓冲写入失败");
            } catch (Error fatal) {
                markWriteFailed("ffmpeg stdin 最后缓冲写入异常终止");
                throw fatal;
            }
        } finally {
            try {
                stdin.close();
            } catch (IOException | RuntimeException e) {
                markWriteFailed("ffmpeg stdin 关闭失败");
                log.debug("关闭 ffmpeg stdin 异常", e);
            } catch (Error fatal) {
                markWriteFailed("ffmpeg stdin 关闭异常终止");
                throw fatal;
            }
        }
    }

    public boolean isAlive() {
        return process == null ? writerFinished.getCount() != 0 : process.isAlive();
    }

    public Integer exitValue() {
        if (process == null) return writerFinished.getCount() == 0 ? (writeFailed.get() ? 1 : 0) : null;
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            if (process == null || process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (!writerFinished.await(remaining, TimeUnit.NANOSECONDS)) {
                    markWriteFailed("ffmpeg 已退出，但原始录像/输入队列未完成关闭");
                    return null;
                }
                verifyOutputBeforeCompletion(deadline);
                cancelOutputMonitor();
                return exitValue();
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
            cancelOutputMonitor();
            closeStdin();
            discardQueuedPackets();
            if (process == null) {
                if (writerFinished.getCount() != 0) markWriteFailed("原始录像保存被取消，不能认定完整下载");
                closeRequested.set(true);
                stdinClosed.set(true);
                writerThread.interrupt();
                awaitWriterExit(1);
                clearPool();
                return;
            }
            closeRequested.set(true);
            stdinClosed.set(true);
            try {
                ProcessHandle handle = process.toHandle();
                handle.descendants().forEach(ProcessHandle::destroy);
            } catch (Exception ignored) {
                // ignore
            }
            process.destroy();
            awaitWriterExit(1);
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            clearPool();
            log.info("ffmpeg 进程已销毁，命令={}", commandLine);
        }
    }

    private void awaitWriterExit(long seconds) {
        try {
            writerThread.join(TimeUnit.SECONDS.toMillis(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 下载写入已失败（进程退出或消费停滞）。之后的包不再写入，文件不完整。 */
    public boolean isWriteFailed() {
        return writeFailed.get();
    }

    /** 因消费滞后而丢弃的码流包数量 */
    public long getDroppedPackets() {
        return droppedPackets.get();
    }

    /** 当前待写入队列长度 */
    public int getPendingPackets() {
        return queue.size();
    }

    /** 含正在写入的包；只有实际 writer 释放后才扣除。 */
    public long getBufferedBytes() { return bufferedBytes.get(); }

    public static long getTotalBufferedBytes() { return DEFAULT_MEMORY_BUDGET.usedBytes(); }

    /** 只暂停新增领取，正在运行的流仍由各自入队预算和策略处理。 */
    static boolean globalBudgetSaturated() {
        return DEFAULT_MEMORY_BUDGET.usedBytes()
                > DEFAULT_MEMORY_BUDGET.globalBytes - DEFAULT_MEMORY_BUDGET.maxPacketBytes;
    }

    /** 调度线程只检查内存状态，磁盘 IO 在有界独立线程池中执行。 */
    void monitorOutput(OutputGuard guard) {
        if (outputGuard != null) throw new IllegalStateException("输出保护已注册");
        outputGuard = guard;
        outputMonitor = OUTPUT_TIMER.scheduleWithFixedDelay(this::checkOutputTick, 0, 1, TimeUnit.SECONDS);
    }

    private void checkOutputTick() {
        if (destroyed.get() || writeFailed.get() || !isAlive()) { cancelOutputMonitor(); return; }
        GuardCheck current = guardCheck;
        if (current != null && current.done.getCount() != 0) {
            if (System.nanoTime() - current.started > OUTPUT_CHECK_TIMEOUT_NANOS)
                markWriteFailed("录像磁盘检查超时，暂停写入");
            return;
        }
        guardCheck = submitOutputCheck();
    }

    private GuardCheck submitOutputCheck() {
        GuardCheck check = new GuardCheck();
        try {
            OUTPUT_CHECKS.execute(() -> {
                try {
                    if (!destroyed.get()) outputGuard.check();
                } catch (IOException | RuntimeException e) {
                    markWriteFailed("录像输出空间保护失败：" + e.getMessage());
                } catch (Error fatal) {
                    markWriteFailed("录像输出空间保护异常终止，拒绝完整下载");
                    throw fatal;
                } finally { check.done.countDown(); }
            });
        } catch (RejectedExecutionException e) {
            markWriteFailed("录像磁盘检查队列已满，暂停写入");
            check.done.countDown();
        }
        return check;
    }

    private void verifyOutputBeforeCompletion(long deadline) throws InterruptedException {
        if (outputGuard == null || writeFailed.get()) return;
        GuardCheck check = submitOutputCheck();
        long remaining = Math.min(OUTPUT_CHECK_TIMEOUT_NANOS, Math.max(0, deadline - System.nanoTime()));
        if (!check.done.await(remaining, TimeUnit.NANOSECONDS))
            markWriteFailed("无法在期限内确认录像输出安全，拒绝完成");
    }

    private void cancelOutputMonitor() {
        ScheduledFuture<?> monitor = outputMonitor;
        if (monitor != null) monitor.cancel(false);
    }

    @FunctionalInterface interface OutputGuard { void check() throws IOException; }

    private static final class GuardCheck {
        final long started = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(1);
    }

    /** 同一实例供所有会话共享；原生读取和分配缓冲之前预留实际字节。 */
    static final class MemoryBudget {
        final int maxPacketBytes;
        final long perProcessBytes;
        final long globalBytes;
        final AtomicLong usedBytes = new AtomicLong();
        MemoryBudget(int maxPacketBytes, long perProcessBytes, long globalBytes) {
            if (maxPacketBytes <= 0 || perProcessBytes < POOL_BUFFER_SIZE || globalBytes < POOL_BUFFER_SIZE)
                throw new IllegalArgumentException("码流内存预算无效");
            this.maxPacketBytes = maxPacketBytes;
            this.perProcessBytes = perProcessBytes;
            this.globalBytes = globalBytes;
        }
        long usedBytes() { return usedBytes.get(); }
    }

    public String getCommandLine() {
        return commandLine;
    }

    static String toCommandLine(List<String> command) {
        return String.join(" ", command);
    }

    private static final class Packet {
        private final byte[] buf;
        private final int len;
        private final AtomicBoolean released = new AtomicBoolean();

        Packet(byte[] buf, int len) {
            this.buf = buf;
            this.len = len;
        }
    }
}
