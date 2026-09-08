package com.agentmonitor.agent.exporter;

import java.util.ArrayList;
import java.util.List;

import com.agentmonitor.agent.model.SpanData;

/** Test-only in-memory sink; it lives beside ExportResult because that result is intentionally internal. */
public final class RecordingSpanExporter implements SpanExporter {

    public final List<SpanData> spans = new ArrayList<>();

    @Override
    public String name() { return "test"; }

    @Override
    public boolean start() { return true; }

    @Override
    public ExportResult export(SpanData span) {
        spans.add(span);
        return ExportResult.accepted();
    }

    @Override
    public void close() { }
}
