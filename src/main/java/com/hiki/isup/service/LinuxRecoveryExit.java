package com.hiki.isup.service;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import java.util.function.Supplier;

/** Emergency recovery only: bypass both Java hooks and native SDK atexit handlers. */
final class LinuxRecoveryExit implements Runnable {
    private final Supplier<Function> resolver;
    // Keep the library and function strongly reachable until process termination.
    private NativeLibrary library;
    private Function exit;

    LinuxRecoveryExit() {
        resolver = () -> {
            if (!Platform.isLinux()) throw new IllegalStateException("Recovery exit requires Linux libc _exit");
            library = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
            return library.getFunction("_exit");
        };
    }

    LinuxRecoveryExit(Supplier<Function> resolver) {
        this.resolver = resolver;
    }

    synchronized void prepare() {
        if (exit != null) return;
        try {
            exit = java.util.Objects.requireNonNull(resolver.get(), "libc _exit binding");
        } catch (LinkageError | RuntimeException failure) {
            throw new IllegalStateException("Cannot prepare Linux recovery exit", failure);
        }
    }

    @Override
    public synchronized void run() {
        if (exit == null) throw new IllegalStateException("Recovery exit was not prepared");
        exit.invokeVoid(new Object[]{75});
        throw new IllegalStateException("libc _exit unexpectedly returned");
    }
}
