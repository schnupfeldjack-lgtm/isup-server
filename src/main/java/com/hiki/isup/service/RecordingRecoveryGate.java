package com.hiki.isup.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 进程恢复与业务请求互斥；正常运行时不改变请求处理。 */
@Component
@Order(20)
public class RecordingRecoveryGate extends OncePerRequestFilter {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final LongSupplier clock;
    private volatile long lastRequestFinished;
    private volatile boolean closed;
    private final AtomicLong activity = new AtomicLong();

    @Autowired
    public RecordingRecoveryGate() { this(System::nanoTime); }

    RecordingRecoveryGate(LongSupplier clock) {
        this.clock = clock;
        this.lastRequestFinished = clock.getAsLong();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/")
                || path.equals("/api/v1/recordings/diagnostics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!lock.readLock().tryLock()) {
            response.sendError(503, "录像服务正在恢复预检，请稍后重试");
            return;
        }
        try {
            if (closed) {
                response.sendError(503, "录像服务正在恢复，请稍后重试");
                return;
            }
            activity.incrementAndGet();
            chain.doFilter(request, response);
        } finally {
            activity.incrementAndGet();
            lastRequestFinished = clock.getAsLong();
            lock.readLock().unlock();
        }
    }

    public boolean tryQuiesce(long quietSeconds, BooleanSupplier prepare) {
        return tryQuiesce(quietSeconds, activityVersion(), prepare);
    }

    public long activityVersion() { return activity.get(); }

    public boolean tryQuiesce(long quietSeconds, long expectedActivity, BooleanSupplier prepare) {
        if (!lock.writeLock().tryLock()) return false;
        try {
            if (closed || activity.get() != expectedActivity
                    || clock.getAsLong() - lastRequestFinished < TimeUnit.SECONDS.toNanos(quietSeconds)) {
                return false;
            }
            closed = prepare.getAsBoolean();
            return closed;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
