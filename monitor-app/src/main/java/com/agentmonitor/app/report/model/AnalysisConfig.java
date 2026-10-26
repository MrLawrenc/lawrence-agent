package com.agentmonitor.app.report.model;
import com.agentmonitor.model.config.MonitoringConfig;
public record AnalysisConfig(boolean enabled, long slowTraceThresholdMs, long slowSpanThresholdMs,
        long slowSelfTimeThresholdMs, boolean includeErrorTrace, int maxTracesPerRootMethod,
        int maxBottlenecksPerTrace, int maxDepth) {
    public AnalysisConfig {
        new MonitoringConfig.Analysis(enabled, slowTraceThresholdMs, slowSpanThresholdMs, slowSelfTimeThresholdMs,
                includeErrorTrace, maxTracesPerRootMethod, maxBottlenecksPerTrace, maxDepth);
    }
    public static AnalysisConfig disabled() {
        var d = MonitoringConfig.Analysis.defaults();
        return new AnalysisConfig(false, d.slowTraceThresholdMs(), d.slowSpanThresholdMs(), d.slowSelfTimeThresholdMs(),
                d.includeErrorTrace(), d.maxTracesPerRootMethod(), d.maxBottlenecksPerTrace(), d.maxDepth());
    }
}
