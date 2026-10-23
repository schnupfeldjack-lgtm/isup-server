package com.hiki.isup.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/** A disposable prefix with independently verified business coverage; never rewrites the original PS. */
final class RecordingTailRepair {
    record Prepared(Path path, Path source, PsRecordingTimeline original, long deadline) implements AutoCloseable {
        void verifySource() throws IOException { copyAndVerify(source, null, original, 0, deadline); }
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }

    static Prepared prepare(String ffmpeg, Path source, LocalDateTime start, LocalDateTime end, long deadline)
            throws IOException {
        ExactDurationTrimmer.checkProcessingBudget(deadline, source.getParent());
        PsRecordingTimeline original = PsRecordingTimeline.parse(source, deadline);
        PsRecordingTimeline.TailRepairPlan proof = original.tailRepairPlan(start, end);
        Path prefix = Files.createTempFile(source.getParent(), "recording-tail-", ".ps");
        try {
            RecordingStorageGuard.checkOutput(prefix, RecordingStorageGuard.MAX_OUTPUT_BYTES);
            copyAndVerify(source, prefix, original, proof.cutoffBytes(), deadline);
            PsRecordingTimeline candidate = PsRecordingTimeline.parse(prefix, deadline);
            if (candidate.hasIncompleteTail() || !candidate.duplicateVideoPackets().isEmpty()) {
                throw new RecordingValidationException("末 AU 副本仍存在不完整尾包或重复视频包");
            }
            PsRecordingTimeline.Window window = candidate.plan(start, end);
            if (!window.anchors().equals(proof.window().anchors())) {
                throw new RecordingValidationException("末 AU 副本的保留时间锚点变化");
            }
            RecordingFrameProbe.Frames frames = RecordingFrameProbe.readUntil(ffmpeg, prefix, deadline, 0, source.getParent());
            frames.verifyAnchors(window.anchors());
            frames.selectWindow(window.startPtsSeconds(), window.endPtsSeconds(),
                    () -> RecordingH264ContinuityProbe.readUntil(ffmpeg, prefix, frames.video(), deadline, source.getParent()));
            Prepared prepared = new Prepared(prefix, source, original, deadline);
            prepared.verifySource();
            return prepared;
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(prefix); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /** Hash all original bytes even when only the proven prefix is written. Bounded I/O and shared deadline. */
    static void copyAndVerify(Path source, Path output, PsRecordingTimeline original, long cutoff, long deadline)
            throws IOException {
        ExactDurationTrimmer.checkProcessingBudget(deadline, source.getParent());
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) != original.sourceBytes()) {
            throw new IOException("末 AU 补救期间原始 PS 类型或长度变化");
        }
        if (cutoff < 0 || cutoff >= original.sourceBytes()) throw new IOException("末 AU 副本边界无效");
        MessageDigest hash = PsDuplicateAccessUnits.sha256();
        try (var input = Files.newInputStream(source);
             var target = output == null ? OutputStream.nullOutputStream() : Files.newOutputStream(output)) {
            byte[] buffer = new byte[65_536];
            long position = 0;
            long nextSpaceCheck = System.nanoTime() + 1_000_000_000L;
            while (position < original.sourceBytes()) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("末 AU 补救被中断");
                RecordingDeadline.check(deadline);
                if (System.nanoTime() - nextSpaceCheck >= 0) {
                    ExactDurationTrimmer.checkProcessingBudget(deadline, source.getParent());
                    nextSpaceCheck = System.nanoTime() + 1_000_000_000L;
                }
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, original.sourceBytes() - position));
                if (count < 0) throw new IOException("末 AU 补救期间原始 PS 缩短");
                hash.update(buffer, 0, count);
                if (position < cutoff) target.write(buffer, 0, (int) Math.min(count, cutoff - position));
                position += count;
            }
            if (input.read() != -1 || Files.size(source) != original.sourceBytes() || !original.matchesSourceHash(hash.digest())) {
                throw new IOException("末 AU 补救期间原始 PS 内容变化");
            }
        }
        ExactDurationTrimmer.checkProcessingBudget(deadline, source.getParent());
    }
}
