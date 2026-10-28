package com.agentmonitor.model.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.agentmonitor.model.output.ExporterType;

/**
 * The single, transport-independent monitoring configuration.
 *
 * <p>The App turns this object into a per-attach properties file and the
 * Agent never needs to interpret YAML.  Keeping the defaults and validation
 * here prevents the UI, YAML parser, and Agent-launcher from drifting apart.</p>
 */
public record MonitoringConfig(
        int schemaVersion,
        Scope scope,
        Sampling sampling,
        Output output,
        Resource resource,
        Dependencies dependencies,
        Analysis analysis,
        Diagnostics diagnostics,
        Overhead overhead,
        Experiment experiment) {

    public MonitoringConfig(int schemaVersion, Scope scope, Sampling sampling, Output output,
                            Resource resource, Dependencies dependencies, Analysis analysis, Diagnostics diagnostics) {
        this(schemaVersion, scope, sampling, output, resource, dependencies, analysis, diagnostics, Overhead.defaults(), Experiment.defaults());
    }

    public MonitoringConfig(int schemaVersion, Scope scope, Sampling sampling, Output output,
                            Resource resource, Dependencies dependencies, Analysis analysis) {
        this(schemaVersion, scope, sampling, output, resource, dependencies, analysis, Diagnostics.defaults());
    }

    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int DEFAULT_SAMPLING_RATE_PERCENT = 10;
    public static final long DEFAULT_TAIL_CAPTURE_THRESHOLD_MILLIS = 50;
    /** Limits tail buffering without constraining head-sampled traces. */
    public static final int DEFAULT_TAIL_MAX_BUFFERED_SPANS = 512;
    public static final int DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB = 1;
    /** Preserve a complete trace when its tail buffer fills instead of silently discarding it. */
    public static final TailOverflowPolicy DEFAULT_TAIL_OVERFLOW_POLICY = TailOverflowPolicy.PROMOTE;
    public static final String DEFAULT_SESSION_ROOT_DIRECTORY = "~/Downloads/agent-monitor-captures";
    public static final int DEFAULT_FILE_ROTATE_SIZE_MB = 100;
    public static final int DEFAULT_FILE_ROTATE_MINUTES = 10;
    public static final long DEFAULT_SLOW_TRACE_THRESHOLD_MILLIS = 3_000;
    public static final long DEFAULT_SLOW_SPAN_THRESHOLD_MILLIS = 800;
    public static final long DEFAULT_SLOW_SELF_TIME_THRESHOLD_MILLIS = 300;
    public static final int DEFAULT_MAX_TRACES_PER_ROOT_METHOD = 20;
    public static final int DEFAULT_MAX_BOTTLENECKS_PER_TRACE = 5;
    public static final int DEFAULT_MAX_DEPTH = 50;

    public MonitoringConfig {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("仅支持配置版本 " + CURRENT_SCHEMA_VERSION + "，收到: " + schemaVersion);
        }
        scope = scope == null ? Scope.defaults() : scope;
        sampling = sampling == null ? Sampling.defaults() : sampling;
        output = output == null ? Output.defaults() : output;
        resource = resource == null ? Resource.defaults() : resource;
        dependencies = dependencies == null ? Dependencies.defaults() : dependencies;
        analysis = analysis == null ? Analysis.defaults() : analysis;
        diagnostics = diagnostics == null ? Diagnostics.defaults() : diagnostics;
        overhead = overhead == null ? Overhead.defaults() : overhead;
        experiment = experiment == null ? Experiment.defaults() : experiment;
    }

    public static MonitoringConfig defaults() {
        return new MonitoringConfig(CURRENT_SCHEMA_VERSION, Scope.defaults(), Sampling.defaults(), Output.defaults(),
                Resource.defaults(), Dependencies.defaults(), Analysis.defaults());
    }

    public MonitoringConfig withScope(Scope replacement) {
        return new MonitoringConfig(schemaVersion, replacement, sampling, output, resource, dependencies, analysis, diagnostics, overhead, experiment);
    }

    /** Soft observer budget; byte admission is a hard estimated-resident-payload bound. */
    public record Overhead(boolean enabled, long queueMaxBytes, int highWatermarkPercent, int lowWatermarkPercent,
                           int minimumSamplingRatePercent, long adviceBudgetNanosPerSecond, long recoveryMillis) {
        public Overhead {
            if(queueMaxBytes<65536 || queueMaxBytes>256L*1024*1024 || lowWatermarkPercent<1 || highWatermarkPercent>95
                    || lowWatermarkPercent>=highWatermarkPercent || minimumSamplingRatePercent<0 || minimumSamplingRatePercent>100
                    || adviceBudgetNanosPerSecond<100000 || adviceBudgetNanosPerSecond>1000000000L || recoveryMillis<1000 || recoveryMillis>300000)
                throw new IllegalArgumentException("overhead budget/watermarks invalid");
        }
        public static Overhead defaults(){return new Overhead(true,16L*1024*1024,75,35,1,50_000_000L,10_000);}
    }

    /** User-declared experiment context; matching declarations do not prove actual workload equivalence. */
    public record Experiment(String workloadId,String datasetId,String loadProfile,String warmupId,String buildId) {
        public Experiment { workloadId=normalizeOptional(workloadId);datasetId=normalizeOptional(datasetId);loadProfile=normalizeOptional(loadProfile);warmupId=normalizeOptional(warmupId);buildId=normalizeOptional(buildId); }
        public static Experiment defaults(){return new Experiment("","","","","");}
    }

    /** Bounded evidence capture. Expensive heap diagnostics remain explicit actions. */
    public record Diagnostics(boolean enabled, int intervalMs, int threadIntervalMs, int maxSamples,
                              boolean jfr, boolean exceptionEvents, Trigger trigger) {
        public Diagnostics {
            if (intervalMs < 500 || intervalMs > 60_000) throw new IllegalArgumentException("diagnostics.intervalMs: 500–60000");
            if (threadIntervalMs < 1000 || threadIntervalMs > 300_000) throw new IllegalArgumentException("diagnostics.threadIntervalMs: 1000–300000");
            if (maxSamples < 2 || maxSamples > 7200) throw new IllegalArgumentException("diagnostics.maxSamples: 2–7200");
            trigger = trigger == null ? Trigger.defaults() : trigger;
        }
        public static Diagnostics defaults() { return new Diagnostics(true, 1000, 5000, 3600, false, false, Trigger.defaults()); }
    }
    public record Trigger(boolean enabled, int cpuPercent, int heapPercent, int blockedThreads,
                          int consecutiveSamples, long slowCallMs) {
        public Trigger {
            if (cpuPercent < 1 || cpuPercent > 100 || heapPercent < 1 || heapPercent > 100
                    || blockedThreads < 1 || consecutiveSamples < 1 || consecutiveSamples > 120 || slowCallMs < 1)
                throw new IllegalArgumentException("diagnostics.trigger 阈值无效");
        }
        public static Trigger defaults() { return new Trigger(false, 85, 90, 20, 3, 3000); }
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        if (scope.includePackages().isEmpty() && scope.includeClasses().isEmpty()) {
            errors.add("scope.includePackages 与 scope.includeClasses 至少配置一项");
        }
        if (!output.exporters().contains(ExporterType.NETTY) && !output.exporters().contains(ExporterType.OTLP)) {
            errors.add("output.exporters 必须包含 otlp 或 netty（实时遥测数据）");
        }
        if (scope.includePackages().stream().anyMatch(p -> p.contains("*"))) errors.add("scope.includePackages 使用包名前缀，不支持通配符；pkg: 排除规则支持通配符");
        for (String condition : scope.excludeConditions()) {
            if (!(condition.startsWith("pkg:") || condition.startsWith("cls:"))) {
                errors.add("scope.excludeConditions 必须以 pkg: 或 cls: 开头: " + condition);
            }
        }
        return List.copyOf(errors);
    }

    public void requireValid() {
        List<String> errors = validationErrors();
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("；", errors));
    }

    public String packagePrefixesArg() {
        return String.join("|", scope.includePackages());
    }

    public String includeClassesArg() {
        return String.join("|", scope.includeClasses());
    }

    public String excludeConditionsArg() {
        return String.join("|", scope.excludeConditions());
    }

    public String excludeMethodsArg() {
        return String.join("|", scope.excludeMethods());
    }

    public record Scope(List<String> includePackages, List<String> includeClasses,
                        List<String> excludeConditions, List<String> excludeMethods) {
        public static final List<String> DEFAULT_EXCLUDE_CONDITIONS = List.of(
                "cls:*DTO", "cls:*VO", "cls:*Entity", "cls:*Config");
        public static final List<String> DEFAULT_EXCLUDE_METHODS = List.of(
                "get*", "is*", "set*", "toString", "hashCode", "equals");

        public Scope {
            includePackages = normalize(includePackages);
            includeClasses = normalize(includeClasses);
            // An explicitly empty list means "do not exclude anything". The parser supplies
            // the named defaults only when the field is absent, so UI metrics, YAML and Agent
            // receive exactly the same effective scope.
            excludeConditions = normalize(excludeConditions);
            excludeMethods = normalize(excludeMethods);
        }

        public static Scope defaults() {
            return new Scope(List.of(), List.of(), DEFAULT_EXCLUDE_CONDITIONS, DEFAULT_EXCLUDE_METHODS);
        }
    }

    public record Sampling(int ratePercent, long tailCaptureThresholdMs, int tailMaxBufferedSpans,
                           int tailMaxBufferedSizeMb, TailOverflowPolicy tailOverflowPolicy) {
        public Sampling {
            if (ratePercent < 0 || ratePercent > 100) {
                throw new IllegalArgumentException("sampling.ratePercent 必须在 0 到 100 之间");
            }
            if (tailCaptureThresholdMs < 0) {
                throw new IllegalArgumentException("sampling.tailCaptureThresholdMs 不能小于 0");
            }
            if (tailMaxBufferedSpans <= 0) {
                throw new IllegalArgumentException("sampling.tailMaxBufferedSpans 必须大于 0");
            }
            if (tailMaxBufferedSizeMb <= 0) {
                throw new IllegalArgumentException("sampling.tailMaxBufferedSizeMb 必须大于 0");
            }
            tailOverflowPolicy = tailOverflowPolicy == null ? DEFAULT_TAIL_OVERFLOW_POLICY : tailOverflowPolicy;
        }

        public static Sampling defaults() {
            return new Sampling(DEFAULT_SAMPLING_RATE_PERCENT, DEFAULT_TAIL_CAPTURE_THRESHOLD_MILLIS,
                    DEFAULT_TAIL_MAX_BUFFERED_SPANS, DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB,
                    DEFAULT_TAIL_OVERFLOW_POLICY);
        }
    }

    /** Behaviour when a non-head-sampled trace reaches a configured tail-buffer limit. */
    public enum TailOverflowPolicy {
        /** Flush the buffered portion and stream the remaining spans so the trace is retained. */
        PROMOTE("promote"),
        /** Preserve the former strict tail-sampling behaviour by dropping the whole trace. */
        DROP("drop");

        private final String configValue;

        TailOverflowPolicy(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static TailOverflowPolicy fromConfig(String value) {
            if (value == null || value.isBlank()) return DEFAULT_TAIL_OVERFLOW_POLICY;
            for (TailOverflowPolicy candidate : values()) {
                if (candidate.configValue.equalsIgnoreCase(value.trim())) return candidate;
            }
            throw new IllegalArgumentException("sampling.tailOverflowPolicy 仅支持 promote 或 drop: " + value);
        }
    }

    public record Output(String sessionRootDir, List<ExporterType> exporters, Capture capture,
                         SpanFiles spanFiles, Retention retention, String otlpForwardEndpoint) {
        public Output(String root, List<ExporterType> exporters, Capture capture, SpanFiles files, Retention retention) {
            this(root, exporters, capture, files, retention, "");
        }
        public Output {
            otlpForwardEndpoint = otlpForwardEndpoint == null ? "" : otlpForwardEndpoint.trim();
            if (!otlpForwardEndpoint.isEmpty()) {
                java.net.URI endpoint = java.net.URI.create(otlpForwardEndpoint);
                if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme())) || endpoint.getHost() == null
                        || endpoint.getUserInfo() != null || endpoint.getFragment() != null)
                    throw new IllegalArgumentException("output.otlpForwardEndpoint 必须为 HTTP(S) traces endpoint");
            }
            sessionRootDir = sessionRootDir == null || sessionRootDir.isBlank()
                    ? DEFAULT_SESSION_ROOT_DIRECTORY : sessionRootDir.trim();
            exporters = normalizeExporters(exporters);
            capture = capture == null ? Capture.defaults() : capture;
            spanFiles = spanFiles == null ? SpanFiles.defaults() : spanFiles;
            retention = retention == null ? Retention.defaults() : retention;
        }

        public static Output defaults() {
            return new Output(DEFAULT_SESSION_ROOT_DIRECTORY, List.of(ExporterType.OTLP, ExporterType.FILE),
                    Capture.defaults(), SpanFiles.defaults(), Retention.defaults());
        }
    }

    /** Optional identity overrides for the JVM being observed, not for the monitoring App itself. */
    public record Resource(String serviceName, String serviceVersion, String deploymentEnvironment) {
        public Resource {
            serviceName = normalizeOptional(serviceName);
            serviceVersion = normalizeOptional(serviceVersion);
            deploymentEnvironment = normalizeOptional(deploymentEnvironment);
        }

        public static Resource defaults() {
            return new Resource("", "", "");
        }

        public boolean isConfigured() {
            return !serviceName.isBlank() || !serviceVersion.isBlank() || !deploymentEnvironment.isBlank();
        }
    }

    /** Personal-use defaults intentionally preserve raw diagnostic values. */
    public record Capture(boolean arguments, boolean returnValue, boolean sqlParameters) {
        public static Capture defaults() {
            return new Capture(true, true, true);
        }
    }

    public record SpanFiles(int rotateSizeMb, int rotateMinutes, boolean compress) {
        public SpanFiles {
            if (rotateSizeMb <= 0) throw new IllegalArgumentException("output.spanFiles.rotateSizeMb 必须大于 0");
            if (rotateMinutes <= 0) throw new IllegalArgumentException("output.spanFiles.rotateMinutes 必须大于 0");
        }

        public static SpanFiles defaults() {
            return new SpanFiles(DEFAULT_FILE_ROTATE_SIZE_MB, DEFAULT_FILE_ROTATE_MINUTES, false);
        }
    }

    public record Retention(int maxSessions) {
        public Retention {
            if (maxSessions < 0) throw new IllegalArgumentException("output.retention.maxSessions 不能小于 0");
        }

        public static Retention defaults() {
            return new Retention(0);
        }
    }

    public record Dependencies(boolean jdbc, boolean http, boolean redis, boolean messaging) {
        public Dependencies(boolean jdbc,boolean http){this(jdbc,http,true,true);}
        public static Dependencies defaults() {
            return new Dependencies(true, true,true,true);
        }
    }

    public record Analysis(boolean enabled, long slowTraceThresholdMs, long slowSpanThresholdMs,
                           long slowSelfTimeThresholdMs, boolean includeErrorTrace,
                           int maxTracesPerRootMethod, int maxBottlenecksPerTrace, int maxDepth) {
        public Analysis {
            if (slowTraceThresholdMs <= 0 || slowSpanThresholdMs <= 0 || slowSelfTimeThresholdMs <= 0) {
                throw new IllegalArgumentException("analysis 的慢调用阈值必须大于 0");
            }
            if (maxTracesPerRootMethod <= 0 || maxBottlenecksPerTrace <= 0 || maxDepth <= 0) {
                throw new IllegalArgumentException("analysis 的数量限制必须大于 0");
            }
        }

        public static Analysis defaults() {
            return new Analysis(true, DEFAULT_SLOW_TRACE_THRESHOLD_MILLIS, DEFAULT_SLOW_SPAN_THRESHOLD_MILLIS,
                    DEFAULT_SLOW_SELF_TIME_THRESHOLD_MILLIS, true, DEFAULT_MAX_TRACES_PER_ROOT_METHOD,
                    DEFAULT_MAX_BOTTLENECKS_PER_TRACE, DEFAULT_MAX_DEPTH);
        }
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<ExporterType> normalizeExporters(List<ExporterType> values) {
        if (values == null || values.isEmpty()) return List.of(ExporterType.OTLP, ExporterType.FILE);
        LinkedHashSet<ExporterType> normalized = new LinkedHashSet<>();
        for (ExporterType exporter : values) normalized.add(Objects.requireNonNull(exporter, "exporter"));
        return List.copyOf(normalized);
    }
}
