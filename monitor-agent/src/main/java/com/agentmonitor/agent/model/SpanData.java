package com.agentmonitor.agent.model;

import java.util.Map;
import java.util.List;

import com.agentmonitor.agent.interceptor.SpanContext;
import com.agentmonitor.model.span.SpanKind;

/** Immutable domain event emitted when one instrumented method completes. */
public record SpanData(
        String traceId,
        String spanId,
        String parentSpanId,
        String className,
        String methodName,
        String signature,
        String threadName,
        int depth,
        long startedAtEpochMillis,
        long durationNanos,
        SpanStatus status,
        String arguments,
        String returnValue,
        String stackTrace,
        SpanKind kind,
        Map<String, String> attributes,
        String statusDescription,
        List<SpanEventData> events) {

    public SpanData {
        kind = kind == null ? SpanKind.BUSINESS : kind;
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        statusDescription = statusDescription == null ? "" : statusDescription;
        events = List.copyOf(events == null ? List.of() : events);
    }

    /** Compatibility constructor for the original schema-v1 domain model. */
    public SpanData(String traceId, String spanId, String parentSpanId, String className, String methodName,
                    String signature, String threadName, int depth, long startedAtEpochMillis, long durationNanos,
                    SpanStatus status, String arguments, String returnValue, String stackTrace, SpanKind kind,
                    Map<String, String> attributes) {
        this(traceId, spanId, parentSpanId, className, methodName, signature, threadName, depth,
                startedAtEpochMillis, durationNanos, status, arguments, returnValue, stackTrace, kind, attributes,
                "", List.of());
    }

    public SpanData(SpanContext context, String signature, long startedAtEpochMillis,
                    long durationNanos, boolean error, String returnValue, String stackTrace) {
        this(context.traceId, context.spanId, context.parentSpanId, context.className, context.methodName,
                signature == null ? "" : signature, context.threadName, context.depth, startedAtEpochMillis,
                durationNanos, SpanStatus.fromError(error), context.arguments, returnValue == null ? "" : returnValue,
                stackTrace == null ? "" : stackTrace, context.kind, context.attributes, "", List.of());
    }
}
