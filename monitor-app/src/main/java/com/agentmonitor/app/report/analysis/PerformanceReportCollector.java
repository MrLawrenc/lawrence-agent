package com.agentmonitor.app.report.analysis;

import com.agentmonitor.app.config.MonitoringConfigYaml;
import com.agentmonitor.app.model.CallNode;
import com.agentmonitor.app.model.MonitoringSession;
import com.agentmonitor.app.report.SessionAnalysisPrompt;
import com.agentmonitor.app.report.model.*;
import com.agentmonitor.app.util.AtomicFiles;
import com.agentmonitor.app.util.AppLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded analysis of observed complete synchronous trees; never estimates population percentiles. */
public final class PerformanceReportCollector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_METHODS = 8192, MAX_SAMPLES = 1000, MAX_DEPTH = 256;
    private final AnalysisConfig config;
    private final MonitoringSession session;
    private final Map<String, Hotspot> methods = new LinkedHashMap<>();
    private final Map<String, List<TraceSample>> samples = new LinkedHashMap<>();
    private final Set<String> samplingReasons = new TreeSet<>();
    private long roots, observedSpans, errorSpans, omittedMethods, depthLimited, inconsistentDurations, truncatedSpans;
    private int retainedSamples;
    private boolean sampleLimitReached, started, finished;
    private ScheduledExecutorService snapshots;
    public PerformanceReportCollector(AnalysisConfig config, MonitoringSession session) {
        this.config = Objects.requireNonNull(config); this.session = session;
    }
    public synchronized void start() {
        if (started) return;
        started = true;
        if (session == null) return;
        snapshots = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "agent-monitor-report-snapshot"); thread.setDaemon(true); return thread;
        });
        snapshots.scheduleWithFixedDelay(this::writeSnapshot, 2, 2, TimeUnit.SECONDS);
    }
    public synchronized void accept(String traceId, CallNode root) {
        if (finished || root == null) return;
        roots++;
        if (!config.enabled()) return;
        TraceAnalysis analysis = new TraceAnalysis();
        inspect(root, List.of(), 0, analysis);
        if (root.getDurationNanos() < nanos(config.slowTraceThresholdMs()) && !(config.includeErrorTrace() && analysis.error)) return;
        String key = methodKey(root);
        List<TraceSample> bucket = samples.get(key);
        if (bucket == null) {
            if (retainedSamples >= MAX_SAMPLES) { sampleLimitReached = true; return; }
            bucket = new ArrayList<>(); samples.put(key, bucket);
        }
        List<Evidence> evidence = analysis.top.stream().sorted(Comparator.comparingLong(Evidence::selfNanos).reversed()).toList();
        if (evidence.isEmpty() && analysis.best != null) evidence = List.of(analysis.best);
        TraceSample sample = new TraceSample(traceId, key, root.getDurationNanos(), analysis.error, evidence);
        if (bucket.size() < Math.min(config.maxTracesPerRootMethod(), MAX_SAMPLES) && retainedSamples < MAX_SAMPLES) {
            bucket.add(sample); retainedSamples++;
        } else {
            sampleLimitReached = true;
            if (!bucket.isEmpty()) {
                TraceSample minimum = Collections.min(bucket, Comparator.comparingLong(TraceSample::durationNanos));
                if (sample.durationNanos > minimum.durationNanos) { bucket.remove(minimum); bucket.add(sample); }
            }
        }
    }
    private void inspect(CallNode node, List<String> ancestors, int depth, TraceAnalysis analysis) {
        if (depth >= Math.min(config.maxDepth(), MAX_DEPTH)) { depthLimited++; return; }
        observedSpans++; if (node.isError()) errorSpans++;
        analysis.error |= node.isError();
        String key = methodKey(node);
        List<String> path = new ArrayList<>(ancestors); path.add(key);
        long children = 0;
        for (CallNode child : node.getChildren()) children = add(children, child.getDurationNanos());
        if (children > node.getDurationNanos()) inconsistentDurations++;
        long self = node.getDurationNanos() - Math.min(children, node.getDurationNanos());
        if (node.getAttributes().containsKey("agent.capture.truncated") || node.getAttributes().containsKey("agent.payload.truncated")) truncatedSpans++;
        String reason = node.getAttributes().get("agent.sampling.reason"); if (reason != null) samplingReasons.add(reason);
        Hotspot hotspot = methods.get(key);
        if (hotspot == null && methods.size() < MAX_METHODS) { hotspot = new Hotspot(); methods.put(key, hotspot); }
        if (hotspot == null) omittedMethods++; else hotspot.add(node.getDurationNanos(), self, node.isError());
        boolean candidate = self >= nanos(config.slowSelfTimeThresholdMs()) || node.getDurationNanos() >= nanos(config.slowSpanThresholdMs()) || node.isError();
        if (candidate || analysis.best == null || self > analysis.best.selfNanos) {
            Map<String, String> attributes = new LinkedHashMap<>();
            for (String name : List.of("sqlFingerprint", "db.query.summary", "db.operation.batch.size", "http.route", "http.response.status_code", "error.type",
                    "agent.capture.truncated", "agent.payload.truncated", "agent.db.batch.parameters_truncated", "agent.sampling.reason", "agent.sampling.head_rate_percent")) {
                String value = node.getAttributes().get(name); if (value != null) attributes.put(name, value.substring(0, Math.min(value.length(), 512)));
            }
            Evidence evidence = new Evidence(key, node.getKind().name(), node.getDurationNanos(), self, node.isError(), List.copyOf(path), attributes);
            if (analysis.best == null || self > analysis.best.selfNanos) analysis.best = evidence;
            if (candidate) {
                analysis.top.add(evidence);
                if (analysis.top.size() > Math.min(config.maxBottlenecksPerTrace(), 20)) analysis.top.poll();
            }
        }
        for (CallNode child : node.getChildren()) inspect(child, path, depth + 1, analysis);
    }
    public synchronized void finish(ReportStatus requested, String message, CaptureQuality quality) {
        if (finished) return;
        finished = true;
        if (snapshots != null) snapshots.shutdownNow();
        if (session == null) return;
        CaptureQuality effective = quality == null ? CaptureQuality.unavailable() : quality;
        ReportStatus status = requested == ReportStatus.COMPLETED && effective.hasDegradation() ? ReportStatus.INCOMPLETE : requested;
        try {
            AtomicFiles.write(reportPath(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document(status, message, effective)));
            AtomicFiles.write(session.reportsDirectory().resolve("slow-spans.json"), JSON.writeValueAsString(snapshot(status)));
            AtomicFiles.write(session.sessionDirectory().resolve("ai-analysis-prompt.txt"), SessionAnalysisPrompt.forSession(session));
        } catch (Exception error) {
            AppLog.error("最终报告写入失败: " + session.performanceReportPath(), error);
            throw new IllegalStateException("最终报告写入失败: " + error.getMessage(), error);
        }
    }
    private Map<String, Object> document(ReportStatus status, String message, CaptureQuality quality) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1); report.put("sessionId", session.sessionId());
        report.put("startedAt", session.startedAt().toString()); report.put("finishedAt", Instant.now().toString());
        report.put("status", status.name()); report.put("completionMessage", message == null ? "" : message);
        boolean complete = status == ReportStatus.COMPLETED && !quality.hasDegradation();
        report.put("captureComplete", complete); report.put("analysisReady", complete && config.enabled() && roots > 0);
        report.put("analysisEnabled", config.enabled()); report.put("captureQuality", quality);
        report.put("statisticsScope", "observed_completed_synchronous_traces_only");
        report.put("observedRootTraces", roots); report.put("analyzedSpans", observedSpans); report.put("observedErrorSpans", errorSpans);
        report.put("units", Map.of("duration", "nanoseconds")); report.put("analysisConfig", config);
        report.put("samplingReasonsObserved", samplingReasons);
        report.put("analysisLimits", Map.of("omittedMethodOccurrences", omittedMethods, "depthLimitedSubtrees", depthLimited,
                "sampleLimitReached", sampleLimitReached, "inconsistentChildDurations", inconsistentDurations,
                "truncatedSpans", truncatedSpans, "methodCapacity", MAX_METHODS, "sampleCapacity", MAX_SAMPLES));
        report.put("limitations", List.of("Head selection plus slow/error/overflow promotion is biased. Do not infer population mean, P95, error rate or throughput.",
                "Self time = inclusive time minus direct child durations, clamped at zero; inconsistent sums are counted.",
                "Paths are synchronous evidence, not an asynchronous or distributed critical path.",
                "Observer overhead is partly excluded; scheduling, GC and remaining instrumentation effects still apply.",
                "Truncated values and bounded analysis are partial evidence. Verify an optimization against source and controlled reproduction."));
        report.put("hotspots", methods.entrySet().stream().sorted(Comparator.<Map.Entry<String, Hotspot>>comparingLong(e -> e.getValue().self).reversed())
                .map(e -> e.getValue().document(e.getKey())).toList());
        report.put("slowTraces", traces());
        report.put("synchronousHotPaths", traces().stream().filter(t -> !t.bottlenecks.isEmpty())
                .map(t -> Map.of("traceId", t.traceId, "path", t.bottlenecks.get(0).path, "selfNanos", t.bottlenecks.get(0).selfNanos)).toList());
        try {
            if (Files.isRegularFile(session.resourcePath())) report.put("resource", JSON.readTree(session.resourcePath().toFile()));
            Path configuration = session.sessionDirectory().resolve("config.yml");
            if (Files.isRegularFile(configuration)) report.put("monitoringConfig", MonitoringConfigYaml.parse(Files.readString(configuration)));
        } catch (Exception error) { report.put("metadataReadError", error.getMessage()); }
        return report;
    }
    private List<TraceSample> traces() { return samples.values().stream().flatMap(List::stream).sorted(Comparator.comparingLong(TraceSample::durationNanos).reversed()).toList(); }
    private Map<String, Object> snapshot(ReportStatus status) { return Map.of("schemaVersion", 1, "status", status.name(), "observedRootTraces", roots, "slowTraces", traces()); }
    private synchronized void writeSnapshot() {
        if (finished || session == null) return;
        try { AtomicFiles.write(session.reportsDirectory().resolve("slow-spans.json"), JSON.writeValueAsString(snapshot(ReportStatus.RUNNING))); }
        catch (Exception error) { AppLog.warn("慢调用快照写入失败: " + error.getMessage()); }
    }
    public Path reportPath() { return session == null ? null : session.performanceReportPath(); }
    private static String methodKey(CallNode node) { return node.getFullClassName() + "#" + node.getMethodName2() + node.getSignature() + " [" + node.getKind() + "]"; }
    private static long nanos(long ms) { return ms > Long.MAX_VALUE / 1_000_000 ? Long.MAX_VALUE : ms * 1_000_000; }
    private static long add(long a, long b) { return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b; }
    private static final class TraceAnalysis {
        private boolean error; private Evidence best;
        private final PriorityQueue<Evidence> top = new PriorityQueue<>(Comparator.comparingLong(Evidence::selfNanos));
    }
    public record Evidence(String method, String kind, long durationNanos, long selfNanos, boolean error, List<String> path, Map<String, String> attributes) { }
    public record TraceSample(String traceId, String rootMethod, long durationNanos, boolean error, List<Evidence> bottlenecks) { }
    private static final class Hotspot {
        private long calls, errors, total, self, max, min = Long.MAX_VALUE;
        private void add(long duration, long own, boolean error) {
            calls++; if (error) errors++; total = PerformanceReportCollector.add(total, duration); self = PerformanceReportCollector.add(self, own);
            max = Math.max(max, duration); min = Math.min(min, duration);
        }
        private Map<String, Object> document(String key) { return Map.of("method", key, "observedCalls", calls, "observedErrors", errors,
                "inclusiveNanos", total, "selfNanos", self, "minNanos", min == Long.MAX_VALUE ? 0 : min, "maxNanos", max); }
    }
}
