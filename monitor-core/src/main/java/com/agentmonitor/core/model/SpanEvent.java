package com.agentmonitor.core.model;

import com.agentmonitor.model.trace.TraceNode;
import com.agentmonitor.model.span.SpanKind;
import java.util.*;

/** Validated completed-span wire model shared by online collection and offline replay. */
public record SpanEvent(String traceId, String spanId, String parentSpanId, String className, String methodName,
        String signature, String threadName, int depth, long startedAtEpochMillis, long durationNanos,
        boolean error, String arguments, String returnValue, String stackTrace, SpanKind kind,
        Map<String, String> attributes) {
    public SpanEvent {
        attributes = Map.copyOf(attributes);
        for (String value : List.of(traceId,spanId,parentSpanId)) if (value.length()>256) throw new IllegalArgumentException("Oversized span identity");
        for (String value : List.of(className,methodName,signature,threadName)) if (value.length()>8192) throw new IllegalArgumentException("Oversized method identity");
        if (attributes.size()>256) throw new IllegalArgumentException("Too many attributes");
        long text = (long) arguments.length()+returnValue.length()+stackTrace.length();
        for (var entry : attributes.entrySet()) text += (long) entry.getKey().length()+entry.getValue().length();
        if (text>262144) throw new IllegalArgumentException("Span evidence exceeds 256 Ki characters");
    }
    public static SpanEvent parse(String payload) { return com.agentmonitor.core.internal.SpanEventCodec.parse(payload); }
    public boolean localRoot(){return parentSpanId.isBlank() || depth==0 && (kind==SpanKind.HTTP_SERVER && "true".equals(attributes.get("agent.remote_parent")) || "true".equals(attributes.get("agent.local.detached")));}
    public String segmentKey(){String root=attributes.getOrDefault("agent.local.root_span_id","");return traceId+(root.matches("[0-9a-f]{16}")?"/"+root:"");}
    public TraceNode toTraceNode() {
        var evidence=new LinkedHashMap<>(attributes);evidence.put("agent.span.started_at_epoch_millis",Long.toString(startedAtEpochMillis));evidence.put("agent.span.id",spanId);evidence.put("agent.span.parent_id",parentSpanId);
        return new TraceNode(className, methodName, threadName, durationNanos, error, depth, signature,
                arguments, returnValue, stackTrace, kind, evidence, traceId);
    }
}
