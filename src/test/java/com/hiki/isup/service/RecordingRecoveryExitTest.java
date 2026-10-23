package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RecordingRecoveryExitTest {
    @Test
    void productionExitCannotBeHeldByShutdownHook() throws Exception {
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-XX:ActiveProcessorCount=1", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(8, TimeUnit.SECONDS), "production recovery exit hung in shutdown hook");
            assertEquals(com.sun.jna.Platform.isLinux() ? 75 : 42, child.exitValue());
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) { } }
            }));
            RecordingRecoveryService service = new RecordingRecoveryService(null, null, null, null,
                    null, null, null, new IsupProperties());
            Runnable exit = (Runnable) ReflectionTestUtils.getField(service, "exit");
            try {
                // Resolves only when present, so this also exercises the old production action.
                try { exit.getClass().getDeclaredMethod("prepare").invoke(exit); }
                catch (NoSuchMethodException oldAction) { }
                exit.run();
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (!com.sun.jna.Platform.isLinux() && failure.getCause() instanceof IllegalStateException) {
                    Runtime.getRuntime().halt(42);
                }
                throw failure;
            }
        }
    }
}
