package com.agentmonitor.agent.exporter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.agentmonitor.agent.log.AgentLog;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.protocol.SpanPayloadLimits;
import com.agentmonitor.model.output.ExporterStatistics;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.agent.interceptor.OverheadController;

/** Decorator pattern: moves serialization and destination I/O behind a bounded queue. */
public final class AsyncSpanExporter implements SpanExporter {

    private static final int QUEUE_CAPACITY = 8192;
    private static final long QUEUE_POLL_MILLIS = 100;
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MILLIS = 5_000;

    private final SpanExporter delegate;
    private record Pending(SpanData span,long estimatedBytes){}
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final long maxResidentBytes;
    private final AtomicLong residentBytes=new AtomicLong(),residentSpans=new AtomicLong(),byteBudgetDrops=new AtomicLong(),workerWallNanos=new AtomicLong();
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
        this(delegate,MonitoringConfig.Overhead.defaults());
    }
    AsyncSpanExporter(SpanExporter delegate,MonitoringConfig.Overhead budget){this.delegate=delegate;this.maxResidentBytes=budget.queueMaxBytes();}

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
        span = SpanPayloadLimits.limit(span);
        long bytes=estimatedBytes(span);
        synchronized (lifecycleLock) {
            // STOP closes admission before waiting for the queue. Keeping admission and offer in
            // one critical section establishes a finite queue boundary for drain().
            if (!accepting || bytes>maxResidentBytes-residentBytes.get()) {
                if(accepting)byteBudgetDrops.incrementAndGet();
                queueDrops.incrementAndGet();
                return ExportResult.rejected();
            }
            residentBytes.addAndGet(bytes);
            residentSpans.incrementAndGet();
            if(!queue.offer(new Pending(span,bytes))){residentBytes.addAndGet(-bytes);residentSpans.decrementAndGet();queueDrops.incrementAndGet();return ExportResult.rejected();}
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
                rejectedDestinations.get(), residentSpans.get());
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
            delegate.captureQuality(statistics());
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
                Pending first = queue.poll(QUEUE_POLL_MILLIS, TimeUnit.MILLISECONDS);
                OverheadController.observe(residentBytes.get(),System.nanoTime());
                if (first == null) continue;
                java.util.List<Pending> pending = new java.util.ArrayList<>(); pending.add(first); queue.drainTo(pending, 127);
                java.util.List<SpanData> batch=pending.stream().map(Pending::span).toList();
                long started=System.nanoTime();
                try {
                    java.util.List<ExportResult> results = delegate.exportBatch(batch);
                    for (ExportResult result : results) {
                        if (!result.delivered()) deliveryDrops.incrementAndGet();
                        rejectedDestinations.addAndGet(result.rejectedDestinations());
                    }
                } catch (Throwable error) { deliveryDrops.addAndGet(batch.size()); }
                finally {residentBytes.addAndGet(-pending.stream().mapToLong(Pending::estimatedBytes).sum());residentSpans.addAndGet(-pending.size());workerWallNanos.addAndGet(Math.max(0,System.nanoTime()-started));}
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable ignored) {
                deliveryDrops.incrementAndGet();
            }
        }
    }

    public java.util.Map<String,Object> budgetStatistics(){return java.util.Map.of("maxResidentEstimatedBytes",maxResidentBytes,"residentEstimatedBytes",residentBytes.get(),"byteBudgetDroppedSpans",byteBudgetDrops.get(),"exportWorkerWallNanos",workerWallNanos.get(),"policy",OverheadController.snapshot());}
    private static long estimatedBytes(SpanData s){
        long n=1024;for(String value:new String[]{s.traceId(),s.spanId(),s.parentSpanId(),s.className(),s.methodName(),s.signature(),s.threadName(),s.arguments(),s.returnValue(),s.stackTrace(),s.statusDescription()})n+=64+2L*value.length();
        for(var a:s.attributes().entrySet())n+=128+2L*(a.getKey().length()+a.getValue().length());
        for(var e:s.events()){n+=128+2L*e.name().length();for(var a:e.attributes().entrySet())n+=128+2L*(a.getKey().length()+a.getValue().length());}
        return n;
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
        return ExporterDrainResult.failed(residentSpans.get(), droppedCount(), rejectedDestinationCount(), detail);
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
