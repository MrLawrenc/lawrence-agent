package com.agentmonitor.core.service;

import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.report.analysis.PerformanceReportCollector;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.output.ExporterStatistics;
import com.agentmonitor.model.report.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only reconstruction. Missing quality never upgrades old or partial data to complete. */
public final class SessionAnalyzer {
    private static final ObjectMapper JSON = new ObjectMapper();
    public Map<String, Object> analyze(Path selected) throws IOException {
        Path directory = selected.toAbsolutePath().normalize();
        if (directory.getFileName() != null && directory.getFileName().toString().equals("spans")) directory = directory.getParent();
        var replay = new OfflineTraceReplayLoader().load(directory);
        JsonNode marker = readOptional(directory.resolve("session.json"));
        Instant started;
        try { started = Instant.parse(marker.path("startedAt").asText()); } catch (Exception ignored) { started = Instant.EPOCH; }
        var session = new MonitoringSession(marker.path("sessionId").asText("offline:" + directory.getFileName()),
                directory.getParent(), directory, started);
        Path yaml = directory.resolve("config.yml");
        MonitoringConfig.Analysis a = Files.isRegularFile(yaml)
                ? MonitoringConfigYaml.parse(Files.readString(yaml)).analysis() : MonitoringConfig.Analysis.defaults();
        var collector = new PerformanceReportCollector(new AnalysisConfig(a.enabled(), a.slowTraceThresholdMs(), a.slowSpanThresholdMs(),
                a.slowSelfTimeThresholdMs(), a.includeErrorTrace(), a.maxTracesPerRootMethod(), a.maxBottlenecksPerTrace(), a.maxDepth()), session);
        for (var root : replay.roots()) collector.accept(root.getTraceId(), root);
        JsonNode manifest = readOptional(directory.resolve("spans/manifest.json"));
        JsonNode output = manifest.path("outputQuality");
        boolean reported = output.path("reported").asBoolean(false);
        for (String field : List.of("enqueuedSpans", "queueDroppedSpans", "deliveryDroppedSpans", "rejectedDestinations", "pendingSpans"))
            reported &= output.path(field).isIntegralNumber() && output.path(field).canConvertToLong() && output.path(field).asLong() >= 0;
        var stats = new ExporterStatistics(reported, output.path("enqueuedSpans").asLong(), output.path("queueDroppedSpans").asLong(),
                output.path("deliveryDroppedSpans").asLong(), output.path("rejectedDestinations").asLong(), output.path("pendingSpans").asLong());
        var quality = new CaptureQuality(stats, replay.acceptedSpans(), 0,
                replay.skippedSpans() + replay.orphanSpans() + replay.cyclicSpans());
        JsonNode previous = readOptional(session.performanceReportPath());
        boolean originalIncomplete = !previous.isMissingNode() && !previous.path("captureComplete").asBoolean(false);
        boolean complete = manifest.path("schemaVersion").asInt() == 1 && manifest.path("captureComplete").asBoolean(false)
                && manifest.path("writtenSpans").asLong(-1) == replay.acceptedSpans()
                && !replay.hasDegradation() && !quality.hasDegradation() && !originalIncomplete;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionDirectory", directory.toString()); result.put("readOnly", true);
        result.put("replay", Map.of("acceptedSpans", replay.acceptedSpans(), "rootTraces", replay.roots().size(),
                "skippedSpans", replay.skippedSpans(), "orphanSpans", replay.orphanSpans(), "cyclicSpans", replay.cyclicSpans(), "warnings", replay.warnings()));
        Map<String,Object> rendered=new LinkedHashMap<>(collector.render(complete ? ReportStatus.COMPLETED : ReportStatus.INCOMPLETE,
                complete ? "离线重建，原始采集完整性已核对" : "离线重建，采集不完整或缺少完整性证据", quality));
        if(Files.isRegularFile(directory.resolve("agent-logs/request-metrics.json")))try{rendered.put("requestMetrics",new com.agentmonitor.core.diagnostics.RequestStatistics().read(directory));}catch(Exception invalid){throw new IOException("Independent request statistics failed verification",invalid);}
        result.put("report",Collections.unmodifiableMap(rendered));
        return Collections.unmodifiableMap(result);
    }
    private static JsonNode readOptional(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return JSON.missingNode();
        if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("元数据文件过大: " + path);
        JsonNode node = JSON.readTree(path.toFile());
        if (node == null || !node.isObject()) throw new IOException("元数据必须是 JSON 对象: " + path);
        return node;
    }
}
