package com.agentmonitor.agent.interceptor;

import java.util.ArrayList;
import java.util.List;

import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy;

/**
 * Trace-scoped capture decision and bounded tail buffer.
 *
 * <p>Every {@link SpanContext} in one synchronous trace shares this object. A head-sampled
 * trace is exported as each span completes. A trace missed by head sampling retains completed
 * spans locally until its root completes; it is then promoted as one complete trace only when
 * the root is slow or any span failed. When that bounded buffer fills, the configured policy
 * either promotes it to streaming output or explicitly drops it.</p>
 */
public final class TraceSamplingState {

    private static final List<SpanData> NO_SPANS = List.of();

    private final boolean headSampled;
    private final long slowThresholdNanos;
    private final int maxBufferedSpans;
    private final int maxBufferedCharacters;
    private final TailOverflowPolicy overflowPolicy;
    private final List<SpanData> bufferedSpans;
    private int bufferedCharacters;
    private boolean containsError;
    private boolean overflowed;
    private boolean promoted;

    TraceSamplingState(boolean headSampled, long slowThresholdNanos) {
        this(headSampled, slowThresholdNanos, MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SPANS,
                MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB * 1024 * 1024,
                MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY);
    }

    TraceSamplingState(boolean headSampled, long slowThresholdNanos, int maxBufferedSpans,
                       int maxBufferedCharacters, TailOverflowPolicy overflowPolicy) {
        this.headSampled = headSampled;
        this.slowThresholdNanos = Math.max(0, slowThresholdNanos);
        this.maxBufferedSpans = Math.max(1, maxBufferedSpans);
        this.maxBufferedCharacters = Math.max(1, maxBufferedCharacters);
        this.overflowPolicy = overflowPolicy == null ? MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY : overflowPolicy;
        this.bufferedSpans = headSampled ? NO_SPANS : new ArrayList<>();
    }

    /** True when this trace was chosen by the root-level head-sampling decision. */
    public boolean isHeadSampled() {
        return headSampled;
    }

    /**
     * Records one completed Span. Tail promotion returns the completed tree in completion order
     * (children before their parent); an overflow promotion returns the buffered prefix and then
     * streams every subsequent Span. The caller exports the returned values outside its tracing
     * stack; this method never performs I/O.
     */
    synchronized List<SpanData> complete(SpanData span, boolean rootSpan) {
        if (headSampled || span == null) return NO_SPANS;
        if (promoted) return List.of(span);
        if (overflowed) return NO_SPANS;

        int size = estimatedCharacters(span);
        if (bufferedSpans.size() >= maxBufferedSpans
                || size > maxBufferedCharacters - bufferedCharacters) {
            overflowed = true;
            if (overflowPolicy == TailOverflowPolicy.PROMOTE) {
                // The completed portion is ordered exactly like an ordinary tail promotion
                // (children before their parents). From now on every completed span streams
                // immediately, so no portion of a large trace is silently lost.
                List<SpanData> promotedTrace = new ArrayList<>(bufferedSpans.size() + 1);
                promotedTrace.addAll(bufferedSpans);
                promotedTrace.add(span);
                bufferedSpans.clear();
                bufferedCharacters = 0;
                promoted = true;
                return List.copyOf(promotedTrace);
            }
            // DROP is an explicit opt-in for strict bounded tail sampling.
            bufferedSpans.clear();
            bufferedCharacters = 0;
            return NO_SPANS;
        }
        bufferedSpans.add(span);
        bufferedCharacters += size;
        containsError |= span.status().isError();

        if (!rootSpan) return NO_SPANS;
        boolean promote = containsError || span.durationNanos() >= slowThresholdNanos;
        if (!promote) {
            discard();
            return NO_SPANS;
        }
        List<SpanData> result = List.copyOf(bufferedSpans);
        discard();
        return result;
    }

    synchronized void discard() {
        if (!headSampled) bufferedSpans.clear();
        bufferedCharacters = 0;
    }

    boolean overflowed() {
        return overflowed;
    }

    boolean promoted() {
        return promoted;
    }

    private int estimatedCharacters(SpanData span) {
        long count = length(span.traceId()) + length(span.spanId()) + length(span.parentSpanId())
                + length(span.className()) + length(span.methodName()) + length(span.signature())
                + length(span.threadName()) + length(span.arguments()) + length(span.returnValue())
                + length(span.stackTrace()) + 128L;
        for (var attribute : span.attributes().entrySet()) {
            count += length(attribute.getKey()) + length(attribute.getValue()) + 8L;
            if (count >= maxBufferedCharacters) return maxBufferedCharacters;
        }
        return (int) Math.min(maxBufferedCharacters, count);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
