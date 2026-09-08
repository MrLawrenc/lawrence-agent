package com.agentmonitor.agent.interceptor;

import java.util.Map;

import com.agentmonitor.model.span.SpanKind;

/**
 * Per-invocation state held only until the intercepted method completes.
 * Parameter values are captured on entry so their original values survive
 * in-method mutation, while any external output is deferred until exit.
 */
public final class SpanContext {

    public final String traceId;
    public final String spanId;
    public final String parentSpanId;
    public final String className;
    public final String methodName;
    public final String threadName;
    public final int depth;
    public final long startNanos;
    public final long startedAtEpochMillis;
    public final String arguments;
    public final SpanKind kind;
    public final Map<String, String> attributes;
    /** Shared root-level sampling decision and optional tail buffer for this whole trace. */
    public final TraceSamplingState traceSampling;

    public SpanContext(String traceId, String spanId, String parentSpanId,
                       String className, String methodName, String threadName,
                       int depth, long startNanos, long startedAtEpochMillis, String arguments) {
        this(traceId, spanId, parentSpanId, className, methodName, threadName, depth, startNanos,
                startedAtEpochMillis, arguments, SpanKind.BUSINESS, Map.of(),
                new TraceSamplingState(true, 0));
    }

    public SpanContext(String traceId, String spanId, String parentSpanId,
                       String className, String methodName, String threadName,
                       int depth, long startNanos, long startedAtEpochMillis, String arguments,
                       SpanKind kind, Map<String, String> attributes) {
        this(traceId, spanId, parentSpanId, className, methodName, threadName, depth, startNanos,
                startedAtEpochMillis, arguments, kind, attributes, new TraceSamplingState(true, 0));
    }

    public SpanContext(String traceId, String spanId, String parentSpanId,
                       String className, String methodName, String threadName,
                       int depth, long startNanos, long startedAtEpochMillis, String arguments,
                       SpanKind kind, Map<String, String> attributes, TraceSamplingState traceSampling) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.className = className;
        this.methodName = methodName;
        this.threadName = threadName;
        this.depth = depth;
        this.startNanos = startNanos;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.arguments = arguments;
        this.kind = kind == null ? SpanKind.BUSINESS : kind;
        this.attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        this.traceSampling = traceSampling == null ? new TraceSamplingState(true, 0) : traceSampling;
    }
}
