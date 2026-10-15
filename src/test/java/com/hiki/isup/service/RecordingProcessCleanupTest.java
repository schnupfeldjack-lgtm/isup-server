package com.hiki.isup.service;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordingProcessCleanupTest {
    @Test void waitsForForcedExitEvenWhenCallerIsAlreadyInterrupted() throws Exception {
        Process process = process();
        when(process.isAlive()).thenReturn(true, false);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenAnswer(invocation -> {
            assertFalse(Thread.currentThread().isInterrupted(), "清理等待不能被已有中断立即跳过");
            return true;
        });
        Thread.currentThread().interrupt();
        try {
            RecordingProcessCleanup.finish(process, new Thread());
            assertTrue(Thread.currentThread().isInterrupted(), "必须恢复调用方中断语义");
            verify(process).destroyForcibly();
            verify(process).waitFor(5, TimeUnit.SECONDS);
        } finally { Thread.interrupted(); }
    }

    @Test void failureToTerminateIsExplicitNotReportedAsCleaned() throws Exception {
        Process process = process();
        when(process.isAlive()).thenReturn(true);
        when(process.waitFor(5, TimeUnit.SECONDS)).thenReturn(false);
        assertThrows(IOException.class, () -> RecordingProcessCleanup.finish(process, new Thread()));
    }

    private Process process() throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        return process;
    }
}
