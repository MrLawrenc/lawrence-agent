package com.agentmonitor.cli;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitorCliTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    @Test void runtimeHasNoJavaFxAndInvalidArgumentsReturnExactlyOneJsonDocument() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("javafx.application.Platform"));
        for (String[] args : List.of(new String[]{"wat"}, new String[]{"jvm", "list", "--pid", "1"},
                new String[]{"analyze", "--session"}, new String[]{"analyze", "--json", "--json"},
                new String[]{"capture", "--pid", "x", "--config", "x"},
                new String[]{"capture", "--pid", "1", "--duration", "999999999999999999m", "--config", "x"})) {
            var stdout = new StringWriter(); var stderr = new StringWriter();
            assertEquals(MonitorCli.USAGE, new MonitorCli().run(args, new PrintWriter(stdout), new PrintWriter(stderr)));
            try (var parser = JSON.getFactory().createParser(stdout.toString())) {
                assertNotNull(JSON.readTree(parser)); assertNull(parser.nextToken());
            }
            assertFalse(stderr.toString().isBlank());
        }
        assertEquals(500, MonitorCli.duration("500ms")); assertEquals(60_000, MonitorCli.duration("1m"));
        assertThrows(IllegalArgumentException.class, () -> MonitorCli.duration("0s"));
    }
    @Test void packagedClasspathCapturesARealJvmReanalyzesReadOnlyAndRestoresOnSigterm() throws Exception {
        Process target = new ProcessBuilder(java(), "-cp", Path.of("build/classes/java/test").toAbsolutePath().toString(),
                "agentfixtures.clipipeline.TargetLoop").redirectErrorStream(true).start();
        var targetIn = new BufferedReader(new InputStreamReader(target.getInputStream()));
        var targetOut = new PrintWriter(target.getOutputStream(), true);
        try {
            assertEquals("READY", targetIn.readLine());
            var d = MonitoringConfig.defaults();
            var config = new MonitoringConfig(2,
                    new MonitoringConfig.Scope(List.of(), List.of("agentfixtures.clipipeline.TargetLoop"), List.of(), List.of("main")),
                    new MonitoringConfig.Sampling(100, 50, 512, 1, MonitoringConfig.TailOverflowPolicy.PROMOTE),
                    new MonitoringConfig.Output(directory.resolve("captures").toString(), d.output().exporters(),
                            new MonitoringConfig.Capture(false, false, false), d.output().spanFiles(), d.output().retention()),
                    d.resource(), new MonitoringConfig.Dependencies(false, false), d.analysis());
            Path yaml = directory.resolve("monitor.yml"); Files.writeString(yaml, MonitoringConfigYaml.write(config));
            Path stdout = directory.resolve("capture.json"), stderr = directory.resolve("capture.log");
            Process cli = capture(target.pid(), yaml, "1500ms", stdout, stderr);
            try {
                waitForReady(cli, stderr); targetOut.println("RUN"); awaitLine(targetIn, "WORK_DONE");
                assertTrue(cli.waitFor(20, TimeUnit.SECONDS), () -> "CLI completion timed out: " + read(stderr)); assertEquals(0, cli.exitValue(), () -> read(stderr));
            } finally { if (cli.isAlive()) cli.destroyForcibly(); }
            JsonNode result = JSON.readTree(stdout.toFile());
            assertTrue(result.path("restored").asBoolean()); assertTrue(result.path("captureComplete").asBoolean());
            assertEquals(3, result.path("receivedSpans").asInt()); assertTrue(target.isAlive());
            Path session = Path.of(result.path("sessionDirectory").asText());
            byte[] before = Files.readAllBytes(session.resolve("reports/performance-report.json"));
            assertEquals(JSON.readTree(before).path("diagnosticSummary"), result.path("diagnostics"));
            var analyzedOut = new StringWriter(); var analyzedErr = new StringWriter();
            assertEquals(0, new MonitorCli().run(new String[]{"analyze", "--session", session.toString(), "--json"},
                    new PrintWriter(analyzedOut), new PrintWriter(analyzedErr)), analyzedErr::toString);
            JsonNode analysis = JSON.readTree(analyzedOut.toString());
            assertTrue(analysis.path("readOnly").asBoolean()); assertEquals(3, analysis.path("report").path("observedRootTraces").asInt());
            assertArrayEquals(before, Files.readAllBytes(session.resolve("reports/performance-report.json")));
            Path stoppedLog = directory.resolve("interrupted.log");
            Process interrupted = capture(target.pid(), yaml, "60s", directory.resolve("interrupted.json"), stoppedLog);
            try {
                waitForReady(interrupted, stoppedLog); targetOut.println("RUN"); awaitLine(targetIn, "WORK_DONE");
                interrupted.destroy(); assertTrue(interrupted.waitFor(20, TimeUnit.SECONDS));
                String sessionLine = Files.readAllLines(stoppedLog).stream().filter(l -> l.startsWith("SESSION ")).findFirst().orElseThrow();
                Path stoppedSession = Path.of(sessionLine.substring(8));
                JsonNode stoppedReport = JSON.readTree(stoppedSession.resolve("reports/performance-report.json").toFile());
                assertEquals("COMPLETED", stoppedReport.path("status").asText()); assertTrue(stoppedReport.path("captureComplete").asBoolean());
                assertTrue(target.isAlive());
            } finally { if (interrupted.isAlive()) interrupted.destroyForcibly(); }
        } finally { targetOut.println("EXIT"); if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly(); }
    }
    private Process capture(long pid, Path yaml, String duration, Path stdout, Path stderr) throws IOException {
        return new ProcessBuilder(java(), "--add-modules", "jdk.attach", "-Dagent.monitor.leaseDir=" + directory.resolve("leases"),
                "-cp", System.getProperty("agent.monitor.cli.testClasspath"), MonitorCli.class.getName(),
                "capture", "--pid", Long.toString(pid), "--config", yaml.toString(), "--duration", duration, "--json")
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
    }
    private static String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static void awaitLine(BufferedReader input, String expected) throws Exception {
        CompletableFuture.runAsync(() -> {
            try { for (String line; (line = input.readLine()) != null;) if (line.equals(expected)) return;
                throw new AssertionError("Target exited before " + expected);
            } catch (IOException error) { throw new RuntimeException(error); }
        }).get(10, TimeUnit.SECONDS);
    }
    private static String read(Path file) { try { return Files.readString(file); } catch (IOException error) { return error.toString(); } }
    private static void waitForReady(Process cli, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (cli.isAlive() && System.nanoTime() < deadline) {
            if (read(log).contains("READY pid=")) return;
            Thread.sleep(20);
        }
        fail("CLI did not become ready: " + read(log));
    }
}
