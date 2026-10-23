package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Mp4DownloadTailTest {
    @TempDir Path temp;

    @Test void preservesMediaBytesAndOtherTracksWhileRemovingOneTerminalSample() throws Exception {
        byte[] video = run(0x300, 3, 8);
        byte[] audio = run(0x300, 4, 8);
        byte[] media = box("mdat", new byte[1024 * 1024]);
        byte[] source = join(movie(), box("moof", join(traf(1, video), traf(2, audio))), media, box("mfra", new byte[8]));
        Path path = write(source);
        assertEquals(1, Mp4DownloadTail.finish(path, deadline()));
        byte[] output = Files.readAllBytes(path);
        assertEquals(source.length, output.length);
        int at = find(output, "trun");
        assertEquals(2, integer(output, at + 8));
        assertArrayEquals(audio, java.util.Arrays.copyOfRange(output, find(output, "trun", at + 4) - 4,
                find(output, "trun", at + 4) - 4 + audio.length));
        int mdat = find(output, "mdat") - 4;
        assertArrayEquals(media, java.util.Arrays.copyOfRange(output, mdat, mdat + media.length));
        assertEquals(-1, find(output, "mfra"));
    }

    @Test void fourByteEntriesLeaveAValidFreeBoxWithoutMovingPayload() throws Exception {
        Path path = write(join(movie(), box("moof", traf(1, run(0x200, 4, 4))), box("mdat", new byte[80])));
        assertEquals(2, Mp4DownloadTail.finish(path, deadline()));
        byte[] result = Files.readAllBytes(path); int trun = find(result, "trun");
        assertEquals(2, integer(result, trun + 8));
        assertEquals(8, integer(result, find(result, "free") - 4));
    }

    @Test void implicitEntriesAndASingleSampleRunAreSupported() throws Exception {
        Path implicit = write(join(movie(), box("moof", traf(1, run(0, 5, 0)))));
        assertEquals(1, Mp4DownloadTail.finish(implicit, deadline()));
        byte[] bytes = Files.readAllBytes(implicit);
        assertEquals(4, integer(bytes, find(bytes, "trun") + 8));
        Path single = temp.resolve("single.mp4");
        Files.write(single, join(movie(), box("moof", traf(1, run(0x200, 1, 4)))));
        byte[] before = Files.readAllBytes(single);
        assertEquals(0, Mp4DownloadTail.finish(single, deadline()));
        assertArrayEquals(before, Files.readAllBytes(single));
    }

    @Test void onlyTheLastVideoRunChangesAndNonfragmentedFilesStayUnchanged() throws Exception {
        byte[] first = box("moof", traf(1, run(0x300, 5, 8)));
        Path path = write(join(movie(), first, box("mdat", new byte[80]), box("moof", traf(1, run(0x300, 4, 8)))));
        assertEquals(1, Mp4DownloadTail.finish(path, deadline()));
        byte[] result = Files.readAllBytes(path); int at = find(result, "trun");
        assertEquals(5, integer(result, at + 8));
        assertEquals(3, integer(result, find(result, "trun", at + 4) + 8));
        byte[] flat = join(movie(), box("mdat", new byte[80]));
        Files.write(path, flat); assertEquals(0, Mp4DownloadTail.finish(path, deadline()));
        assertArrayEquals(flat, Files.readAllBytes(path));
    }

    @Test void unsupportedTableDoesNotPartiallyModifyTheFile() throws Exception {
        byte[] bad = join(movie(), box("moof", traf(1, run(0x300, 5, 4))));
        Path path = write(bad);
        assertThrows(java.io.IOException.class, () -> Mp4DownloadTail.finish(path, deadline()));
        assertArrayEquals(bad, Files.readAllBytes(path));
        assertThrows(java.io.IOException.class, () -> Mp4DownloadTail.finish(path, System.nanoTime() - 1));
        assertArrayEquals(bad, Files.readAllBytes(path));
    }

    @Test void customIndexesAndImplicitDataOffsetsAreLeftUnchanged() throws Exception {
        byte[] normal = join(movie(), box("moof", traf(1, run(0x300, 5, 8))));
        byte[] indexed = join(normal, box("sidx", new byte[20]));
        Path path = write(indexed);
        assertEquals(0, Mp4DownloadTail.finish(path, deadline()));
        assertArrayEquals(indexed, Files.readAllBytes(path));
        byte[] implicit = normal.clone(); int trun = find(implicit, "trun");
        ByteBuffer.wrap(implicit).putInt(trun + 4, 0x300);
        Files.write(path, implicit);
        assertEquals(0, Mp4DownloadTail.finish(path, deadline()));
        assertArrayEquals(implicit, Files.readAllBytes(path));
    }

    @Test void removingOnlyNonSyncSamplesPreservesTheSeekIndex() throws Exception {
        byte[] tfhd = box("tfhd", ByteBuffer.allocate(20).putInt(0x21).putInt(1).putLong(0).putInt(0x1010000).array());
        byte[] source = join(movie(), box("moof", box("traf", join(tfhd, run(0x300, 5, 8)))), box("mfra", new byte[8]));
        Path path = write(source);
        assertEquals(1, Mp4DownloadTail.finish(path, deadline()));
        assertTrue(find(Files.readAllBytes(path), "mfra") >= 0);
    }

    @Test void existing4kDetailSampleKeepsItsCodecAndMediaBytes() throws Exception {
        String source = System.getProperty("isup.test.detailSample", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!source.isBlank());
        Path output = Path.of(source).resolveSibling("packing-detail-tail-fixed.mp4");
        Files.copy(Path.of(source), output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        int drop = Mp4DownloadTail.finish(output, deadline());
        assertTrue(drop >= 1 && drop <= 2);
        assertEquals(Files.size(Path.of(source)), Files.size(output));
    }

    @Test void actualNativeFilesKeepTheirMediaPayloadAndLoseOnlyTheTerminalTableEntries() throws Exception {
        String directory = System.getProperty("isup.test.nativeSamples", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!directory.isBlank());
        for (String kind : new String[]{"short", "long"}) {
            Path source = Path.of(directory).resolve("panorama-native-" + kind + "-20261023.mp4");
            byte[] original = Files.readAllBytes(source);
            Path output = temp.resolve(kind + ".mp4"); Files.write(output, original);
            int drop = Mp4DownloadTail.finish(output, deadline());
            assertTrue(drop >= 1 && drop <= 2);
            byte[] changed = Files.readAllBytes(output);
            assertEquals(original.length, changed.length);
            int differences = 0;
            for (int i = 0; i < original.length; i++) if (original[i] != changed[i]) differences++;
            assertTrue(differences < 32, "只调整封装表项，不能重写视频数据");
            Files.copy(output, Path.of(directory).resolve("panorama-native-" + kind + "-index-fixed.mp4"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path write(byte[] bytes) throws Exception { Path path = temp.resolve("test.mp4"); Files.write(path, bytes); return path; }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }
    private static byte[] movie() throws Exception {
        byte[] tkhd = new byte[20]; ByteBuffer.wrap(tkhd).putInt(12, 1);
        byte[] handler = new byte[12]; ByteBuffer.wrap(handler).putInt(8, 0x76696465);
        return box("moov", box("trak", join(box("tkhd", tkhd), box("mdia", box("hdlr", handler)))));
    }
    private static byte[] traf(int id, byte[] run) throws Exception { return box("traf", join(box("tfhd", ByteBuffer.allocate(16).putInt(1).putInt(id).putLong(0).array()), run)); }
    private static byte[] run(int flags, int count, int stride) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(12 + count * stride).putInt(flags | 1).putInt(count).putInt(0);
        while (bytes.hasRemaining()) bytes.putInt(100);
        return box("trun", bytes.array());
    }
    private static byte[] box(String type, byte[] body) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeInt(body.length + 8); out.writeBytes(type); out.write(body); return bytes.toByteArray();
    }
    private static byte[] join(byte[]... inputs) throws Exception { var out = new ByteArrayOutputStream(); for (byte[] b : inputs) out.write(b); return out.toByteArray(); }
    private static int integer(byte[] data, int at) { return ByteBuffer.wrap(data).getInt(at); }
    private static int find(byte[] data, String type) { return find(data, type, 0); }
    private static int find(byte[] data, String type, int from) {
        byte[] pattern = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int i = from; i <= data.length - 4; i++) { for (int j = 0; j < 4; j++) if (data[i+j] != pattern[j]) continue outer; return i; }
        return -1;
    }
}
