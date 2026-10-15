package com.hiki.isup.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaSessionTest {

    @Test
    void terminalStatusCannotBeOverwrittenByLateCompletionOrData() {
        MediaSession session = new MediaSession("cancelled", SessionType.DOWNLOAD, "DLWH", 1);
        session.setStatus(SessionStatus.FAILED);
        session.setStatus(SessionStatus.COMPLETED);
        session.setStatus(SessionStatus.RUNNING);
        assertEquals(SessionStatus.FAILED, session.getStatus());
    }

    @Test
    @DisplayName("再次设置清理钩子时先执行旧钩子，停止时再执行新钩子")
    void replacingReleaseHookRunsPreviousThenNew() {
        MediaSession session = new MediaSession("s1", SessionType.PREVIEW, "GW4206623", 1);
        List<String> order = new ArrayList<>();
        session.setReleaseHook(() -> order.add("old"));
        session.setReleaseHook(() -> order.add("new"));

        assertEquals(List.of("old"), order);

        session.runReleaseHook();
        assertEquals(List.of("old", "new"), order);

        session.runReleaseHook();
        assertEquals(List.of("old", "new"), order);
    }
}
