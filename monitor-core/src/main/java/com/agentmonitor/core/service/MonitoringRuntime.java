package com.agentmonitor.core.service;

import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.model.SessionRetentionPolicy;
import java.util.List;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.report.AnalysisConfig;
import com.agentmonitor.model.trace.TraceNode;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One foreground capture lifecycle shared by desktop and CLI. No UI toolkit or process exit. */
public final class MonitoringRuntime implements AutoCloseable {
    private final String pid;
    private final java.time.Instant targetStartedAt;
    public void requireExpectedStart(String expected){if(!targetStartedAt.equals(java.time.Instant.parse(expected)))throw new IllegalArgumentException("Target PID was reused or start identity differs");}
    private final MonitoringConfig config;
    private final MonitoringSession session;
    private final TraceServer collector;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final Object lifecycle = new Object();
    private final Object stopLock = new Object();
    private final CompletableFuture<Void> attachFinished = new CompletableFuture<>();
    private final Attacher attacher;
    @FunctionalInterface interface Attacher {
        void attach(String pid, MonitoringConfig config, int port, MonitoringSession session) throws Exception;
    }
    private TargetJvmLease lease;
    private int port;
    private boolean attachAttempted;
    private volatile boolean closed;
    private volatile SessionRetentionManager.CleanupResult cleanupResult = new SessionRetentionManager.CleanupResult(List.of(), List.of());
    private TraceServer.StopResult stopResult;
    private volatile Runnable onReady;
    private final com.agentmonitor.core.diagnostics.DiagnosticRecorder diagnostics;
    private RollingCaptureArchive rolling;
    private volatile String rollingFailure="";
    public String rollingFailure(){return rollingFailure;}
    public void enableRolling(long windowMillis,long maxBytes)throws Exception {
        synchronized(lifecycle){if(port!=0||closed||rolling!=null)throw new IllegalStateException("Configure rolling before start");rolling=new RollingCaptureArchive(session,config,windowMillis,maxBytes);collector.setOnCompletedRoot(rolling::accept);diagnostics.setOnSample(rolling::sample);diagnostics.enableRolling();rolling.setOnWindowClosed(diagnostics::checkpointToWindow);}
    }
    public boolean captureBudgetReached(){return rolling!=null&&rolling.budgetReached();}
    public Path rollingManifestPath(){return rolling==null?null:rolling.manifestPath();}

    public MonitoringRuntime(String pid, MonitoringConfig config) { this(pid, config, JvmService::attachAgent); }
    MonitoringRuntime(String pid, MonitoringConfig config, Attacher attacher) {
        this.attacher = attacher;
        long target = Long.parseLong(pid);
        if (target == ProcessHandle.current().pid() || !ProcessHandle.of(target).map(ProcessHandle::isAlive).orElse(false))
            throw new IllegalArgumentException("需要一个仍存活的外部 JVM PID");
        targetStartedAt=ProcessHandle.of(target).flatMap(p->p.info().startInstant()).orElseThrow(()->new IllegalArgumentException("Cannot verify target start identity"));
        config.requireValid(); this.pid = Long.toString(target); this.config = config;
        session = MonitoringSession.create(config.output().sessionRootDir());
        diagnostics = new com.agentmonitor.core.diagnostics.DiagnosticRecorder(pid, config.diagnostics(), session);
        var a = config.analysis();
        collector = new TraceServer(0, new AnalysisConfig(a.enabled(), a.slowTraceThresholdMs(), a.slowSpanThresholdMs(),
                a.slowSelfTimeThresholdMs(), a.includeErrorTrace(), a.maxTracesPerRootMethod(), a.maxBottlenecksPerTrace(), a.maxDepth()), session);
        collector.setOnReady(() -> { synchronized (lifecycle) { if (!closed && !ready.isDone()){if(rolling!=null)try{rolling.start();}catch(Exception e){ready.completeExceptionally(e);return;} diagnostics.start();} }
            ready.complete(null); Runnable callback = onReady; if (!closed && callback != null) callback.run(); });
    }
    public MonitoringSession session() { return session; }
    public java.util.Map<String,Object> diagnosticSnapshot() { return diagnostics.latest(); }
    public void setOnRootNode(Consumer<TraceNode> handler) { collector.setOnRootNode(handler); }
    public void setOnStatusChange(Consumer<String> handler) { collector.setOnStatusChange(handler); }
    public void setOnReady(Runnable handler) { onReady = handler; }
    public int startCollector() throws Exception {
        synchronized (lifecycle) {
            if (closed || port != 0) throw new IllegalStateException("采集会话不能重复启动");
            if(!targetAlive())throw new IllegalStateException("Target exited or PID start identity changed");
            lease = TargetJvmLease.acquire(pid);
            try {
                com.agentmonitor.core.util.AtomicFiles.write(session.sessionDirectory().resolve("config.yml"), com.agentmonitor.core.config.MonitoringConfigYaml.write(config));
                port = collector.start(); return port;
            }
            catch (Exception error) { lease.close(); lease = null; throw error; }
        }
    }
    public void attach() throws Exception {
        synchronized (lifecycle) {
            if (closed || port == 0 || attachAttempted) throw new IllegalStateException("Collector 未启动或已经挂载");
            if(!targetAlive())throw new IllegalStateException("Target exited or PID start identity changed before attach");
            lease.state("ATTACHING"); attachAttempted = true;
        }
        try {
            // loadAgent can block in the target's retransformation. Never hold the stop mutex here.
            attacher.attach(pid, config, port, session);
            synchronized (lifecycle) { if (!closed && lease != null) lease.state("MONITORING"); }
        } finally { attachFinished.complete(null); }
    }
    public void start() throws Exception { startCollector(); attach(); awaitReady(20, TimeUnit.SECONDS); }
    public void awaitReady(long timeout, TimeUnit unit) throws Exception { ready.get(timeout, unit); }
    public long getEventCount() { return collector.getEventCount(); }
    public boolean wasAttachAttempted() { synchronized (lifecycle) { return attachAttempted; } }
    public Path getPerformanceReportPath() { return collector.getPerformanceReportPath(); }
    public SessionRetentionManager.CleanupResult cleanupResult() { return cleanupResult; }
    public TraceServer.StopResult stopAndAwaitRestore() {
        synchronized (stopLock) {
            boolean attempted;
            synchronized (lifecycle) {
                if (stopResult != null) return stopResult;
                closed = true; attempted = attachAttempted;
            }
            if (attempted && !attachFinished.isDone()) {
                try { attachFinished.get(2, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (ExecutionException | TimeoutException ignored) { }
            }
            TraceServer.StopResult result;
            // A timer rotation after diagnostics starts closing would have no JFR checkpoint.
            // Preserve this window until close() can copy the finalized master recording.
            if(rolling!=null)rolling.pauseRotation();
            try {
                result = attempted ? collector.stopAndAwaitRestore() : TraceServer.StopResult.notConfirmed("Agent 尚未挂载");
                collector.stop();
            } catch (Exception error) {
                result = TraceServer.StopResult.notConfirmed("停止会话失败: " + error.getMessage());
                collector.stop();
            }
            diagnostics.close();
            diagnostics.attachSummary(getPerformanceReportPath());
            if(rolling!=null)try{rolling.close();}catch(Exception failure){rollingFailure=failure.toString();com.agentmonitor.core.util.AppLog.warn("Rolling archive finalization failed: "+rollingFailure);}
            if (result.restored() && result.outputDrained()) {
                try {
                    cleanupResult = new SessionRetentionManager().pruneCompletedSessions(session,
                            new SessionRetentionPolicy(config.output().retention().maxSessions()));
                } catch (RuntimeException error) {
                    cleanupResult = new SessionRetentionManager.CleanupResult(List.of(), List.of(error.toString()));
                }
            }
            synchronized (lifecycle) {
                try {
                    if (lease != null) lease.state(!attempted || result.restored() || !targetAlive() ? "IDLE" : "UNKNOWN");
                } catch (Exception error) {
                    result = new TraceServer.StopResult(result.status(), result.message()
                            + "；会话锁状态写入失败: " + error.getMessage(), result.captureQuality());
                } finally { if (lease != null) { lease.close(); lease = null; } }
                stopResult = result;
            }
            return result;
        }
    }
    public boolean targetAlive() { return ProcessHandle.of(Long.parseLong(pid)).filter(ProcessHandle::isAlive).flatMap(p->p.info().startInstant()).map(targetStartedAt::equals).orElse(false); }
    public void stop() { stopAndAwaitRestore(); }
    @Override public void close() { stopAndAwaitRestore(); }
}
