package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RecordingVfrWindowTest {
    @Test void provenVfrKeepsEveryPictureAndOnlyClipsBoundaryDisplayIntervals() throws Exception {
        var frames = new RecordingFrameProbe.Frames(RecordingH264ContinuityProbeTest.SOURCE, List.of(), .04);
        var proof = RecordingH264ContinuityProbeTest.parse(RecordingH264ContinuityProbeTest.trace(), frames.video());
        var selected = frames.selectWindow(.10, .19, () -> proof);
        assertTrue(selected.variable());
        assertEquals(List.of(.08, .16), selected.source());
        assertEquals(0, selected.presentation().getFirst(), 1e-9);
        assertEquals(.06, selected.presentation().getLast(), 1e-9);
        assertEquals(.09, selected.duration(), 1e-9);
        assertEquals(.03, selected.lastDuration(), 1e-9);
    }

    @Test void ordinaryCfrNeverInvokesProtocolProbe() throws Exception {
        var frames = new RecordingFrameProbe.Frames(List.of(0d, .04, .08, .12, .16, .20), List.of(), .04);
        var selected = frames.selectWindow(.02, .18, () -> { throw new AssertionError("not needed"); });
        assertFalse(selected.variable());
        assertEquals(List.of(.04, .08, .12, .16), selected.source());
    }

    @Test void boundaryInsideLongSourceIntervalCannotHideBehindOrdinaryEdgeTolerance() throws Exception {
        var frames = new RecordingFrameProbe.Frames(RecordingH264ContinuityProbeTest.SOURCE, List.of(), .04);
        assertDoesNotThrow(() -> frames.validateWindow(.13, .22));
        var proof = RecordingH264ContinuityProbeTest.parse(RecordingH264ContinuityProbeTest.trace(), frames.video());
        var selected = frames.selectWindow(.13, .22, () -> proof);
        assertTrue(selected.variable());
        assertEquals(List.of(.08, .16, .20), selected.source());
        assertEquals(.09, selected.duration(), 1e-9);
        assertEquals(.03, selected.presentation().get(1), 1e-9);
    }

    @Test void proofCannotAuthorizeUncoveredStartEndOrUnknownCadence() throws Exception {
        var frames = new RecordingFrameProbe.Frames(RecordingH264ContinuityProbeTest.SOURCE, List.of(), .04);
        var proof = RecordingH264ContinuityProbeTest.parse(RecordingH264ContinuityProbeTest.trace(), frames.video());
        assertThrows(IOException.class, () -> frames.selectWindow(-.02, .19, () -> proof));
        assertThrows(IOException.class, () -> frames.selectWindow(.10, .40, () -> proof));
        var unknown = new RecordingFrameProbe.Frames(frames.video(), List.of(), 0);
        assertThrows(IOException.class, () -> unknown.selectWindow(.10, .19, () -> proof));
    }

    @Test void normalBoundaryAtIdrStillFailsClosedWhenVariableProofIsRequired() throws Exception {
        var source = RecordingH264ContinuityProbeTest.SOURCE;
        var lines = new java.util.ArrayList<>(RecordingH264ContinuityProbeTest.trace());
        int packet = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Packet:")) packet++;
            if (packet == 4 && lines.get(i).contains(" nal_unit_type ")) {
                lines.set(i, RecordingH264ContinuityProbeTest.field("nal_unit_type", 5));
            }
        }
        var proof = RecordingH264ContinuityProbeTest.parse(lines, source);
        var frames = new RecordingFrameProbe.Frames(source, List.of(), .04);
        assertThrows(IOException.class, () -> frames.selectWindow(.13, .22, () -> proof));
    }

    @Test void outputUsesSourceProofButStillRejectsMissingAddedShiftedOrLongTailFrames() throws Exception {
        var frames = new RecordingFrameProbe.Frames(RecordingH264ContinuityProbeTest.SOURCE, List.of(), .04);
        var proof = RecordingH264ContinuityProbeTest.parse(RecordingH264ContinuityProbeTest.trace(), frames.video());
        var selected = frames.selectWindow(.05, .27, () -> proof);
        List<Double> expected = selected.presentation();
        var actual = new RecordingFrameProbe.Frames(expected, List.of(), .04,
                expected.stream().map(v -> v.equals(expected.getLast()) ? selected.lastDuration() : .04).toList());
        actual.validateOutput(selected, false);
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(expected.subList(1, expected.size()), List.of(), .04).validateOutput(selected, false));
        var shifted = expected.stream().map(v -> v > .1 ? v + .04 : v).toList();
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(shifted, List.of(), .04).validateOutput(selected, false));
        assertThrows(IOException.class, () -> new RecordingFrameProbe.Frames(expected, List.of(), .04,
                expected.stream().map(v -> .2).toList()).validateOutput(selected, false));
    }
}
