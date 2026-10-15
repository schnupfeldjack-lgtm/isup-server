package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecordingFrameProbeTest {
    @ParameterizedTest @ValueSource(ints = {5, 8, 10, 20, 25})
    void codecCadenceAllowsEveryIntactLowRateFrame(int fps) throws Exception {
        var frames = parsedFrames(fps, fps, -1);
        assertEquals(fps * 2, frames.validateWindow(1, 3).size());
    }

    @ParameterizedTest @ValueSource(ints = {5, 8, 10, 20, 25})
    void widerLowRateEdgesNeverHideMissingFrames(int fps) throws Exception {
        for (int missing : new int[]{fps, fps * 2 + 1, fps * 3 - 1}) {
            assertThrows(RecordingValidationException.class,
                    () -> parsedFrames(fps, fps, missing).validateWindow(1, 3));
        }
    }

    @Test void codecRateCannotBeReinferredAsLowFpsWhenMostFramesAreMissing() throws Exception {
        assertThrows(RecordingValidationException.class, () -> parsedFrames(5, 25, -1).validateWindow(1, 3));
        assertThrows(RecordingValidationException.class, () -> parsedFrames(25, 5, -1).validateWindow(1, 3));
    }

    @Test void unknownOrChangedFrameRateCannotAuthorizeWideFrameGaps() throws Exception {
        for (String rate : List.of("0/0", "0/1", "5/0")) {
            var parser = new RecordingFrameProbe.Parser();
            assertThrows(RecordingValidationException.class,
                    () -> parser.accept("[Parsed_showinfo_0] config in time_base: 1/90000, frame_rate: " + rate));
        }
        var parser = new RecordingFrameProbe.Parser();
        parser.accept("[Parsed_showinfo_0] config in time_base: 1/90000, frame_rate: 25/1");
        assertThrows(RecordingValidationException.class,
                () -> parser.accept("[Parsed_showinfo_0] config in time_base: 1/90000, frame_rate: 5/1"));
    }

    private static RecordingFrameProbe.Frames parsedFrames(int actualRate, int declaredRate, int missing) throws IOException {
        var parser = new RecordingFrameProbe.Parser();
        parser.accept("[Parsed_showinfo_0] config in time_base: 1/90000, frame_rate: " + declaredRate + "/1");
        for (int i = 0; i < actualRate * 4; i++) {
            if (i != missing) parser.accept("[Parsed_showinfo_0] n: " + i + " pts: " + (i * 90000L / actualRate));
        }
        return parser.finish();
    }

    @Test void missingOneOrTwoFramesMustNotPassAsNormal25FpsVideo() {
        var frames = new RecordingFrameProbe.Frames(List.of(0d, .04, .08, .16, .20, .24), List.of(), .04);
        assertThrows(IOException.class, () -> frames.validateWindow(0, .28));
    }
    @Test void detectsElevenSecondFreezeInsidePackingWindow() {
        var frames = new RecordingFrameProbe.Frames(List.of(0d, 11.51, 11.55, 11.59), List.of(), .04);
        assertThrows(IOException.class, () -> frames.validateWindow(0, 11.6));
    }

    @Test void allowsDiscardingBadLeadInButNeverBridgesGapInsideOrder() throws Exception {
        List<Double> pts = new ArrayList<>(List.of(0d));
        for (int n = 0; n <= 200; n++) pts.add(11.51 + n * .04);
        var frames = new RecordingFrameProbe.Frames(pts, List.of(), .04);
        var window = frames.validateWindow(12, 18);
        assertEquals(150, window.size());
        assertTrue(window.getFirst() >= 12);
        assertTrue(window.getLast() < 18);
    }

    @Test void refusesMissingStartOrEndAndRepeatedFrames() {
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(List.of(1d, 1.04), List.of(), .04).validateWindow(0, 1));
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(List.of(0d, .04), List.of(), .04).validateWindow(0, 1));
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(List.of(0d, .04, .04, .08), List.of(), .04).validateWindow(0, .1));
    }

    @Test void parsesIntegerPtsInsteadOfRoundedLargePtsTime() throws Exception {
        var parser = new RecordingFrameProbe.Parser();
        parser.accept("[Parsed_showinfo_0] config in time_base: 1/90000, frame_rate: 25/1");
        parser.accept("[Parsed_showinfo_0] n: 0 pts: 8100000036 pts_time:90000 duration:3600");
        parser.accept("[Parsed_ashowinfo_0] n:0 pts:720000003 pts_time:90000 rate:8000 nb_samples:160");
        var frames = parser.finish();
        assertEquals(90000.0004, frames.video().getFirst(), 1e-9);
        assertEquals(90000.000375, frames.audio().getFirst().start(), 1e-9);
    }

    @Test void outputMustPreserveEverySelectedFrameAndRelativeTiming() throws Exception {
        var selected = List.of(10d, 10.04, 10.08, 10.12);
        new RecordingFrameProbe.Frames(List.of(0d, .04, .08, .12), List.of(), .04).validateOutput(selected, false);
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(List.of(0d, .04, .08), List.of(), .04).validateOutput(selected, false));
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(List.of(0d, .04, .10, .14), List.of(), .04).validateOutput(selected, false));
    }
}
