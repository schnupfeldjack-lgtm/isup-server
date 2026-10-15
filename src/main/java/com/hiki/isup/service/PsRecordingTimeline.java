package com.hiki.isup.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Comparator;

/**
 * 海康 PS 中的绝对钟点与原始视频 PTS 对应关系，不依赖转封装后的 MP4 零点。
 *
 * <p>只解析完整 PS/PSM/PES 结构内的 HK 描述符。SDK 的 PackVideoFrame 将同一
 * FRAME_INFO 的 PSM 放在视频 PES 前，因此将它绑定到下一视频 PES 的 PTS；最终仍须
 * 用同一原始 PS 的解码帧验证这些锚点，不能把本类的结构解析当成录像完整性的证明。
 * 若仅末包 EOF，可返回不含该包及其待绑定 HK 的完整前缀；它仍须经过覆盖与严格解码校验。</p>
 */
public final class PsRecordingTimeline {
    private static final int MAX_ANCHORS = 100_000;
    private static final long MAX_PACKETS = 10_000_000;
    private static final int MAX_PREFIX = 65_536;
    private static final double MAX_ANCHOR_GAP_SECONDS = 10;
    private static final double MAX_MAPPING_ERROR_SECONDS = .08;
    private final List<Anchor> anchors;
    private final int streamId;
    private final int audioStreamCount;
    private final long sourceBytes;
    private final long completePrefixBytes;
    private final boolean incompleteTail;
    private final LocalDateTime incompleteTailHk;
    private final Anchor incompleteTailAnchor;
    private final List<PsDuplicateAccessUnits.VideoPacket> duplicateVideoPackets;
    private final byte[] sourceHash;

    private PsRecordingTimeline(List<Anchor> anchors, int streamId, int audioStreamCount,
                                long sourceBytes, long completePrefixBytes, boolean incompleteTail,
                                LocalDateTime incompleteTailHk, Anchor incompleteTailAnchor,
                                List<PsDuplicateAccessUnits.VideoPacket> duplicateVideoPackets, byte[] sourceHash) {
        this.anchors = List.copyOf(anchors);
        this.streamId = streamId;
        this.audioStreamCount = audioStreamCount;
        this.sourceBytes = sourceBytes;
        this.completePrefixBytes = completePrefixBytes;
        this.incompleteTail = incompleteTail;
        this.incompleteTailHk = incompleteTailHk;
        this.incompleteTailAnchor = incompleteTailAnchor;
        this.duplicateVideoPackets = List.copyOf(duplicateVideoPackets);
        this.sourceHash = sourceHash.clone();
    }

    public record Anchor(LocalDateTime wallTime, long pts90k, long fileOffset) {
        public double ptsSeconds() { return pts90k / 90_000d; }
    }

    public record Window(double startPtsSeconds, double endPtsSeconds, List<Anchor> anchors) {
        public Window { anchors = List.copyOf(anchors); }
    }

    public List<Anchor> anchors() { return anchors; }
    public int streamId() { return streamId; }
    public int audioStreamCount() { return audioStreamCount; }
    public long sourceBytes() { return sourceBytes; }
    public long completePrefixBytes() { return completePrefixBytes; }
    public boolean hasIncompleteTail() { return incompleteTail; }
    List<PsDuplicateAccessUnits.VideoPacket> duplicateVideoPackets() { return duplicateVideoPackets; }
    boolean matchesSourceHash(byte[] hash) { return MessageDigest.isEqual(sourceHash, hash); }

    public static PsRecordingTimeline parse(Path source) throws IOException {
        return parse(source, Long.MAX_VALUE);
    }

    static PsRecordingTimeline parse(Path source, long deadlineNanos) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            return new Parser(new BufferedInputStream(input, 65_536), deadlineNanos, source.toAbsolutePath().getParent()).read();
        }
    }

    /** 调用者拥有输入流；PS 包、锚点列表和相邻重复候选 AU 均有大小上限。 */
    static PsRecordingTimeline parse(InputStream source) throws IOException {
        return new Parser(new BufferedInputStream(source, 65_536), Long.MAX_VALUE, null).read();
    }

    /**
     * 只接受由至少两个锚点完整覆盖本单的一个连续段。墙钟/PTS 跳变会断段；
     * 若本单跨越断点、缺头尾、或回拨造成重复覆盖，拒绝生成看似完整的成片。
     */
    public Window plan(LocalDateTime start, LocalDateTime end) throws IOException {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new RecordingValidationException("本单录像时间区间无效");
        }
        List<List<Anchor>> segments = new ArrayList<>();
        List<Anchor> current = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (!current.isEmpty() && !continuous(current.getFirst(), current.getLast(), anchor)) {
                segments.add(current);
                current = new ArrayList<>();
            }
            current.add(anchor);
        }
        if (!current.isEmpty()) segments.add(current);
        if (incompleteTailHk != null && (incompleteTailAnchor == null
                || !continuous(current.getFirst(), current.getLast(), incompleteTailAnchor))) {
            throw new RecordingValidationException("不完整尾包的 HK/PTS 与完整前缀不连续或无法验证，不能排除墙钟回拨: hk="
                    + incompleteTailHk);
        }
        // PES 是解码序；只有同一墙钟/PTS 映射内的锚点才能按展示序排列。
        // Parser 已校验 DTS 单调且 PTS >= DTS，不能靠全局排序掩盖时间回退。
        for (List<Anchor> segment : segments) segment.sort(Comparator.comparingLong(Anchor::pts90k));
        Window result = null;
        List<Anchor> selectedSegment = null;
        for (List<Anchor> segment : segments) {
            if (segment.size() < 2 || segment.getFirst().wallTime().isAfter(start)
                    || segment.getLast().wallTime().isBefore(end)) continue;
            if (result != null) throw new RecordingValidationException("录像墙钟重复覆盖本单区间，不能唯一确定录像片段");
            Anchor first = segment.getFirst();
            result = new Window(first.ptsSeconds() + seconds(first.wallTime(), start),
                    first.ptsSeconds() + seconds(first.wallTime(), end), segment);
            selectedSegment = segment;
        }
        if (result == null) {
            // 只能把“已证明连续，但还没取够”交给近期录像的有限重试。
            // 锚点过稀、单锚点、跳时与歧义均不能靠等待当作完整录像放行。
            if (segments.size() == 1 && segments.getFirst().size() >= 2) {
                List<Anchor> segment = segments.getFirst();
                throw new RecordingCoverageException(start, end,
                        segment.getFirst().wallTime(), segment.getLast().wallTime());
            }
            throw new RecordingValidationException("录像缺少完整覆盖本单的连续时间段：HK/PTS跳变、锚点过稀或起止覆盖不足");
        }
        if (incompleteTailHk != null && incompleteTailHk.isBefore(end)) {
            throw new RecordingValidationException("不完整尾包的 HK 落在已覆盖订单结束点之前，不能排除本单缺帧或墙钟回拨: hk="
                    + incompleteTailHk + ", requestedEnd=" + end);
        }
        for (List<Anchor> segment : segments) {
            if (segment != selectedSegment && !segment.getLast().wallTime().isBefore(start)
                    && segment.getFirst().wallTime().isBefore(end)) {
                throw new RecordingValidationException("录像回拨后片段与本单部分重叠，不能唯一确定画面归属");
            }
        }
        return result;
    }

    private static boolean continuous(Anchor first, Anchor previous, Anchor next) {
        double wallStep = seconds(previous.wallTime(), next.wallTime());
        double ptsStep = (next.pts90k() - previous.pts90k()) / 90_000d;
        double fromFirst = seconds(first.wallTime(), next.wallTime());
        double ptsFromFirst = (next.pts90k() - first.pts90k()) / 90_000d;
        return wallStep != 0 && ptsStep != 0 && Math.signum(wallStep) == Math.signum(ptsStep)
                && Math.abs(wallStep) <= MAX_ANCHOR_GAP_SECONDS && Math.abs(ptsStep) <= MAX_ANCHOR_GAP_SECONDS
                // 相邻误差是两个相对段首误差之差，最坏可达 2 倍；整段仍须守住原容差。
                && Math.abs(wallStep - ptsStep) <= 2 * MAX_MAPPING_ERROR_SECONDS + 1e-9
                && Math.abs(fromFirst - ptsFromFirst) <= MAX_MAPPING_ERROR_SECONDS + 1e-9;
    }

    private static double seconds(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toNanos() / 1_000_000_000d;
    }

    private static final class Parser {
        private final InputStream input;
        private final MessageDigest sourceHash = PsDuplicateAccessUnits.sha256();
        private final PsDuplicateAccessUnits duplicates = new PsDuplicateAccessUnits();
        private final List<Anchor> anchors = new ArrayList<>();
        private final Set<Long> anchorPts = new HashSet<>();
        private final Set<Integer> audioIds = new HashSet<>();
        private final long deadlineNanos;
        private final Path sessionDirectory;
        private long nextSpaceCheck = System.nanoTime() + 1_000_000_000L;
        private long offset;
        private long packets;
        private long previousPts = -1;
        private long previousDts = -1;
        private long previousOffset = -1;
        private LocalDateTime previousHk;
        private int videoId = -1;
        private int videoType = -1;
        private LocalDateTime pendingTime;
        private long pendingOffset;
        private AccessUnit currentAccessUnit;
        private AccessUnit incompleteAccessUnit;

        private Parser(InputStream input, long deadlineNanos, Path sessionDirectory) {
            this.input = new DigestInputStream(input, sourceHash);
            this.deadlineNanos = deadlineNanos;
            this.sessionDirectory = sessionDirectory;
        }

        private PsRecordingTimeline read() throws IOException {
            try {
                findFirstPack();
                readPack();
            } catch (TruncatedPacketException e) {
                throw invalid("原始 PS 首包截断", offset);
            }
            long completePrefixBytes = offset;
            boolean incompleteTail = false;
            LocalDateTime incompleteTailHk = null;
            Anchor incompleteTailAnchor = null;
            while (true) {
                checkBudget();
                long packetOffset = offset;
                long incompletePts = -1;
                try {
                    int first = readByte();
                    if (first < 0) break;
                    if (first != 0 || requiredByte() != 0 || requiredByte() != 1) {
                        throw invalid("PS 包边界不合法", packetOffset);
                    }
                    int id = requiredByte();
                    if (++packets > MAX_PACKETS) throw invalid("PS 包数量超过上限", packetOffset);
                    if (id == 0xb9) {
                        if (readByte() != -1) throw invalid("PS 结束码后仍有数据", packetOffset);
                        completePrefixBytes = offset;
                        break;
                    }
                    if (id == 0xba) {
                        readPack();
                    } else {
                        if (id < 0xbb) throw invalid("PS 流类型不支持", packetOffset);
                        int length = (requiredByte() << 8) | requiredByte();
                        if (length == 0 && id >= 0xe0 && id <= 0xef) {
                            throw invalid("不支持未指定长度的视频 PES", packetOffset);
                        }
                        byte[] payload;
                        try {
                            payload = requiredBytes(length);
                        } catch (TruncatedPacketException e) {
                            // 残包已包含的 PES 时间头仍须合法，不能用 EOF 掩盖已知 DTS 回退。
                            if (id >= 0xe0 && id <= 0xef) incompletePts = readVideo(id, e.partialBytes, packetOffset, false);
                            throw e;
                        }
                        if (id == 0xbc) readPsm(payload, packetOffset);
                        else if (id >= 0xe0 && id <= 0xef) readVideo(id, payload, packetOffset);
                        else if (id >= 0xc0 && id <= 0xdf
                                || id == 0xbd && !isKnownHikPrivateRecord(payload)) audioIds.add(id);
                    }
                    // HK 只有绑定到完整视频 PES 后才可信；尾部缺 PES 时连同该 PSM 一起排除。
                    completePrefixBytes = pendingTime == null ? offset : pendingOffset;
                } catch (TruncatedPacketException e) {
                    // 仅真实 EOF 可保留完整前缀，结构错误、时间回退与普通 I/O 故障仍直接失败。
                    // 同一可信 HK/PTS/DTS 的续包截断时，须连首 PES 一起排除，不能留下半个 AU。
                    // 无法证明续包归属时仍只保留结构前缀，后续严格解码不得放行半帧。
                    incompleteTail = true;
                    incompleteTailHk = pendingTime;
                    if (incompleteAccessUnit != null) {
                        completePrefixBytes = incompleteAccessUnit.startOffset();
                        incompleteTailAnchor = incompleteAccessUnit.anchor();
                        incompleteTailHk = incompleteTailAnchor.wallTime();
                        anchors.removeLast();
                        anchorPts.remove(incompleteTailAnchor.pts90k());
                    } else if (pendingTime != null && incompletePts >= 0) {
                        incompleteTailAnchor = new Anchor(pendingTime, incompletePts, packetOffset);
                    }
                    pendingTime = null;
                    break;
                }
            }
            duplicates.end();
            if (pendingTime != null) throw invalid("HK 描述符后缺少视频 PES", pendingOffset);
            if (videoId < 0 || anchors.isEmpty()) throw new RecordingValidationException("原始 PS 缺少可信的 HK 视频时间锚点");
            return new PsRecordingTimeline(anchors, videoId, audioIds.size(), offset, completePrefixBytes,
                    incompleteTail, incompleteTailHk, incompleteTailAnchor,
                    duplicates.skippedPackets(), sourceHash.digest());
        }

        private void findFirstPack() throws IOException {
            int word = 0;
            for (int i = 0; i < MAX_PREFIX; i++) {
                if (i % 1024 == 0) checkBudget();
                int value = readByte();
                if (value < 0) throw new RecordingValidationException("原始录像没有 MPEG-2 PS 包头");
                word = (word << 8) | value;
                if (i >= 3 && word == 0x000001ba) return;
            }
            throw new RecordingValidationException("原始录像 PS 前导数据超过上限");
        }

        private void readPack() throws IOException {
            byte[] header = requiredBytes(10);
            if ((u(header[0]) & 0xc4) != 0x44 || (u(header[2]) & 4) != 4
                    || (u(header[4]) & 4) != 4 || (u(header[5]) & 1) != 1
                    || (u(header[8]) & 3) != 3 || (u(header[9]) & 0xf8) != 0xf8) {
                throw invalid("MPEG-2 PS 包头标记不合法", offset - 14);
            }
            requiredBytes(u(header[9]) & 7);
        }

        private void readPsm(byte[] data, long packetOffset) throws IOException {
            if (pendingTime != null) throw invalid("HK 描述符尚未关联视频就出现下一 PSM", packetOffset);
            if (data.length < 10 || (u(data[0]) & 0xe0) != 0xe0 || u(data[1]) != 0xff) {
                throw invalid("PSM 头部或保留位不合法", packetOffset);
            }
            int infoEnd = 4 + uint16(data, 2);
            if (infoEnd > data.length - 6) throw invalid("PSM 描述符区长度越界", packetOffset);
            LocalDateTime time = descriptors(data, 4, infoEnd, packetOffset);
            int mapEnd = infoEnd + 2 + uint16(data, infoEnd);
            if (mapEnd != data.length - 4) throw invalid("PSM 基本流映射长度不匹配", packetOffset);
            int position = infoEnd + 2;
            while (position < mapEnd) {
                if (position + 4 > mapEnd) throw invalid("PSM 基本流项截断", packetOffset);
                int streamType = u(data[position]);
                int id = u(data[position + 1]);
                int descriptorEnd = position + 4 + uint16(data, position + 2);
                if (descriptorEnd > mapEnd) throw invalid("PSM 基本流描述符越界", packetOffset);
                if (id >= 0xe0 && id <= 0xef) {
                    if (streamType != 0x1b && streamType != 0x24) {
                        throw invalid("仅支持 H.264/H.265 视频时间对齐", packetOffset);
                    }
                    noteVideoId(id, packetOffset);
                    videoType = streamType;
                } else if (id >= 0xc0 && id <= 0xdf || id == 0xbd || isAudioType(streamType)) {
                    // 私有 stream_type 的 G.711 可被 FFmpeg 识别为 Data，仍须要求实际音频证据。
                    audioIds.add(id);
                }
                // SDK 可解析基本流描述符，但全局时间只取 program_stream_info 的 0x40。
                descriptors(data, position + 4, descriptorEnd, packetOffset);
                position = descriptorEnd;
            }
            // SDK MakePSM 明确写入四个 0 CRC 字节，不能把 CRC=0 当损坏拒绝。
            duplicates.psm(data, videoType);
            currentAccessUnit = null;
            if (time != null) {
                pendingTime = time;
                pendingOffset = packetOffset;
            }
        }

        private LocalDateTime descriptors(byte[] data, int from, int end, long packetOffset) throws IOException {
            LocalDateTime result = null;
            for (int i = from; i < end;) {
                if (i + 2 > end) throw invalid("PSM 描述符头截断", packetOffset);
                int descriptorEnd = i + 2 + u(data[i + 1]);
                if (descriptorEnd > end) throw invalid("PSM 描述符长度越界", packetOffset);
                if (u(data[i]) == 0x40) {
                    if (u(data[i + 1]) != 0x0e || data[i + 2] != 0x48 || data[i + 3] != 0x4b
                            || (u(data[i + 10]) & 0x20) == 0) {
                        throw invalid("HK 时间描述符格式不支持", packetOffset);
                    }
                    LocalDateTime time = HikStreamClock.parseDescriptor(data, i);
                    if (time == null || result != null) throw invalid("HK 时间无效或重复", packetOffset);
                    result = time;
                }
                i = descriptorEnd;
            }
            return result;
        }

        private void readVideo(int id, byte[] data, long packetOffset) throws IOException {
            readVideo(id, data, packetOffset, true);
        }

        private long readVideo(int id, byte[] data, long packetOffset, boolean complete) throws IOException {
            noteVideoId(id, packetOffset);
            if (data.length < 3 || (u(data[0]) & 0xc0) != 0x80) {
                throw invalid("视频 PES 头部不合法", packetOffset);
            }
            int flags = u(data[1]) >>> 6;
            int headerLength = u(data[2]);
            if (headerLength + 3 >= data.length || flags == 1) {
                throw invalid("视频 PES 可选头或载荷截断", packetOffset);
            }
            if (flags == 0) {
                if (pendingTime != null) throw invalid("HK 对应首个视频 PES 缺少 PTS", packetOffset);
                if (complete) duplicates.continuation(id, data, headerLength + 3, packetOffset, offset);
                else duplicates.uncertainVideo();
                if (complete) currentAccessUnit = null;
                return -1;
            }
            int expectedHeader = flags == 3 ? 10 : 5;
            if (headerLength < expectedHeader || (u(data[3]) >>> 4) != flags
                    || (u(data[3]) & 1) == 0 || (u(data[5]) & 1) == 0 || (u(data[7]) & 1) == 0) {
                throw invalid("视频 PTS 标记不合法", packetOffset);
            }
            if (flags == 3 && ((u(data[8]) >>> 4) != 1 || (u(data[8]) & 1) == 0
                    || (u(data[10]) & 1) == 0 || (u(data[12]) & 1) == 0)) {
                throw invalid("视频 DTS 标记不合法", packetOffset);
            }
            long pts = timestamp(data, 3);
            // PES 不携带 DTS 时表示 DTS=PTS；有 B 帧时不能把传输顺序当作展示顺序。
            long dts = flags == 3 ? timestamp(data, 8) : pts;
            boolean repeatedAnchor = complete && dts == previousDts && pts == previousPts && pendingTime != null
                    && duplicates.permitsRepeatedAnchor(id, pendingTime, pts, dts);
            if (pts < dts || dts < previousDts
                    || dts == previousDts && (pts != previousPts || pendingTime != null && !repeatedAnchor)) {
                throw invalid("视频 DTS 回退、重复解码时间或 PTS/DTS 33 位回绕，拒绝猜测时间归一化"
                        + ": previousPts=" + previousPts + ", previousDts=" + previousDts
                        + ", previousHk=" + previousHk + ", previousOffset=" + previousOffset
                        + ", pts=" + pts + ", dts=" + dts + ", hk=" + pendingTime
                        + ", hkOffset=" + (pendingTime == null ? -1 : pendingOffset), packetOffset);
            }
            if (!complete) {
                duplicates.uncertainVideo();
                if (pendingTime == null && currentAccessUnit != null
                        && currentAccessUnit.anchor().pts90k() == pts && currentAccessUnit.dts90k() == dts) {
                    incompleteAccessUnit = currentAccessUnit;
                }
                return pts;
            }
            if (currentAccessUnit != null
                    && (currentAccessUnit.anchor().pts90k() != pts || currentAccessUnit.dts90k() != dts)) {
                currentAccessUnit = null;
            }
            previousPts = pts;
            previousDts = dts;
            previousOffset = packetOffset;
            previousHk = pendingTime;
            duplicates.video(id, pendingTime, pts, dts, data, headerLength + 3,
                    packetOffset, offset, repeatedAnchor);
            if (pendingTime != null) {
                if (repeatedAnchor) {
                    // 这里只暂缓重复锚点；整组闭合且字节一致之前 parse 不会成功返回。
                    pendingTime = null;
                    return pts;
                }
                if (anchors.size() >= MAX_ANCHORS) throw invalid("录像时间锚点超过上限", packetOffset);
                if (!anchorPts.add(pts)) throw invalid("视频重复 PTS 锚点，不能唯一对齐画面", packetOffset);
                Anchor anchor = new Anchor(pendingTime, pts, packetOffset);
                anchors.add(anchor);
                currentAccessUnit = new AccessUnit(anchor, dts, pendingOffset);
                pendingTime = null;
            }
            return pts;
        }

        private static long timestamp(byte[] data, int at) {
            return ((long) (u(data[at]) & 0x0e) << 29) | ((long) u(data[at + 1]) << 22)
                    | ((long) (u(data[at + 2]) & 0xfe) << 14) | ((long) u(data[at + 3]) << 7) | (u(data[at + 4]) >>> 1);
        }

        private static boolean isAudioType(int type) {
            return type == 0x03 || type == 0x04 || type == 0x0f || type == 0x11
                    || type == 0x81 || type == 0x90 || type == 0x91;
        }

        /** 仅排除 SDK 与实录共同证实的完整私有记录；未知 BD 仍按潜在音频保护。 */
        private static boolean isKnownHikPrivateRecord(byte[] data) {
            // 已验证的单包格式：无加密、带 PTS、7 字节可选头及完整帧标记 ff f8。
            // 不把未知扩展、分包或损坏时间头猜成元数据。PSM 中的音频声明仍独立保留。
            if (data.length < 14 || u(data[0]) != 0x8c || u(data[1]) != 0x80 || u(data[2]) != 7
                    || (u(data[3]) & 0xf1) != 0x21 || (u(data[5]) & 1) != 1 || (u(data[7]) & 1) != 1
                    || u(data[8]) != 0xff || u(data[9]) != 0xf8) return false;
            int type = uint16(data, 10);
            int words = uint16(data, 12);
            // PackPrivtFrame 的类型 2/8；ParsePrivtPacket 的长度单位为 4 字节。
            // 要求精确闭合，不能忽略记录后的未知数据或把截断载荷视作完整记录。
            return (type == 2 || type == 8) && words > 0 && 14 + words * 4 == data.length;
        }

        private void checkBudget() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("原始 PS 解析被中断");
            RecordingDeadline.check(deadlineNanos);
            if (sessionDirectory != null && System.nanoTime() - nextSpaceCheck >= 0) {
                RecordingStorageGuard.check(sessionDirectory);
                nextSpaceCheck = System.nanoTime() + 1_000_000_000L;
                RecordingDeadline.check(deadlineNanos);
            }
        }

        private void noteVideoId(int id, long packetOffset) throws IOException {
            if (videoId >= 0 && videoId != id) throw invalid("单次录像包含多个视频流，不能唯一对齐", packetOffset);
            videoId = id;
        }

        private int readByte() throws IOException {
            int value = input.read();
            if (value >= 0) offset++;
            return value;
        }

        private int requiredByte() throws IOException {
            int value = readByte();
            if (value < 0) throw new TruncatedPacketException(new byte[0]);
            return value;
        }

        private byte[] requiredBytes(int count) throws IOException {
            byte[] bytes = input.readNBytes(count);
            offset += bytes.length;
            if (bytes.length != count) throw new TruncatedPacketException(bytes);
            return bytes;
        }

        private static final class TruncatedPacketException extends IOException {
            private final byte[] partialBytes;
            private TruncatedPacketException(byte[] partialBytes) { this.partialBytes = partialBytes; }
        }

        private record AccessUnit(Anchor anchor, long dts90k, long startOffset) { }

        private static int u(byte value) { return value & 0xff; }
        private static int uint16(byte[] data, int at) { return (u(data[at]) << 8) | u(data[at + 1]); }
        private static RecordingValidationException invalid(String reason, long offset) {
            return new RecordingValidationException(reason + "，PS offset=" + offset);
        }
    }
}
