package com.agentmonitor.core.collector;

import com.agentmonitor.core.model.SpanEvent;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Receiver -> ordered processors -> exporters. Transport implementations stay outside analysis. */
public final class TracePipeline {
    private final List<UnaryOperator<SpanEvent>> processors;
    private final List<Predicate<SpanEvent>> exporters;
    public TracePipeline(List<UnaryOperator<SpanEvent>> processors, List<Predicate<SpanEvent>> exporters) {
        this.processors = List.copyOf(processors); this.exporters = List.copyOf(exporters);
    }
    public boolean receive(SpanEvent span) {
        for (var processor : processors) { span = processor.apply(span); if (span == null) return false; }
        for (var exporter : exporters) if (!exporter.test(span)) return false;
        return true;
    }
}
