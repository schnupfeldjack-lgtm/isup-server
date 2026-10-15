package com.hiki.isup.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegProcessTest {

    @Test void closeDuringNativeCopyDrainsTheAcceptedTailPacket() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        FfmpegProcess capture = new FfmpegProcess(null, "close-during-copy", false, raw, budget);
        BlockingPointer pointer = new BlockingPointer(new byte[]{3, 4});
        FutureTask<Void> writing = startNativeWrite(capture, pointer);
        try {
            assertTrue(pointer.reading.await(3, TimeUnit.SECONDS));
            assertEquals(8192, budget.usedBytes(), "正在复制的 native 包仍占用预算");
            capture.closeStdin();
            pointer.release.countDown();
            writing.get(3, TimeUnit.SECONDS);

            assertEquals(0, capture.waitFor(3));
            assertArrayEquals(new byte[]{3, 4}, raw.toByteArray(), "结束信令不能丢弃已经开始复制的尾包");
            assertFalse(capture.isWriteFailed());
            assertEquals(0, budget.usedBytes());
        } finally {
            pointer.release.countDown();
            capture.destroy();
            writing.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void closeWithStalledNativeCopyFailsWithinBoundInsteadOfCompletingEmpty() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        FfmpegProcess capture = new FfmpegProcess(null, "close-stalled-copy", false, raw, budget);
        BlockingPointer pointer = new BlockingPointer(new byte[]{7});
        FutureTask<Void> writing = startNativeWrite(capture, pointer);
        try {
            assertTrue(pointer.reading.await(3, TimeUnit.SECONDS));
            capture.closeStdin();

            assertEquals(1, capture.waitFor(3), "收尾不能无限等待复制，也不能在尾包未完成时返回成功");
            assertTrue(capture.isWriteFailed());
            assertEquals(8192, budget.usedBytes(), "native 仍可能写入的缓冲不能提前归还预算");
            pointer.release.countDown();
            writing.get(3, TimeUnit.SECONDS);
            assertEquals(0, raw.size());
            assertEquals(0, budget.usedBytes());
        } finally {
            pointer.release.countDown();
            capture.destroy();
            writing.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void destroyDuringNativeCopyFailsWithoutReleasingItsBufferEarly() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        FfmpegProcess capture = new FfmpegProcess(null, "destroy-during-copy", false, raw, budget);
        BlockingPointer pointer = new BlockingPointer(new byte[]{9});
        FutureTask<Void> writing = startNativeWrite(capture, pointer);
        try {
            assertTrue(pointer.reading.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(2), capture::destroy);
            assertEquals(1, capture.waitFor(3));
            assertTrue(capture.isWriteFailed());
            assertEquals(8192, budget.usedBytes());

            pointer.release.countDown();
            writing.get(3, TimeUnit.SECONDS);
            assertEquals(0, raw.size());
            assertEquals(0, budget.usedBytes());
        } finally {
            pointer.release.countDown();
            capture.destroy();
            writing.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void closeRejectsNewWritesBeforeNativeCopyOrBudgetReservation() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        FfmpegProcess capture = new FfmpegProcess(null, "closed-input", false, raw, budget);
        com.sun.jna.Pointer pointer = org.mockito.Mockito.mock(com.sun.jna.Pointer.class);
        try {
            capture.closeStdin();
            capture.write(pointer, 1);
            capture.write(new byte[]{2}, 0, 1);
            assertEquals(0, capture.waitFor(3));
            org.mockito.Mockito.verifyNoInteractions(pointer);
            assertFalse(capture.isWriteFailed());
            assertEquals(0, raw.size());
            assertEquals(0, budget.usedBytes());
        } finally { capture.destroy(); }
    }

    @Test void interruptedEnqueueAfterCloseFailsAndReturnsAllReservations() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 32L * 1024 * 1024, 32L * 1024 * 1024);
        BlockingSink sink = new BlockingSink();
        FfmpegProcess capture = new FfmpegProcess(null, "interrupt-after-close", false, sink, budget);
        BlockingPointer pointer = new BlockingPointer(new byte[]{7});
        FutureTask<Boolean> writing = new FutureTask<>(() -> {
            capture.write(pointer, 1);
            return Thread.currentThread().isInterrupted();
        });
        Thread caller = new Thread(writing, "test-interrupted-enqueue");
        caller.setDaemon(true);
        try {
            capture.write(new byte[]{1}, 0, 1);
            assertTrue(sink.writing.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 2048; i++) capture.write(new byte[]{2}, 0, 1);
            assertEquals(2048, capture.getPendingPackets());
            caller.start();
            assertTrue(pointer.reading.await(3, TimeUnit.SECONDS));
            capture.closeStdin();
            pointer.interruptAfterRead = true;
            pointer.release.countDown();

            assertTrue(writing.get(3, TimeUnit.SECONDS), "保留调用线程的中断标志");
            assertTrue(capture.isWriteFailed(), "结束信令后在途包入队被中断也必须失败");
            sink.release.countDown();
            assertEquals(1, capture.waitFor(3));
            assertEquals(0, budget.usedBytes());
        } finally {
            pointer.release.countDown();
            sink.release.countDown();
            capture.destroy();
            caller.join(3000);
        }
    }

    @Test void realtimeQueueStillDropsAcceptedPacketWhenFullDuringClose() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 8L * 1024 * 1024, 8L * 1024 * 1024);
        BlockingSink sink = new BlockingSink();
        FfmpegProcess capture = new FfmpegProcess(null, "live-close-full", true, sink, budget);
        BlockingPointer pointer = new BlockingPointer(new byte[]{7});
        FutureTask<Void> writing = null;
        try {
            capture.write(new byte[]{1}, 0, 1);
            assertTrue(sink.writing.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 512; i++) capture.write(new byte[]{2}, 0, 1);
            assertEquals(512, capture.getPendingPackets());
            writing = startNativeWrite(capture, pointer);
            assertTrue(pointer.reading.await(3, TimeUnit.SECONDS));
            capture.closeStdin();
            pointer.release.countDown();
            writing.get(3, TimeUnit.SECONDS);

            assertEquals(1, capture.getDroppedPackets());
            assertFalse(capture.isWriteFailed());
            sink.release.countDown();
            assertEquals(0, capture.waitFor(3));
            assertEquals(0, budget.usedBytes());
        } finally {
            pointer.release.countDown();
            sink.release.countDown();
            capture.destroy();
            if (writing != null) writing.get(3, TimeUnit.SECONDS);
        }
    }

    private static FutureTask<Void> startNativeWrite(FfmpegProcess capture, BlockingPointer pointer) {
        FutureTask<Void> writing = new FutureTask<>(() -> { capture.write(pointer, pointer.bytes.length); return null; });
        Thread thread = new Thread(writing, "test-native-copy");
        thread.setDaemon(true);
        thread.start();
        return writing;
    }

    private static final class BlockingPointer extends com.sun.jna.Pointer {
        final byte[] bytes;
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        boolean interruptAfterRead;
        BlockingPointer(byte[] bytes) { super(0); this.bytes = bytes; }
        @Override public void read(long offset, byte[] buffer, int index, int length) {
            reading.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("native copy test timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            System.arraycopy(bytes, 0, buffer, index, length);
            if (interruptAfterRead) Thread.currentThread().interrupt();
        }
    }

    @Test void downloadByteBudgetIncludesWriterAndQueuedPackets() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        BlockingSink sink = new BlockingSink();
        FfmpegProcess capture = new FfmpegProcess(null, "bounded", false, sink, budget);
        try {
            capture.write(new byte[8192], 0, 8192);
            assertTrue(sink.writing.await(3, java.util.concurrent.TimeUnit.SECONDS));
            capture.write(new byte[8192], 0, 8192);
            assertEquals(16384, capture.getBufferedBytes());
            capture.write(new byte[8192], 0, 8192);
            assertTrue(capture.isWriteFailed(), "完整下载达到字节上限必须失败，不能静默丢包");
            assertEquals(16384, budget.usedBytes());
        } finally { sink.release.countDown(); capture.destroy(); }
        assertEquals(0, budget.usedBytes());
    }

    @Test void sharedBudgetLimitsAllWritersAndRealtimeStillDropsNewPacket() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 32768, 16384);
        BlockingSink firstSink = new BlockingSink(), secondSink = new BlockingSink();
        FfmpegProcess first = new FfmpegProcess(null, "first", false, firstSink, budget);
        FfmpegProcess second = new FfmpegProcess(null, "live", true, secondSink, budget);
        try {
            first.write(new byte[8192], 0, 8192);
            second.write(new byte[8192], 0, 8192);
            assertTrue(firstSink.writing.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(secondSink.writing.await(3, java.util.concurrent.TimeUnit.SECONDS));
            second.write(new byte[8192], 0, 8192);
            assertEquals(1, second.getDroppedPackets());
            org.junit.jupiter.api.Assertions.assertFalse(second.isWriteFailed());
            assertEquals(16384, budget.usedBytes());
        } finally {
            firstSink.release.countDown(); secondSink.release.countDown();
            first.destroy(); second.destroy();
        }
        assertEquals(0, budget.usedBytes());
    }

    @Test void oversizedNativePacketIsRejectedBeforePointerRead() {
        com.sun.jna.Pointer pointer = org.mockito.Mockito.mock(com.sun.jna.Pointer.class);
        FfmpegProcess capture = FfmpegProcess.capture(new ByteArrayOutputStream(), "oversize");
        try {
            capture.write(pointer, 1024 * 1024 + 1);
            assertTrue(capture.isWriteFailed());
            org.mockito.Mockito.verifyNoInteractions(pointer);
            assertEquals(0, capture.getBufferedBytes());
        } finally { capture.destroy(); }
    }

    @Test void failedPointerReadReturnsItsMemoryReservation() {
        com.sun.jna.Pointer pointer = org.mockito.Mockito.mock(com.sun.jna.Pointer.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("native read failed")).when(pointer)
                .read(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(byte[].class),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        FfmpegProcess capture = new FfmpegProcess(null, "read-failed", false, new ByteArrayOutputStream(), budget);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> capture.write(pointer, 4));
            assertTrue(capture.isWriteFailed());
            assertEquals(0, capture.getBufferedBytes());
            assertEquals(0, budget.usedBytes());
        } finally { capture.destroy(); }
    }

    @Test void periodicOutputGuardStopsDownloadWithoutSdkCallback() throws Exception {
        FfmpegProcess ffmpeg = new FfmpegProcess(drainingProcess(), "guarded", false);
        java.util.concurrent.CountDownLatch checked = new java.util.concurrent.CountDownLatch(1);
        try {
            ffmpeg.monitorOutput(() -> { checked.countDown(); throw new IOException("disk reserve reached"); });
            assertTrue(checked.await(3, java.util.concurrent.TimeUnit.SECONDS));
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!ffmpeg.isWriteFailed() && System.nanoTime() < end) Thread.sleep(10);
            assertTrue(ffmpeg.isWriteFailed());
        } finally { ffmpeg.destroy(); }
    }

    @Test void unexpectedWriterFailureCannotCompleteAndReturnsAllReservations() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        java.io.OutputStream broken = new java.io.OutputStream() {
            @Override public void write(int value) { throw new IllegalStateException("writer failed"); }
        };
        FfmpegProcess capture = new FfmpegProcess(null, "writer-failed", false, broken, budget);
        try {
            capture.write(new byte[8192], 0, 8192);
            capture.closeStdin();
            assertEquals(1, capture.waitFor(3));
            assertTrue(capture.isWriteFailed());
            assertEquals(0, budget.usedBytes());
        } finally { capture.destroy(); }
    }

    @Test void unexpectedSourceCloseFailureStillReleasesWriterAndBudget() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        ByteArrayOutputStream broken = new ByteArrayOutputStream() {
            @Override public void close() { throw new IllegalStateException("close failed"); }
        };
        FfmpegProcess capture = new FfmpegProcess(null, "source-close-failed", false, broken, budget);
        try {
            capture.write(new byte[8192], 0, 8192);
            capture.closeStdin();
            assertEquals(1, capture.waitFor(3));
            assertTrue(capture.isWriteFailed());
            assertEquals(0, budget.usedBytes());
        } finally { capture.destroy(); }
    }

    @Test void writerErrorCannotCompleteOrLeakBudget() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        java.io.OutputStream broken = new java.io.OutputStream() {
            @Override public void write(int value) { throw new AssertionError("writer error"); }
        };
        FfmpegProcess capture = new FfmpegProcess(null, "writer-error", false, broken, budget);
        try {
            capture.write(new byte[8192], 0, 8192);
            capture.closeStdin();
            assertEquals(1, capture.waitFor(3));
            assertTrue(capture.isWriteFailed());
            assertEquals(0, budget.usedBytes());
        } finally { capture.destroy(); }
    }

    @Test void sourceCloseErrorCannotCompleteOrLeakBudget() throws Exception {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(8192, 16384, 32768);
        ByteArrayOutputStream broken = new ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("source close error"); }
        };
        FfmpegProcess capture = new FfmpegProcess(null, "source-close-error", false, broken, budget);
        try {
            capture.write(new byte[8192], 0, 8192);
            capture.closeStdin();
            assertEquals(1, capture.waitFor(3));
            assertTrue(capture.isWriteFailed());
            assertEquals(0, budget.usedBytes());
        } finally { capture.destroy(); }
    }

    @Test void outputGuardErrorCannotBeTreatedAsSuccessfulCheck() throws Exception {
        FfmpegProcess ffmpeg = new FfmpegProcess(drainingProcess(), "guard-error", false);
        java.util.concurrent.CountDownLatch checked = new java.util.concurrent.CountDownLatch(1);
        try {
            ffmpeg.monitorOutput(() -> { checked.countDown(); throw new AssertionError("guard error"); });
            assertTrue(checked.await(3, java.util.concurrent.TimeUnit.SECONDS));
            ffmpeg.closeStdin();
            ffmpeg.waitFor(3);
            assertTrue(ffmpeg.isWriteFailed(), "磁盘校验Error不是校验成功");
        } finally { ffmpeg.destroy(); }
    }

    @Test void impossiblePacketAllocationCannotLeaveDownloadSuccessful() {
        FfmpegProcess.MemoryBudget budget = new FfmpegProcess.MemoryBudget(Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
        FfmpegProcess capture = new FfmpegProcess(null, "allocation-error", false, new ByteArrayOutputStream(), budget);
        com.sun.jna.Pointer pointer = org.mockito.Mockito.mock(com.sun.jna.Pointer.class);
        try {
            // JVM 必须直接拒绝这个数组长度，不实际申请或占用数 GiB 堆。
            org.junit.jupiter.api.Assertions.assertThrows(OutOfMemoryError.class,
                    () -> capture.write(pointer, Integer.MAX_VALUE));
            assertTrue(capture.isWriteFailed());
            assertEquals(0, budget.usedBytes());
            assertEquals(0, capture.getBufferedBytes());
            org.mockito.Mockito.verifyNoInteractions(pointer);
        } finally { capture.destroy(); }
    }

    private static final class BlockingSink extends java.io.OutputStream {
        final java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        @Override public void write(int value) { }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            writing.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
    }

    @Test void rawCaptureNeedsNoChildProcessAndFinishesExactBytes() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        FfmpegProcess capture = FfmpegProcess.capture(raw, "raw-test");
        try {
            byte[] data = new byte[]{0, 0, 1, (byte) 0xba, 9, 2, 7};
            capture.write(data, 0, data.length);
            capture.closeStdin();
            assertEquals(0, capture.waitFor(3));
            assertArrayEquals(data, raw.toByteArray());
            org.junit.jupiter.api.Assertions.assertFalse(capture.isAlive());
            org.junit.jupiter.api.Assertions.assertFalse(capture.isWriteFailed());
        } finally { capture.destroy(); }
    }

    @Test void rawCaptureCloseFailureIsNeverSuccessful() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { throw new IOException("disk failure"); }
        };
        FfmpegProcess capture = FfmpegProcess.capture(raw, "raw-fail");
        try {
            capture.write(new byte[]{1}, 0, 1);
            capture.closeStdin();
            assertEquals(1, capture.waitFor(3));
            assertTrue(capture.isWriteFailed());
        } finally { capture.destroy(); }
    }

    @Test void abortingRawCaptureCannotLookCompleted() {
        FfmpegProcess capture = FfmpegProcess.capture(new ByteArrayOutputStream(), "raw-abort");
        capture.destroy();
        assertTrue(capture.isWriteFailed());
        assertEquals(1, capture.waitFor(3));
    }

    @Test void successfulExitDoesNotRacePendingSourceClose() throws Exception {
        java.util.concurrent.CountDownLatch closing = new java.util.concurrent.CountDownLatch(1);
        ByteArrayOutputStream raw = new ByteArrayOutputStream() {
            @Override public void close() throws IOException {
                closing.countDown();
                try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IOException("late close failure");
            }
        };
        Process early = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), EarlyExit.class.getName()).start();
        assertTrue(early.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        FfmpegProcess ffmpeg = new FfmpegProcess(early, "early-exit", false, raw);
        try {
            ffmpeg.closeStdin();
            assertTrue(closing.await(3, java.util.concurrent.TimeUnit.SECONDS));
            ffmpeg.waitFor(3);
            assertTrue(ffmpeg.isWriteFailed(), "waitFor 必须等原始文件关闭完成，不能提前放行半成品");
        } finally { ffmpeg.destroy(); }
    }
    public static class EarlyExit { public static void main(String[] args) { } }

    @Test void originalSourceIsCompleteBeforeSuccessfulWaitReturns() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        FfmpegProcess ffmpeg = new FfmpegProcess(drainingProcess(), "capture", false, raw);
        try {
            byte[] bytes = new byte[]{0, 0, 1, (byte)0xba, 8, 9};
            ffmpeg.write(bytes, 0, bytes.length);
            ffmpeg.closeStdin();
            assertEquals(0, ffmpeg.waitFor(10));
            assertArrayEquals(bytes, raw.toByteArray());
            org.junit.jupiter.api.Assertions.assertFalse(ffmpeg.isWriteFailed());
        } finally { ffmpeg.destroy(); }
    }

    @Test void lateCaptureCloseFailureIsNotACompleteDownload() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { throw new IOException("disk full at close"); }
        };
        FfmpegProcess ffmpeg = new FfmpegProcess(drainingProcess(), "close-failure", false, raw);
        try {
            ffmpeg.write(new byte[]{1}, 0, 1);
            ffmpeg.closeStdin();
            assertEquals(0, ffmpeg.waitFor(10));
            assertTrue(ffmpeg.isWriteFailed());
        } finally { ffmpeg.destroy(); }
    }

    private static Process drainingProcess() throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), DrainCommand.class.getName()).start();
    }
    public static class DrainCommand {
        public static void main(String[] args) throws Exception { System.in.transferTo(java.io.OutputStream.nullOutputStream()); }
    }

    @Test
    @DisplayName("下载队列满且下游不消费时，写入在短时间内返回，不长期占用调用线程")
    void downloadWriteReturnsQuicklyWhenConsumerStalls() throws Exception {
        Process process = stallingProcess();
        FfmpegProcess ffmpeg = new FfmpegProcess(process, "stall", false);
        try {
            byte[] packet = new byte[8192];
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                for (int i = 0; i < 2500; i++) {
                    ffmpeg.write(packet, 0, packet.length);
                }
            });
            assertTrue(ffmpeg.isWriteFailed(), "消费停滞应标记写入失败，供下载任务判失败并重试");
        } finally {
            ffmpeg.destroy();
        }
    }

    private static Process stallingProcess() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("ping", "-n", "30", "127.0.0.1")
                : new ProcessBuilder("sleep", "30");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }
}
