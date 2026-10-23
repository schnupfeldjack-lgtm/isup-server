package com.hiki.isup.service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 下载封装收尾：不交付设备截止处可能截断的最后一个视频样本。只读写 MP4 索引，不读取或校验视频载荷。 */
final class Mp4DownloadTail {
    private Mp4DownloadTail() {}

    static int finish(Path path, long deadlineNanos) throws IOException {
        checkDeadline(deadlineNanos);
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            List<Box> roots = boxes(file, 0, file.length(), deadlineNanos);
            if (roots.stream().anyMatch(box -> box.type == type("sidx"))) return 0;
            Set<Integer> videoTracks = new HashSet<>();
            for (Box root : roots) {
                if (root.type != type("moov")) continue;
                for (Box trak : boxes(file, root.payload(), root.end(), deadlineNanos)) {
                    if (trak.type != type("trak")) continue;
                    Integer track = null; boolean video = false;
                    for (Box child : boxes(file, trak.payload(), trak.end(), deadlineNanos)) {
                        if (child.type == type("tkhd")) {
                            int version = readInt(file, child.payload()) >>> 24;
                            track = readInt(file, child.payload() + (version == 1 ? 20 : 12));
                        } else if (child.type == type("mdia")) {
                            for (Box media : boxes(file, child.payload(), child.end(), deadlineNanos)) {
                                if (media.type == type("hdlr")) video = readInt(file, media.payload() + 8) == type("vide");
                            }
                        }
                    }
                    if (video && track != null) videoTracks.add(track);
                }
            }
            if (videoTracks.size() != 1) return 0;
            Box last = null;
            Integer lastDefaultFlags = null;
            long videoSamples = 0;
            for (Box root : roots) {
                if (root.type != type("moof")) continue;
                for (Box traf : boxes(file, root.payload(), root.end(), deadlineNanos)) {
                    if (traf.type != type("traf")) continue;
                    List<Box> children = boxes(file, traf.payload(), traf.end(), deadlineNanos);
                    Integer track = null;
                    Integer defaultFlags = null;
                    for (Box child : children) if (child.type == type("tfhd")) {
                        int flags = readInt(file, child.payload()) & 0xffffff;
                        // 仅处理媒体地址不依赖前一 run 大小的封装，避免改变其它音视频 run 的隐式偏移。
                        if ((flags & 0x20001) == 0) return 0;
                        track = readInt(file, child.payload() + 4);
                        long cursor = child.payload() + 8 + ((flags & 1) != 0 ? 8 : 0)
                                + ((flags & 2) != 0 ? 4 : 0) + ((flags & 8) != 0 ? 4 : 0) + ((flags & 16) != 0 ? 4 : 0);
                        if ((flags & 32) != 0) defaultFlags = readInt(file, cursor);
                    }
                    for (Box child : children) if (child.type == type("trun") && (readInt(file, child.payload()) & 1) == 0) return 0;
                    if (!videoTracks.contains(track)) continue;
                    for (Box child : children) {
                        if (child.type == type("trun")) {
                            long samples = Integer.toUnsignedLong(readInt(file, child.payload() + 4));
                            if (samples > 0) { last = child; lastDefaultFlags = defaultFlags; videoSamples += samples; }
                        }
                    }
                }
            }
            // 自定义的非分片 MP4 沿用原封装；默认下载为 fragmented MP4。
            if (last == null) return 0;
            int flags = readInt(file, last.payload()) & 0xffffff;
            long count = Integer.toUnsignedLong(readInt(file, last.payload() + 4));
            int stride = Integer.bitCount(flags & 0xf00) * 4;
            long header = 8L + ((flags & 1) != 0 ? 4 : 0) + ((flags & 4) != 0 ? 4 : 0);
            if (header + count * stride != last.size - last.header) throw new IOException("下载 MP4 样本表布局不支持收尾");
            // 4 字节样本表项需排除两帧，才能留下合法的 8 字节 free box；不移动任何媒体数据。
            int drop = stride == 4 && count > 1 ? 2 : 1;
            // 极短素材沿用原文件，不能为收尾把唯一的视频样本也排除。
            if (videoSamples <= drop) return 0;
            boolean keepIndex = removedSamplesAreNonSync(file, last, flags, header, stride, count, drop, lastDefaultFlags);
            checkDeadline(deadlineNanos);
            if (count <= drop) {
                writeInt(file, last.offset + 4, type("free"));
            } else {
                writeInt(file, last.payload() + 4, (int) (count - drop));
                if (stride > 0) {
                    long gap = (long) stride * drop;
                    long newSize = last.size - gap;
                    if (last.header == 16) { file.seek(last.offset + 8); file.writeLong(newSize); }
                    else writeInt(file, last.offset, (int) newSize);
                    writeInt(file, last.offset + newSize, (int) gap);
                    writeInt(file, last.offset + newSize + 4, type("free"));
                }
            }
            // 最后一个可选随机访问索引可能引用被排除的终止样本；保留位置，改为空闲 box。
            if (!keepIndex) for (Box root : roots) if (root.type == type("mfra")) writeInt(file, root.offset + 4, type("free"));
            return drop;
        }
    }

    private static boolean removedSamplesAreNonSync(RandomAccessFile file, Box run, int flags, long header,
                                                     int stride, long count, int drop, Integer defaultFlags) throws IOException {
        for (long index = count - drop; index < count; index++) {
            Integer sampleFlags = defaultFlags;
            if ((flags & 0x400) != 0) {
                long offset = run.payload() + header + index * stride
                        + ((flags & 0x100) != 0 ? 4 : 0) + ((flags & 0x200) != 0 ? 4 : 0);
                sampleFlags = readInt(file, offset);
            } else if (index == 0 && (flags & 4) != 0) {
                sampleFlags = readInt(file, run.payload() + 8 + ((flags & 1) != 0 ? 4 : 0));
            }
            if (sampleFlags == null || (sampleFlags & 0x10000) == 0) return false;
        }
        return true;
    }

    private static List<Box> boxes(RandomAccessFile file, long start, long end, long deadline) throws IOException {
        List<Box> result = new ArrayList<>();
        for (long offset = start; offset < end;) {
            checkDeadline(deadline);
            if (end - offset < 8 || result.size() >= 100_000) throw new IOException("下载 MP4 box 范围无效");
            long size = Integer.toUnsignedLong(readInt(file, offset));
            int kind = readInt(file, offset + 4), header = 8;
            if (size == 1) {
                if (end - offset < 16) throw new IOException("下载 MP4 扩展 box 头不足");
                file.seek(offset + 8); size = file.readLong(); header = 16;
            } else if (size == 0) size = end - offset;
            if (size < header || size > end - offset) throw new IOException("下载 MP4 box 长度无效");
            result.add(new Box(offset, size, header, kind)); offset += size;
        }
        return result;
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || deadline - System.nanoTime() <= 0) throw new IOException("下载封装收尾超时或中断");
    }
    private static int readInt(RandomAccessFile file, long offset) throws IOException { file.seek(offset); return file.readInt(); }
    private static void writeInt(RandomAccessFile file, long offset, int value) throws IOException { file.seek(offset); file.writeInt(value); }
    private static int type(String name) { return name.charAt(0) << 24 | name.charAt(1) << 16 | name.charAt(2) << 8 | name.charAt(3); }
    private record Box(long offset, long size, int header, int type) {
        long payload() { return offset + header; }
        long end() { return offset + size; }
    }
}
