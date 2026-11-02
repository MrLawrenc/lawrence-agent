package com.agentmonitor.core.diagnostics;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation. Completion is published only after the work has closed its resources. */
public final class DiagnosticTask {
    public enum State { READY, RUNNING, SUCCEEDED, CANCELLED, FAILED }
    public record Progress(State state, String phase, boolean cancellable, boolean cancelRequested, long elapsedMillis) { }
    @FunctionalInterface public interface Work<T> { T run() throws Exception; }
    private static final ThreadLocal<DiagnosticTask> CURRENT = new ThreadLocal<>();
    private volatile State state = State.READY;
    private volatile String phase = "准备执行";
    private volatile boolean cancellable = true, cancelRequested;
    private volatile long started, ended;

    public synchronized boolean requestCancel() {
        if (state == State.SUCCEEDED || state == State.CANCELLED || state == State.FAILED) return false;
        cancelRequested = true;
        return true;
    }

    public Progress progress() {
        long start = started;
        return new Progress(state, phase, cancellable, cancelRequested,
                start == 0 ? 0 : ((ended == 0 ? System.nanoTime() : ended) - start) / 1_000_000);
    }

    public <T> T run(Work<T> work) throws Exception {
        synchronized (this) {
            if (state != State.READY || CURRENT.get() != null) throw new IllegalStateException("Diagnostic task already running or reused");
            started = System.nanoTime(); state = State.RUNNING;
        }
        CURRENT.set(this);
        try {
            checkpoint();
            T result = work.run();
            synchronized (this) {
                checkpoint();
                state = State.SUCCEEDED;
            }
            return result;
        } catch (CancellationException cancelled) {
            if (cancelled.getSuppressed().length != 0) {
                state = State.FAILED;
                throw new IllegalStateException("取消后的资源清理失败；请核对目标端状态与输出文件", cancelled);
            }
            state = State.CANCELLED;
            throw cancelled;
        } catch (Exception | Error failure) {
            state = State.FAILED;
            throw failure;
        } finally {
            ended = System.nanoTime();
            CURRENT.remove();
        }
    }

    /** Call before a new phase; target operations marked false must finish before cancellation settles. */
    public static void phase(String name, boolean cancellable) {
        checkpoint();
        DiagnosticTask task = CURRENT.get();
        if (task != null) { task.phase = name; task.cancellable = cancellable; }
    }

    public static void checkpoint() {
        DiagnosticTask task = CURRENT.get();
        if (task != null && task.cancelRequested) throw new CancellationException("诊断已取消；已生成的证据文件保留");
    }

    public static void pause(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        do {
            checkpoint();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            Thread.sleep(Math.max(1, Math.min(100, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
        } while (true);
    }
}
