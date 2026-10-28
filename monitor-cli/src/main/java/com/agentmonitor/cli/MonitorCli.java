package com.agentmonitor.cli;

import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.core.service.*;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import java.io.PrintWriter;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Foreground CLI: stdout is one JSON result, stderr contains human-readable progress. */
public final class MonitorCli {
    public static final int OK = 0, USAGE = 2, FAILED = 3, RESTORE_UNCONFIRMED = 4, INCOMPLETE = 5, INVALID_ANALYSIS = 6;
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String HELP = """
            Usage:
              monitor jvm list [--json]
              monitor jvm identity --pid <pid> [--json]
              monitor capture --pid <pid> --config <file.yml> [--duration 60s] [--output <directory>] [--rolling --window 5m --max-bytes 2147483648] [--json]
              monitor analyze --session <directory> [--json]
              monitor config init [--output monitor.yml] [--package <a.b,c.d>] [--class <a.b.Service>] [--json]
              monitor config validate --config <file.yml> [--json]
              monitor scope preview --pid <pid> --config <file.yml> [--package <prefix>] [--search <text>] [--selected-only] [--refresh] [--offset 0] [--limit 150] [--json]
              monitor session list [--root <captures-directory>] [--limit 20] [--json]
              monitor session show --session <directory> [--json]
              monitor ai verify --session <directory> --response <plain-json-file> [--schema <json-file>] [--json]
            capture runs in the foreground and confirms restoration before returning.
            analyze is read-only. JSON goes to stdout; progress goes to stderr.
            Exit codes: 0=success, 2=usage/config, 3=execution failed, 4=restore unconfirmed, 5=incomplete capture/diagnostics, 6=AI response validation failed.
            """;
    public static void main(String[] args) {
        int code = new MonitorCli().run(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        if (code != 0) System.exit(code);
    }
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help") || Arrays.asList(args).contains("--help")) { out.print(HELP+PerformanceCommands.help()+EvidenceCommands.help()+com.agentmonitor.core.diagnostics.DiagnosticCommands.help()); out.flush(); return OK; }
            Integer performance=new PerformanceCommands().run(args,out,err);if(performance!=null)return performance;
            if(com.agentmonitor.core.diagnostics.DiagnosticCommands.supports(args[0])) { Object result=com.agentmonitor.core.diagnostics.DiagnosticCommands.execute(args);emit(out,result);return com.agentmonitor.core.diagnostics.DiagnosticCommands.exitCode(result); }
            Integer evidence=new EvidenceCommands().run(args,out);if(evidence!=null)return evidence;
            Integer workflow=new WorkflowCommands().run(args,out,err);if(workflow!=null)return workflow;
            if (args[0].equals("jvm") && args.length >= 2 && args[1].equals("list")) {
                options(args, 2, Set.of()); emit(out, Map.of("jvms", JvmService.listProcesses())); return OK;
            }
            if(args[0].equals("jvm")&&args.length>=2&&args[1].equals("identity")){var opts=options(args,2,Set.of("pid"));long pid=Long.parseLong(required(opts,"pid"));var process=ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElseThrow(()->new IllegalArgumentException("Target is not alive"));var started=process.info().startInstant().orElseThrow(()->new IllegalArgumentException("Target start identity unavailable"));emit(out,Map.of("pid",pid,"startedAt",started.toString(),"startedAtMillis",started.toEpochMilli()));return OK;}
            if (args[0].equals("capture")) {
                CaptureResult result = capture(options(args, 1, Set.of("pid", "config", "duration", "output", "window", "max-bytes", "expected-start"),Set.of("rolling")), err);
                emit(out, result.body());
                return result.exitCode();
            }
            if (args[0].equals("analyze")) {
                var opts = options(args, 1, Set.of("session"));
                var result = new SessionAnalyzer().analyze(Path.of(required(opts, "session")));
                emit(out, result);
                return JSON.valueToTree(result).path("report").path("captureComplete").asBoolean(false) ? OK : INCOMPLETE;
            }
            throw new IllegalArgumentException("Unknown command. Use monitor --help");
        } catch (IllegalArgumentException error) { return failure(out, err, USAGE, error); }
        catch (Exception error) { return failure(out, err, FAILED, error); }
    }
    record CaptureResult(int exitCode, Map<String, Object> body) {}

    CaptureResult capture(Map<String, String> options, PrintWriter err) throws Exception {
        String pid = required(options, "pid");
        long numericPid = Long.parseLong(pid);
        if (numericPid <= 0) throw new IllegalArgumentException("PID must be positive");
        boolean rolling=options.containsKey("rolling");
        if(!rolling&&(options.containsKey("window")||options.containsKey("max-bytes")))throw new IllegalArgumentException("--window/--max-bytes require --rolling");
        long durationMillis = rolling?longDuration(options.getOrDefault("duration","60s")):duration(options.getOrDefault("duration", "60s"));
        long windowMillis = rolling ? duration(options.getOrDefault("window", "5m")) : 0;
        long maxBytes = rolling ? Long.parseLong(options.getOrDefault("max-bytes", "2147483648")) : 0;
        if (rolling && maxBytes < 1_048_576) throw new IllegalArgumentException("Rolling budget must be >=1MiB");
        String expectedStart = options.get("expected-start");
        if (expectedStart != null) {
            Instant expected;
            try { expected = Instant.parse(expectedStart); }
            catch (java.time.format.DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid --expected-start instant", invalid); }
            ProcessHandle.of(numericPid).flatMap(process -> process.info().startInstant()).ifPresent(actual -> {
                if (!actual.equals(expected)) throw new IllegalArgumentException("Target PID was reused or start identity differs");
            });
        }
        Path configuration = Path.of(required(options, "config"));
        if (!Files.isRegularFile(configuration)) throw new IllegalArgumentException("Configuration does not exist: " + configuration);
        if (Files.size(configuration) > 1_048_576) throw new IllegalArgumentException("Configuration exceeds 1MiB");
        MonitoringConfig config = MonitoringConfigYaml.parse(Files.readString(configuration));
        if (options.containsKey("output")) {
            var o = config.output();
            config = new MonitoringConfig(config.schemaVersion(), config.scope(), config.sampling(),
                    new MonitoringConfig.Output(options.get("output"), o.exporters(), o.capture(), o.spanFiles(), o.retention(), o.otlpForwardEndpoint()),
                    config.resource(), config.dependencies(), config.analysis(),config.diagnostics(),config.overhead(),config.experiment());
        }
        try (var runtime = new MonitoringRuntime(pid, config)) {
            if(expectedStart != null)runtime.requireExpectedStart(expectedStart);
            if(rolling)runtime.enableRolling(windowMillis,maxBytes);
            Thread hook = new Thread(runtime::stopAndAwaitRestore, "monitor-cli-restore");
            Runtime.getRuntime().addShutdownHook(hook);
            runtime.setOnStatusChange(message -> { synchronized (err) { err.println(message); err.flush(); } });
            err.println("SESSION " + runtime.session().sessionDirectory()); err.flush();
            try {
                runtime.start(); err.println("READY pid=" + pid); err.flush();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
                while (System.nanoTime() < deadline && runtime.targetAlive() && !runtime.captureBudgetReached()) Thread.sleep(Math.min(100, durationMillis));
                var stop = runtime.stopAndAwaitRestore();
                JsonNode report = JSON.readTree(runtime.getPerformanceReportPath().toFile());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sessionDirectory", runtime.session().sessionDirectory().toString());
                result.put("reportPath", runtime.getPerformanceReportPath().toString());
                result.put("targetPid", numericPid); result.put("targetStillAlive", runtime.targetAlive());
                result.put("restored", stop.restored()); result.put("stopStatus", stop.status().name()); result.put("message", stop.message());
                result.put("captureQuality", stop.captureQuality()); result.put("receivedSpans", runtime.getEventCount());
                result.put("retention", runtime.cleanupResult());
                result.put("captureComplete", report.path("captureComplete").asBoolean(false));
                result.put("analysisReady", report.path("analysisReady").asBoolean(false));
                result.put("diagnostics", report.path("diagnosticSummary"));
                if(rolling){result.put("rollingManifest",runtime.rollingManifestPath().toString());result.put("captureBudgetReached",runtime.captureBudgetReached());result.put("rollingArchiveError",runtime.rollingFailure());result.put("rollingArchiveComplete",runtime.rollingFailure().isBlank());}
                if (!stop.restored()) return new CaptureResult(RESTORE_UNCONFIRMED, result);
                return new CaptureResult(report.path("captureComplete").asBoolean(false) && !runtime.captureBudgetReached() && runtime.rollingFailure().isBlank() ? OK : INCOMPLETE, result);
            } catch (Exception error) {
                var stop = runtime.stopAndAwaitRestore();
                int code = !stop.restored() && runtime.wasAttachAttempted() ? RESTORE_UNCONFIRMED : FAILED;
                Map<String, Object> failure = Map.of("error", message(error), "exitCode", code, "restored", stop.restored(),
                        "stopStatus", stop.status().name(), "sessionDirectory", runtime.session().sessionDirectory().toString());
                err.println(message(error)); err.flush(); return new CaptureResult(code, failure);
            } finally { Runtime.getRuntime().removeShutdownHook(hook); }
        }
    }
    static Map<String,String> options(String[] args,int start,Set<String> allowed){return options(args,start,allowed,Set.of());}
    static Map<String, String> options(String[] args, int start, Set<String> allowed,Set<String> flags) {
        Map<String, String> options = new LinkedHashMap<>(); boolean json = false;
        for (int i = start; i < args.length; i++) {
            String token = args[i];
            if (token.equals("--json")) { if (json) throw new IllegalArgumentException("Duplicate --json"); json = true; continue; }
            if(token.startsWith("--") && flags.contains(token.substring(2))) {
                if(options.putIfAbsent(token.substring(2),"true")!=null)throw new IllegalArgumentException("Duplicate option: "+token);continue;
            }
            if (!token.startsWith("--") || !allowed.contains(token.substring(2))) throw new IllegalArgumentException("Unknown option: " + token);
            String key = token.substring(2);
            if (options.containsKey(key) || ++i >= args.length || args[i].startsWith("--") || args[i].isBlank())
                throw new IllegalArgumentException("Missing or duplicate option: " + token);
            options.put(key, args[i]);
        }
        return options;
    }
    static String required(Map<String, String> options, String key) {
        String value = options.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key); return value;
    }
    static long longDuration(String text){
        if(!text.matches("[0-9]+(ms|s|m|h|d)"))throw new IllegalArgumentException("Rolling duration: ms/s/m/h/d");
        int suffix=text.endsWith("ms")?2:1;long factor=suffix==2?1:text.endsWith("s")?1000:text.endsWith("m")?60000:text.endsWith("h")?3600000:86400000;
        long value;try{value=Math.multiplyExact(Long.parseLong(text.substring(0,text.length()-suffix)),factor);}catch(ArithmeticException e){throw new IllegalArgumentException("Duration too large");}
        if(value<500||value>7L*86400000)throw new IllegalArgumentException("Rolling duration: 500ms–7d");return value;
    }
    static long duration(String text) {
        if (!text.matches("[0-9]+(ms|s|m)")) throw new IllegalArgumentException("Duration must end in ms, s, or m");
        int suffix = text.endsWith("ms") ? 2 : 1;
        long number = Long.parseLong(text.substring(0, text.length() - suffix));
        long millis;
        try { millis = Math.multiplyExact(number, suffix == 2 ? 1 : text.endsWith("s") ? 1000 : 60_000); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("Duration is too large"); }
        if (millis < 500 || millis > 3_600_000) throw new IllegalArgumentException("Duration must be 500ms–60m");
        return millis;
    }
    private static int failure(PrintWriter out, PrintWriter err, int code, Exception error) {
        err.println(message(error)); err.flush();
        try { emit(out, Map.of("error", message(error), "exitCode", code)); } catch (Exception ignored) { }
        return code;
    }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    static void emit(PrintWriter out, Object value) throws Exception { out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); out.flush(); }
}
