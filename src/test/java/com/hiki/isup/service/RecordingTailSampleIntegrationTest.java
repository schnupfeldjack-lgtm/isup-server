package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.MediaSession;
import com.hiki.isup.model.SessionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicitly opt in with the preserved, immutable PS sample; never reads production. */
class RecordingTailSampleIntegrationTest {
    @TempDir Path directory;

    @Test void preservedCh2SampleIsRepairedOnlyOutsideBusinessAndOriginalStaysIdentical() throws Exception {
        String sampleProperty = System.getProperty("isup.test.tailSource", "");
        String ffmpeg = System.getProperty("isup.test.ffmpeg", "");
        assumeTrue(!sampleProperty.isBlank() && !ffmpeg.isBlank(), "requires preserved local tailSource and ffmpeg");
        Path sample = Path.of(sampleProperty);
        String expected = "adc8b56075eeb97932c9a5923c8489721d7b3077416fb44196dcbce50b5cbb60";
        assertEquals(expected, hash(sample));
        assertEquals(114512864, Files.size(sample));
        Path source = Files.copy(sample, directory.resolve("original-download.ps"));
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 29, 10), end = start.plusSeconds(70);
        var timeline = PsRecordingTimeline.parse(source);
        var proof = timeline.tailRepairPlan(start, end);
        assertEquals(22, timeline.anchors().size());
        assertEquals(114421776, proof.cutoffBytes());
        assertEquals(825764236, proof.pts90k());
        assertEquals(1132, proof.missingBytes());
        MediaSession session = new MediaSession("preserved-ch2-tail", SessionType.DOWNLOAD, "local-evidence", 2);
        session.setRawFilePath(source.toString()); session.setStartTime(start.minusSeconds(5)); session.setEndTime(end.plusSeconds(15));
        IsupProperties properties = new IsupProperties(); properties.setFfmpegPath(ffmpeg);
        long deadline = RecordingDeadline.afterSeconds(240);
        List<List<String>> commands = new ArrayList<>();
        List<String> successfulDecodes = new ArrayList<>();
        RecordingRemuxer remuxer = new RecordingRemuxer(properties, (command, actualDeadline, parent) -> {
            assertEquals(deadline, actualDeadline);
            commands.add(command);
            String result = ExactDurationTrimmer.runUntil(command, actualDeadline, parent);
            if (command.contains("-progress")) successfulDecodes.add(result);
            return result;
        });
        Path output = remuxer.remux(session, start, end, deadline);
        assertTrue(Files.size(output) > 100_000_000);
        assertEquals(4, commands.size(), "One failed strict decode, one proved repair, one final strict decode");
        assertEquals(1, successfulDecodes.size());
        long lastFrame = -1;
        String lastProgress = "";
        for (String line : successfulDecodes.getFirst().split("\\R")) {
            if (line.startsWith("frame=")) lastFrame = Long.parseLong(line.substring(6).trim());
            if (line.startsWith("progress=")) lastProgress = line.trim();
        }
        assertEquals(2199, lastFrame);
        assertEquals("progress=end", lastProgress);
        assertEquals(expected, hash(source)); assertEquals(expected, hash(sample));
        try (var files = Files.list(directory)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("recording-tail-"))); }
        System.out.printf("TAIL_SAMPLE_PASS sourceBytes=%d cutoff=%d pts=%d missing=%d decodedFrames=2199 sourceSha=%s%n",
                Files.size(source), proof.cutoffBytes(), proof.pts90k(), proof.missingBytes(), hash(source));
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(java.io.OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(digest.digest());
    }
}
