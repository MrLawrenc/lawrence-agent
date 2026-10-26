package com.agentmonitor.app.report.model;
import com.agentmonitor.model.output.ExporterStatistics;
public record CaptureQuality(ExporterStatistics agentExporter, long acceptedSpans, long duplicateSpans, long discardedSpans) {
    public CaptureQuality {
        agentExporter = agentExporter == null ? ExporterStatistics.unavailable() : agentExporter;
        if (acceptedSpans < 0 || duplicateSpans < 0 || discardedSpans < 0) throw new IllegalArgumentException("采集计数不能为负");
    }
    public static CaptureQuality unavailable() { return new CaptureQuality(ExporterStatistics.unavailable(), 0, 0, 0); }
    public boolean hasDegradation() { return agentExporter.hasDegradation() || discardedSpans > 0 || duplicateSpans > 0; }
    public String summary() {
        return "接收 " + acceptedSpans + "，重复 " + duplicateSpans + "，丢弃/未闭合 " + discardedSpans
                + "；Agent 丢弃 " + agentExporter.droppedSpans() + "，目标拒绝 " + agentExporter.rejectedDestinations()
                + "，待完成 " + agentExporter.pendingSpans() + (agentExporter.reported() ? "" : "；Agent 质量信息未上报");
    }
}
