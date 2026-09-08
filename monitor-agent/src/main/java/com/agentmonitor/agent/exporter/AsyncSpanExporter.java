package com.agentmonitor.agent.exporter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.agentmonitor.agent.log.AgentLog;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.model.output.ExporterStatistics;

/** Decorator pattern: moves serialization and destination I/O behind a bounded queue. */
public final class AsyncSpanExporter implements SpanExporter {

    private static final int QUEUE_CAPACITY = 8192;
    private static final long QUEUE_POLL_MILLIS = 100;
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MILLIS = 5_000;

    private final SpanExporter delegate;
    private final BlockingQueue<SpanData> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong enqueuedSpans = new AtomicLong();
    private final AtomicLong queueDrops = new AtomicLong();
    private final AtomicLong deliveryDrops = new AtomicLong();
    private final AtomicLong rejectedDestinations = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private volatile boolean accepting;
    private volatile boolean closed;
    private volatile ExporterDrainResult lastDrainResult;
    private Thread worker;

    AsyncSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() { return "async(" + delegate.name() + ')'; }

    @Override
    public boolean start() {
        if (!delegate.start()) return false;
        synchronized (lifecycleLock) {
            if (closed) return false;
            running = true;
            accepting = true;
            worker = new Thread(this::run, "agent-monitor-span-exporter");
            worker.setDaemon(true);
            // Do not retain the target application's context class loader in a long-lived Agent thread.
            worker.setContextClassLoader(AsyncSpanExporter.class.getClassLoader());
            worker.start();
        }
        return true;
    }

    @Override
    public ExportResult export(SpanData span) {
        synchronized (lifecycleLock) {
            // STOP closes admission before waiting for the queue. Keeping admission and offer in
            // one critical section establishes a finite queue boundary for drain().
            if (!accepting || !queue.offer(span)) {
                queueDrops.incrementAndGet();
                return ExportResult.rejected();
            }
            enqueuedSpans.incrementAndGet();
            return ExportResult.accepted();
        }
    }

    @Override
    public void ready() { delegate.ready(); }

    public long droppedCount() { return queueDrops.get() + deliveryDrops.get(); }
    public long rejectedDestinationCount() { return rejectedDestinations.get(); }

    /** Snapshot used by the stop protocol and session report; it never mutates exporter state. */
    public ExporterStatistics statistics() {
        return new ExporterStatistics(true, enqueuedSpans.get(), queueDrops.get(), deliveryDrops.get(),
                rejectedDestinations.get(), queue.size());
    }

    @Override
    public ExporterDrainResult drain(long timeoutMillis) {
        long boundedTimeoutMillis = Math.max(0, timeoutMillis);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMillis);
        Thread activeWorker;
        synchronized (lifecycleLock) {
            if (lastDrainResult != null) return lastDrainResult;
            // No span can enter after this point. The worker's loop keeps consuming until the
            // finite queue is empty, then returns and establishes an exporter-side flush barrier.
            accepting = false;
            running = false;
            activeWorker = worker;
        }
        if (!awaitWorker(activeWorker, deadlineNanos)) {
            return completeDrain(failure("等待异步导出队列超时"));
        }
        long remainingMillis = remainingMillis(deadlineNanos);
        if (remainingMillis < 0) {
            return completeDrain(failure("异步导出队列已清空，但没有剩余 flush 时间"));
        }
        ExporterDrainResult result;
        try {
            result = delegate.drain(remainingMillis);
        } catch (Throwable error) {
            return completeDrain(failure(describe(error)));
        }
        if (result == null) return completeDrain(failure("导出器未返回 drain 结果"));
        return completeDrain(new ExporterDrainResult(result.drained(), result.pendingSpans(),
                droppedCount() + result.droppedSpans(),
                rejectedDestinationCount() + result.rejectedDestinations(), result.detail()));
    }

    private void run() {
        while (running || !queue.isEmpty()) {
            try {
                SpanData span = queue.poll(QUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (span == null) continue;
                ExportResult result = delegate.export(span);
                if (!result.delivered()) deliveryDrops.incrementAndGet();
                rejectedDestinations.addAndGet(result.rejectedDestinations());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
                deliveryDrops.incrementAndGet();
            }
        }
    }

    @Override
    public void close() {
        ExporterDrainResult result;
        Thread activeWorker;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            activeWorker = worker;
        }
        result = drain(SHUTDOWN_DRAIN_TIMEOUT_MILLIS);
        if (!result.drained()) {
            AgentLog.warn("[agent-monitor] exporter close timed out: " + result.summary());
            if (activeWorker != null && activeWorker.isAlive()) activeWorker.interrupt();
        }
        delegate.close();
    }

    private boolean awaitWorker(Thread activeWorker, long deadlineNanos) {
        if (activeWorker == null || activeWorker == Thread.currentThread()) return queue.isEmpty();
        long remainingMillis = remainingMillis(deadlineNanos);
        if (remainingMillis <= 0 && activeWorker.isAlive()) return false;
        try {
            activeWorker.join(remainingMillis);
            return !activeWorker.isAlive();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ExporterDrainResult failure(String detail) {
        return ExporterDrainResult.failed(queue.size(), droppedCount(), rejectedDestinationCount(), detail);
    }

    private ExporterDrainResult completeDrain(ExporterDrainResult result) {
        synchronized (lifecycleLock) {
            if (lastDrainResult == null) lastDrainResult = result;
            return lastDrainResult;
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos < 0) return -1;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static String describe(Throwable error) {
        if (error == null) return "未知导出器错误";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
