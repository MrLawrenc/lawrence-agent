package com.agentmonitor.tools;

import com.agentmonitor.app.model.MonitoringSession;
import com.agentmonitor.app.report.model.AnalysisConfig;
import com.agentmonitor.app.service.JvmService;
import com.agentmonitor.app.service.OfflineTraceReplayLoader;
import com.agentmonitor.app.service.TraceServer;
import com.agentmonitor.app.util.AtomicFiles;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

/** Uses the same production services as MainController; no preview or fixture services. */
public final class DesktopSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: desktopSmoke <pid> <exact-class> [seconds=45]");
        long pid = Long.parseLong(args[0]);
        if (pid == ProcessHandle.current().pid() || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            throw new IllegalArgumentException("Target must be a live external JVM");
        String exactClass = args[1];
        if (!exactClass.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+"))
            throw new IllegalArgumentException("Expected one exact JVM class name");
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 45;
        if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("Duration must be 1–120 seconds");
        var session = MonitoringSession.create(Path.of("build", "desktop-smoke").toAbsolutePath().toString());
        var analysis = new AnalysisConfig(true, 1, 1, 1, true, 20, 5, 50);
        var collector = new TraceServer(0, analysis, session);
        var ready = new CompletableFuture<Void>();
        collector.setOnReady(() -> ready.complete(null));
        var defaults = MonitoringConfig.defaults();
        var config = new MonitoringConfig(2,
                new MonitoringConfig.Scope(List.of(), List.of(exactClass), List.of(), defaults.scope().excludeMethods()),
                new MonitoringConfig.Sampling(100, 50, 512, 1, MonitoringConfig.TailOverflowPolicy.PROMOTE),
                new MonitoringConfig.Output(session.sessionRootDirectory().toString(), defaults.output().exporters(),
                        new MonitoringConfig.Capture(false, false, false), defaults.output().spanFiles(), defaults.output().retention()),
                new MonitoringConfig.Resource("idea-desktop-pipeline-check", "1", "local-verification"),
                new MonitoringConfig.Dependencies(false, false),
                new MonitoringConfig.Analysis(true, 1, 1, 1, true, 20, 5, 50));
        Thread hook = new Thread(collector::stopAndAwaitRestore, "desktop-smoke-restore");
        Runtime.getRuntime().addShutdownHook(hook);
        boolean restored = false;
        try {
            int port = collector.start();
            System.out.println("SESSION " + session.sessionDirectory());
            JvmService.attachAgent(Long.toString(pid), config, port, session);
            ready.get(20, TimeUnit.SECONDS);
            System.out.println("READY target=" + pid + " class=" + exactClass + "; arguments/returns disabled");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline && !Files.exists(session.sessionDirectory().resolve("stop.request")))
                Thread.sleep(100);
            var stop = collector.stopAndAwaitRestore();
            restored = stop.restored();
            var json = new ObjectMapper();
            var report = json.readTree(session.performanceReportPath().toFile());
            var replay = new OfflineTraceReplayLoader().load(session.sessionDirectory());
            var manifest = json.readTree(session.spansDirectory().resolve("manifest.json").toFile());
            var result = json.createObjectNode();
            result.put("targetPid", pid); result.put("exactClass", exactClass);
            result.put("restored", restored); result.put("stopMessage", stop.message());
            result.put("receivedSpans", collector.getEventCount()); result.put("replayedSpans", replay.acceptedSpans());
            result.put("replayedRoots", replay.roots().size()); result.put("replayDegraded", replay.hasDegradation());
            result.put("targetStillAlive", ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            result.set("captureQuality", json.valueToTree(stop.captureQuality()));
            result.put("reportStatus", report.path("status").asText());
            boolean verified = restored && stop.outputDrained() && !stop.captureQuality().hasDegradation()
                    && report.path("analysisReady").asBoolean() && manifest.path("captureComplete").asBoolean()
                    && !replay.hasDegradation() && replay.acceptedSpans() > 0
                    && replay.acceptedSpans() == collector.getEventCount() && result.path("targetStillAlive").asBoolean();
            result.put("verified", verified);
            AtomicFiles.write(session.sessionDirectory().resolve("desktop-smoke-result.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.println("REPORT " + session.performanceReportPath());
            if (!verified) throw new IllegalStateException("Full desktop pipeline verification did not pass; inspect the session artifacts");
        } finally {
            if (!restored) collector.stopAndAwaitRestore();
            collector.stop();
            Runtime.getRuntime().removeShutdownHook(hook);
        }
    }
}
