package com.hiki.isup.service;

import com.hiki.isup.config.IsupProperties;
import com.sun.jna.NativeLibrary;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

/** Isolated Linux/JDK21 probe. Never run inside the test JVM. */
public final class RecoveryExitNativeProbe {
    public static void main(String[] args) throws Exception {
        // args: native library absolute path, system|halt|fixed, port, ready file
        NativeLibrary nativeHandler = NativeLibrary.getInstance(args[0]);
        nativeHandler.getFunction("install_hanging_handler").invokeVoid(new Object[0]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(Path.of(args[3] + ".java-hook"), "Java shutdown hook entered"); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }));
        ServerSocket listener = new ServerSocket(Integer.parseInt(args[2]));
        RecordingRecoveryService service = new RecordingRecoveryService(null, null, null, null,
                null, null, null, new IsupProperties());
        var field = RecordingRecoveryService.class.getDeclaredField("exit");
        field.setAccessible(true);
        Runnable action = (Runnable) field.get(service);
        if (args[1].equals("fixed")) ((LinuxRecoveryExit) action).prepare();
        Files.writeString(Path.of(args[3]), "ready:" + listener.getLocalPort());
        switch (args[1]) {
            case "system" -> System.exit(75);
            case "halt" -> Runtime.getRuntime().halt(75);
            case "fixed" -> action.run();
            default -> throw new IllegalArgumentException(args[1]);
        }
    }
}
