package com.agentmonitor.app.model;
import com.agentmonitor.app.util.AtomicFiles;
import com.agentmonitor.app.util.UserPath;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
public record MonitoringSession(String sessionId, Path sessionRootDirectory, Path sessionDirectory, Instant startedAt) {
    public static MonitoringSession create(String root) {
        Path parent = UserPath.resolve(root, Path.of(System.getProperty("user.home"), "Downloads", "agent-monitor-captures"));
        Instant now = Instant.now();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault()).format(now);
        String id = UUID.randomUUID().toString();
        MonitoringSession session = new MonitoringSession(id, parent, parent.resolve(stamp).resolve("session-" + id), now);
        try {
            Files.createDirectories(session.spansDirectory()); Files.createDirectories(session.reportsDirectory()); Files.createDirectories(session.agentLogsDirectory());
            AtomicFiles.write(session.sessionDirectory().resolve("session.json"), new ObjectMapper().writeValueAsString(Map.of(
                    "application", "agent-monitor", "schemaVersion", 1, "sessionId", id, "startedAt", now.toString())));
            AtomicFiles.write(session.sessionDirectory().resolve("README.md"), "# Agent Monitor session\n\nSession: " + id + "\nStarted: " + now + "\n\n"
                    + "Read reports/performance-report.json first. A missing report means the session has not finalized.\n"
                    + "Check status, captureQuality, and spans/manifest.json before making performance claims.\n"
                    + "Partial, sampled, or truncated data must not be treated as complete evidence.\n");
            AtomicFiles.write(session.spansDirectory().resolve("README.md"), "# Raw spans\n\nRead manifest.json and captureComplete before spans-*.json or .json.gz.\n"
                    + "completed.marker means file closure, not complete collection.\n");
            AtomicFiles.write(session.reportsDirectory().resolve("README.md"), "# Analysis reports\n\nperformance-report.json is the final sampled-evidence report; slow-spans.json is a bounded live snapshot.\n"
                    + "Durations are nanoseconds. Self time excludes direct child durations and is clamped at zero.\n"
                    + "A synchronous method path is not an asynchronous end-to-end critical path.\n");
            AtomicFiles.write(session.agentLogsDirectory().resolve("README.md"), "# Agent diagnostics\n\nagent-monitor.log records installation and restore results; enhancement-result.json lists transformed classes.\n");
            return session;
        } catch (java.io.IOException error) { throw new IllegalStateException("无法创建采集会话: " + parent, error); }
    }
    public Path spansDirectory() { return sessionDirectory.resolve("spans"); }
    public Path reportsDirectory() { return sessionDirectory.resolve("reports"); }
    public Path agentLogsDirectory() { return sessionDirectory.resolve("agent-logs"); }
    public Path performanceReportPath() { return reportsDirectory().resolve("performance-report.json"); }
    public Path resourcePath() { return sessionDirectory.resolve("resource.json"); }
}
