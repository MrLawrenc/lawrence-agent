package com.agentmonitor.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.app.model.*;
import com.agentmonitor.app.report.model.AnalysisConfig;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPipelineIntegrationTest {
    @TempDir Path directory;
    @Test void realAttachProducesACompletedReportAndReplayWithoutAnyFixtureServices() throws Exception {
        Path classes = Path.of("build/classes/java/test").toAbsolutePath();
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes.toString(), "agentfixtures.apppipeline.TargetLoop").redirectErrorStream(true).start();
        var input = new BufferedReader(new InputStreamReader(child.getInputStream()));
        var output = new PrintWriter(child.getOutputStream(), true);
        MonitoringSession session = MonitoringSession.create(directory.toString());
        var analysis = new AnalysisConfig(true, 1, 1, 1, true, 20, 5, 50);
        TraceServer collector = new TraceServer(0, analysis, session);
        try {
            awaitLine(input, "READY");
            var ready = new CompletableFuture<Void>(); collector.setOnReady(() -> ready.complete(null));
            int port = collector.start();
            var defaults = MonitoringConfig.defaults();
            var config = new MonitoringConfig(2,
                    new MonitoringConfig.Scope(List.of(), List.of("agentfixtures.apppipeline.TargetLoop"), List.of(), List.of("main")),
                    new MonitoringConfig.Sampling(100, 50, 512, 1, MonitoringConfig.TailOverflowPolicy.PROMOTE),
                    new MonitoringConfig.Output(directory.toString(), defaults.output().exporters(),
                            new MonitoringConfig.Capture(false, false, false), defaults.output().spanFiles(), defaults.output().retention()),
                    new MonitoringConfig.Resource("pipeline-test", "1", "test"),
                    new MonitoringConfig.Dependencies(false, false), defaults.analysis());
            JvmService.attachAgent(Long.toString(child.pid()), config, port, session);
            ready.get(10, TimeUnit.SECONDS);
            output.println("RUN"); awaitLine(input, "WORK_DONE");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (collector.getEventCount() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(3, collector.getEventCount());
            var stop = collector.stopAndAwaitRestore();
            assertTrue(stop.restored(), stop::message); assertTrue(stop.outputDrained(), stop::message);
            assertFalse(stop.captureQuality().hasDegradation(), stop.captureQuality()::summary);
            var json = new ObjectMapper();
            var report = json.readTree(session.performanceReportPath().toFile());
            assertEquals("COMPLETED", report.path("status").asText());
            assertTrue(report.path("analysisReady").asBoolean()); assertEquals(3, report.path("observedRootTraces").asInt());
            assertFalse(report.path("hotspots").isEmpty());
            assertEquals(Long.toString(child.pid()), report.path("resource").path("attributes").path("process.pid").asText());
            assertEquals("pipeline-test", new SessionResourceLoader().load(session).resource().serviceName());
            var replay = new OfflineTraceReplayLoader().load(session.sessionDirectory());
            assertEquals(3, replay.acceptedSpans()); assertEquals(3, replay.roots().size()); assertFalse(replay.hasDegradation());
            assertTrue(json.readTree(session.spansDirectory().resolve("manifest.json").toFile()).path("captureComplete").asBoolean());
            assertTrue(Files.isRegularFile(session.sessionDirectory().resolve("config.yml")));
            assertTrue(child.isAlive(), "Stopping the Agent must not terminate its target");
        } finally {
            collector.stop(); output.println("EXIT");
            if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
        }
    }
    private static void awaitLine(BufferedReader input, String expected) throws Exception {
        CompletableFuture.runAsync(() -> {
            try { for (String line; (line = input.readLine()) != null;) if (line.equals(expected)) return;
                throw new AssertionError("Child exited before " + expected);
            } catch (IOException error) { throw new RuntimeException(error); }
        }).get(10, TimeUnit.SECONDS);
    }
}
