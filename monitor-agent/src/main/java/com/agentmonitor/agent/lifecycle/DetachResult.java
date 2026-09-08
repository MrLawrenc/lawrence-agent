package com.agentmonitor.agent.lifecycle;

import com.agentmonitor.model.output.ExporterStatistics;

/** Result of disabling the Agent, restoring classes, and draining accepted final output. */
public record DetachResult(boolean restored, boolean outputDrained, String message,
                           ExporterStatistics exporterStatistics) {

    private static final String NO_ACTIVE_TRANSFORMER = "没有活动的字节码增强";

    public DetachResult {
        exporterStatistics = exporterStatistics == null ? ExporterStatistics.unavailable() : exporterStatistics;
    }

    public static DetachResult success() {
        return new DetachResult(true, true, "字节码已还原", ExporterStatistics.unavailable());
    }

    public static DetachResult noActiveTransformer() {
        return new DetachResult(true, true, NO_ACTIVE_TRANSFORMER, ExporterStatistics.unavailable());
    }

    public static DetachResult outputDrainFailed(String message) {
        String detail = message == null || message.isBlank()
                ? "字节码已还原，但尾部输出 drain/flush 未完成" : message;
        return new DetachResult(true, false, detail, ExporterStatistics.unavailable());
    }

    public static DetachResult failed(String message) {
        String detail = message == null || message.isBlank() ? "字节码还原失败" : message;
        return new DetachResult(false, false, detail, ExporterStatistics.unavailable());
    }

    public DetachResult withExporterStatistics(ExporterStatistics statistics) {
        return new DetachResult(restored, outputDrained, message, statistics);
    }
}
