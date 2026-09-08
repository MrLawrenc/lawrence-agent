package com.agentmonitor.agent.exporter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.agentmonitor.agent.log.AgentLog;
import com.agentmonitor.agent.model.SpanData;

/** Composite pattern: fans a span out to every configured and healthy destination. */
final class CompositeSpanExporter implements SpanExporter {

    private final List<SpanExporter> configuredExporters;
    private final List<SpanExporter> activeExporters = new ArrayList<>();

    CompositeSpanExporter(List<SpanExporter> configuredExporters) {
        this.configuredExporters = List.copyOf(configuredExporters);
    }

    @Override
    public String name() { return "composite"; }

    @Override
    public boolean start() {
        for (SpanExporter exporter : configuredExporters) {
            try {
                if (exporter.start()) activeExporters.add(exporter);
                else exporter.close();
            } catch (Exception e) {
                AgentLog.error("[agent-monitor] exporter start failed " + exporter.name() + ": " + e.getMessage());
                try { exporter.close(); } catch (Exception ignored) { }
            }
        }
        AgentLog.info("[agent-monitor] exporters active: " + activeExporters.size());
        return !activeExporters.isEmpty();
    }

    @Override
    public ExportResult export(SpanData span) {
        ExportResult result = new ExportResult(0, 0);
        for (SpanExporter exporter : activeExporters) {
            try {
                result = result.combine(exporter.export(span));
            } catch (Throwable ignored) {
                result = result.combine(ExportResult.rejected());
            }
        }
        return result;
    }

    @Override
    public void ready() {
        for (SpanExporter exporter : activeExporters) exporter.ready();
    }

    @Override
    public ExporterDrainResult drain(long timeoutMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        long pending = 0;
        long dropped = 0;
        long rejected = 0;
        List<String> failures = new ArrayList<>();
        // A leaf may share the control channel with STOPPED, so each leaf must establish its own
        // write/flush barrier before the Agent acknowledges the stop.
        for (SpanExporter exporter : List.copyOf(activeExporters)) {
            long remainingMillis = remainingMillis(deadlineNanos);
            if (remainingMillis < 0) {
                failures.add(exporter.name() + " 未获得 drain 时间");
                continue;
            }
            try {
                ExporterDrainResult result = exporter.drain(remainingMillis);
                if (result == null) {
                    failures.add(exporter.name() + " 未返回 drain 结果");
                    continue;
                }
                pending += result.pendingSpans();
                dropped += result.droppedSpans();
                rejected += result.rejectedDestinations();
                if (!result.drained()) failures.add(exporter.name() + ": " + result.detail());
            } catch (Throwable error) {
                failures.add(exporter.name() + ": " + describe(error));
            }
        }
        if (failures.isEmpty()) return ExporterDrainResult.success(dropped, rejected);
        return ExporterDrainResult.failed(pending, dropped, rejected, String.join("；", failures));
    }

    @Override
    public void close() {
        for (SpanExporter exporter : activeExporters) {
            try { exporter.close(); } catch (Exception ignored) { }
        }
        activeExporters.clear();
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos < 0) return -1;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static String describe(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
