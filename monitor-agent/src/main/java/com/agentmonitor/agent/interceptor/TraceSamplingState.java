package com.agentmonitor.agent.interceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import com.agentmonitor.model.span.SpanAttribute;

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
    private final int maxBufferedBytes;
    private final TailOverflowPolicy overflowPolicy;
    private final List<SpanData> bufferedSpans;
    private long bufferedBytes;
    private long observerNanos;
    private long completedSpans;
    private String localRootId="",traceState="";
    private boolean remoteNotSampled;
    public void remoteNotSampled(boolean value){remoteNotSampled=value;}
    public synchronized void registerRoot(String spanId,int depth){if(depth==0&&localRootId.isBlank())localRootId=spanId;}
    public synchronized String localRootId(){return localRootId;}
    public String traceState(){return traceState;}
    public void traceState(String value){traceState=value;}
    public synchronized long nextCompletedSpan(){return ++completedSpans;}
    private final int headRatePercent;
    private boolean containsError;
    private boolean overflowed;
    private boolean promoted;

    TraceSamplingState(boolean headSampled, long slowThresholdNanos) {
        this(headSampled, slowThresholdNanos, MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SPANS,
                MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB * 1024 * 1024,
                MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY);
    }

    TraceSamplingState(boolean headSampled, long slowThresholdNanos, int maxBufferedSpans,
                       int maxBufferedBytes, TailOverflowPolicy overflowPolicy) {
        this(headSampled, slowThresholdNanos, maxBufferedSpans, maxBufferedBytes, overflowPolicy,
                headSampled ? 100 : 0);
    }

    TraceSamplingState(boolean headSampled, long slowThresholdNanos, int maxBufferedSpans,
                       int maxBufferedBytes, TailOverflowPolicy overflowPolicy, int headRatePercent) {
        this.headRatePercent = headRatePercent;
        this.headSampled = headSampled;
        this.slowThresholdNanos = Math.max(0, slowThresholdNanos);
        this.maxBufferedSpans = Math.max(1, maxBufferedSpans);
        this.maxBufferedBytes = Math.max(1, maxBufferedBytes);
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
        if(remoteNotSampled)return NO_SPANS;
        if (headSampled || span == null) return NO_SPANS;
        if (promoted) return List.of(annotate(span, "tail_overflow"));
        if (overflowed) return NO_SPANS;

        long size = estimatedBytes(span);
        if (bufferedSpans.size() >= maxBufferedSpans
                || size > maxBufferedBytes - bufferedBytes) {
            overflowed = true;
            if (overflowPolicy == TailOverflowPolicy.PROMOTE) {
                // The completed portion is ordered exactly like an ordinary tail promotion
                // (children before their parents). From now on every completed span streams
                // immediately, so no portion of a large trace is silently lost.
                List<SpanData> promotedTrace = new ArrayList<>(bufferedSpans.size() + 1);
                promotedTrace.addAll(bufferedSpans);
                promotedTrace.add(span);
                bufferedSpans.clear();
                bufferedBytes = 0;
                promoted = true;
                return promotedTrace.stream().map(value -> annotate(value, "tail_overflow")).toList();
            }
            // DROP is an explicit opt-in for strict bounded tail sampling.
            bufferedSpans.clear();
            bufferedBytes = 0;
            return NO_SPANS;
        }
        bufferedSpans.add(span);
        bufferedBytes += size;
        containsError |= span.status().isError();

        if (!rootSpan) return NO_SPANS;
        boolean promote = containsError || span.durationNanos() >= slowThresholdNanos;
        if (!promote) {
            discard();
            return NO_SPANS;
        }
        String reason = containsError ? "tail_error" : "tail_slow";
        List<SpanData> result = bufferedSpans.stream().map(value -> annotate(value, reason)).toList();
        discard();
        return result;
    }

    synchronized void discard() {
        if (!headSampled) bufferedSpans.clear();
        bufferedBytes = 0;
    }

    boolean overflowed() {
        return overflowed;
    }

    boolean promoted() {
        return promoted;
    }

    public synchronized long observerNanos() { return observerNanos; }
    public synchronized void addObserverNanos(long nanos) { observerNanos += nanos; }
    public int headRatePercent() { return headRatePercent; }

    SpanData annotate(SpanData span, String reason) {
        var attributes = new LinkedHashMap<>(span.attributes());
        attributes.put(SpanAttribute.SAMPLING_REASON, reason);
        attributes.put(SpanAttribute.SAMPLING_RATE, Integer.toString(headRatePercent));
        return new SpanData(span.traceId(), span.spanId(), span.parentSpanId(), span.className(), span.methodName(),
                span.signature(), span.threadName(), span.depth(), span.startedAtEpochMillis(), span.durationNanos(),
                span.status(), span.arguments(), span.returnValue(), span.stackTrace(), span.kind(), attributes,
                span.statusDescription(), span.events());
    }

    /** Conservative retained UTF-16 size, including event metadata; never clamp to the budget. */
    private long estimatedBytes(SpanData span) {
        long count = 256 + bytes(span.traceId()) + bytes(span.spanId()) + bytes(span.parentSpanId())
                + bytes(span.className()) + bytes(span.methodName()) + bytes(span.signature())
                + bytes(span.threadName()) + bytes(span.arguments()) + bytes(span.returnValue())
                + bytes(span.stackTrace()) + bytes(span.statusDescription());
        for (var attribute : span.attributes().entrySet()) count += 64 + bytes(attribute.getKey()) + bytes(attribute.getValue());
        for (var event : span.events()) {
            count += 64 + bytes(event.name());
            for (var attribute : event.attributes().entrySet()) count += 64 + bytes(attribute.getKey()) + bytes(attribute.getValue());
        }
        return count;
    }
    private static long bytes(String value) { return value == null ? 0 : 32L + 2L * value.length(); }
}
