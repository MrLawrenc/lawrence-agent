package com.agentmonitor.cli;

import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceCommandsTest {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper();
    private record Result(int code,JsonNode value){}
    private Result run(String... args)throws Exception{StringWriter output=new StringWriter();int code=new MonitorCli().run(args,new PrintWriter(output),new PrintWriter(new StringWriter()));return new Result(code,json.readTree(output.toString()));}
    @Test void requestsAndOfflineDiagnosisAreReadOnlyAndValidateOptions()throws Exception{
        var session=MonitoringSession.create(directory.toString());Path metric=session.agentLogsDirectory().resolve("request-metrics.json");
        Files.writeString(metric,json.writeValueAsString(Map.of("schemaVersion",1,"sessionId",session.sessionId(),"captureComplete",true,"endpoints",List.of())));
        byte[] original=Files.readAllBytes(metric);assertEquals(0,run("requests","--session",session.sessionDirectory().toString()).code);
        assertEquals(0,run("diagnose","--session",session.sessionDirectory().toString(),"--symptom","latency").code);assertArrayEquals(original,Files.readAllBytes(metric));
        assertEquals(2,run("diagnose","--session",session.sessionDirectory().toString(),"--symptom","other").code);
        assertEquals(2,run("diagnose","--session",session.sessionDirectory().toString(),"--pid","1","--symptom","cpu").code);
        assertEquals(2,run("distributed","--sessions",session.sessionDirectory().toString(),"--trace","invalid").code);
    }
    @Test void missingIdentityAndDuplicateJsonCannotBecomeSuccessfulRequestStatistics()throws Exception{
        var session=MonitoringSession.create(directory.toString());Files.writeString(session.sessionDirectory().resolve("session.json"),"{}");Files.writeString(session.agentLogsDirectory().resolve("request-metrics.json"),"{\"captureComplete\":true}");
        assertEquals(2,run("requests","--session",session.sessionDirectory().toString()).code);
    }

    @Test void liveDiagnosisUsesBothSharedCapturePathsAndReturnsOneResult() throws Exception {
        Process target = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("build/classes/java/test").toAbsolutePath().toString(),
                "agentfixtures.clipipeline.TargetLoop").start();
        try {
            assertEquals("READY", new BufferedReader(new InputStreamReader(target.getInputStream())).readLine());
            var diagnostics = run("diagnose", "--symptom", "cpu", "--pid", Long.toString(target.pid()),
                    "--duration", "500ms", "--output", directory.resolve("diagnostic-captures").toString());
            assertTrue(diagnostics.code == MonitorCli.OK || diagnostics.code == MonitorCli.INCOMPLETE);
            assertEquals("cpu", diagnostics.value.path("plan").path("symptom").asText());
            assertTrue(Files.isDirectory(Path.of(diagnostics.value.path("sessionDirectory").asText())));

            var defaults = MonitoringConfig.defaults();
            var config = defaults.withScope(new MonitoringConfig.Scope(List.of(),
                    List.of("agentfixtures.clipipeline.TargetLoop"), List.of(), List.of("main")));
            Path yaml = directory.resolve("monitor.yml");
            Files.writeString(yaml, MonitoringConfigYaml.write(config));
            Path rejectedRoot = directory.resolve("rejected-captures");
            assertEquals(MonitorCli.USAGE, run("capture", "--pid", Long.toString(target.pid()),
                    "--config", yaml.toString(), "--rolling", "--window", "70m",
                    "--output", rejectedRoot.toString()).code);
            assertEquals(MonitorCli.USAGE, run("capture", "--pid", Long.toString(target.pid()),
                    "--config", yaml.toString(), "--rolling", "--max-bytes", "1",
                    "--output", rejectedRoot.toString()).code);
            assertEquals(MonitorCli.USAGE, run("capture", "--pid", Long.toString(target.pid()),
                    "--config", yaml.toString(), "--expected-start", "invalid",
                    "--output", rejectedRoot.toString()).code);
            assertEquals(MonitorCli.USAGE, run("capture", "--pid", Long.toString(target.pid()),
                    "--config", yaml.toString(), "--expected-start", "2000-01-01T00:00:00Z",
                    "--output", rejectedRoot.toString()).code);
            assertFalse(Files.exists(rejectedRoot), "Invalid capture options must not create a session");
            var traces = run("diagnose", "--symptom", "cpu", "--pid", Long.toString(target.pid()),
                    "--config", yaml.toString(), "--duration", "500ms",
                    "--output", directory.resolve("trace-captures").toString());
            assertTrue(traces.code == MonitorCli.OK || traces.code == MonitorCli.INCOMPLETE,
                    () -> traces.value.toString());
            assertEquals("cpu", traces.value.path("plan").path("symptom").asText());
            assertEquals(traces.code, traces.value.path("captureExitCode").asInt());
            assertTrue(Files.isDirectory(Path.of(traces.value.path("sessionDirectory").asText())));
        } finally {
            target.getOutputStream().write("EXIT\n".getBytes());
            target.getOutputStream().flush();
            if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly();
        }
    }
}
