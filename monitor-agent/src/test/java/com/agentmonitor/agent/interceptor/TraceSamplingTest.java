package com.agentmonitor.agent.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agentmonitor.agent.exporter.RecordingSpanExporter;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy;

class TraceSamplingTest {

    private final RecordingSpanExporter exporter = new RecordingSpanExporter();

    @BeforeEach
    void setUp() {
        InFlightSpans.startSession();
        MethodSpanAdvice.ACTIVE = true;
        MethodSpanAdvice.EXPORTER = exporter;
        MethodSpanAdvice.PKG_FILTERS = new String[] { "example." };
        MethodSpanAdvice.CLS_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_PKG_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_CLS_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_REGEX_FILTERS = new String[0];
        MethodSpanAdvice.CAPTURE_ARGUMENTS = false;
        MethodSpanAdvice.CAPTURE_RETURN_VALUE = false;
        MethodSpanAdvice.SPAN_STACK.remove();
    }

    @AfterEach
    void tearDown() {
        MethodSpanAdvice.ACTIVE = false;
        MethodSpanAdvice.EXPORTER = null;
        MethodSpanAdvice.PKG_FILTERS = new String[0];
        MethodSpanAdvice.CLS_FILTERS = new String[0];
        MethodSpanAdvice.CAPTURE_ARGUMENTS = true;
        MethodSpanAdvice.CAPTURE_RETURN_VALUE = true;
        MethodSpanAdvice.configureTraceSampling(10, 50);
        MethodSpanAdvice.SPAN_STACK.remove();
    }

    @Test
    void aMissedRootDecisionIsInheritedByEveryBusinessSpanAndDoesNotExportFastTrace() {
        MethodSpanAdvice.configureTraceSampling(0, 1_000);

        SpanContext root = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);
        SpanContext child = MethodSpanAdvice.onEnter("example.OrderRepository", "save", new Object[0]);

        assertFalse(root.traceSampling.isHeadSampled());
        assertSame(root.traceSampling, child.traceSampling);

        MethodSpanAdvice.removeContext(child);
        MethodSpanAdvice.complete(child, completed(child, 1_000_000, false));
        MethodSpanAdvice.removeContext(root);
        MethodSpanAdvice.complete(root, completed(root, 2_000_000, false));

        assertTrue(exporter.spans.isEmpty(), "a fast trace missed by head sampling must stay complete-or-absent");
    }

    @Test
    void slowMissedTraceFlushesItsCompleteTreeOnlyAfterTheRootCompletes() {
        MethodSpanAdvice.configureTraceSampling(0, 5);

        SpanContext root = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);
        SpanContext child = MethodSpanAdvice.onEnter("example.OrderRepository", "save", new Object[0]);

        MethodSpanAdvice.removeContext(child);
        MethodSpanAdvice.complete(child, completed(child, 1_000_000, false));
        assertTrue(exporter.spans.isEmpty(), "tail sampling must not emit a child without its root");

        MethodSpanAdvice.removeContext(root);
        MethodSpanAdvice.complete(root, completed(root, 5_000_000, false));

        assertEquals(List.of(child.spanId, root.spanId), exporter.spans.stream().map(SpanData::spanId).toList());
        assertEquals(root.traceId, exporter.spans.get(0).traceId());
        assertEquals(root.spanId, exporter.spans.get(0).parentSpanId());
        assertEquals("tail_slow", exporter.spans.get(0).attributes().get(com.agentmonitor.model.span.SpanAttribute.SAMPLING_REASON));
    }

    @Test
    void errorPromotesAnOtherwiseFastMissedTraceAsOneTree() {
        MethodSpanAdvice.configureTraceSampling(0, 1_000);

        SpanContext root = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);
        SpanContext child = MethodSpanAdvice.onEnter("example.OrderRepository", "save", new Object[0]);

        MethodSpanAdvice.removeContext(child);
        MethodSpanAdvice.complete(child, completed(child, 1_000_000, true));
        MethodSpanAdvice.removeContext(root);
        MethodSpanAdvice.complete(root, completed(root, 2_000_000, false));

        assertEquals(List.of(child.spanId, root.spanId), exporter.spans.stream().map(SpanData::spanId).toList());
    }

    @Test
    void headSampledTraceExportsEverySpanWithoutWaitingForTheSlowThreshold() {
        MethodSpanAdvice.configureTraceSampling(100, 60_000);

        SpanContext root = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);
        SpanContext child = MethodSpanAdvice.onEnter("example.OrderRepository", "save", new Object[0]);

        assertTrue(root.traceSampling.isHeadSampled());
        assertSame(root.traceSampling, child.traceSampling);
        MethodSpanAdvice.removeContext(child);
        MethodSpanAdvice.complete(child, completed(child, 1_000_000, false));
        assertEquals(1, exporter.spans.size());
        MethodSpanAdvice.removeContext(root);
        MethodSpanAdvice.complete(root, completed(root, 2_000_000, false));

        assertEquals(2, exporter.spans.size());
    }

    @Test
    void tailBufferOverflowPromotesTheBufferedTraceAndStreamsTheRoot() {
        MethodSpanAdvice.configureTraceSampling(0, 60_000, 1, 1, TailOverflowPolicy.PROMOTE);

        SpanContext root = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);
        SpanContext child = MethodSpanAdvice.onEnter("example.OrderRepository", "save", new Object[0]);

        MethodSpanAdvice.removeContext(child);
        MethodSpanAdvice.complete(child, completed(child, 1_000_000, false));
        assertTrue(exporter.spans.isEmpty());

        MethodSpanAdvice.removeContext(root);
        MethodSpanAdvice.complete(root, completed(root, 2_000_000, false));

        assertTrue(root.traceSampling.overflowed());
        assertTrue(root.traceSampling.promoted());
        assertEquals(List.of(child.spanId, root.spanId), exporter.spans.stream().map(SpanData::spanId).toList());
    }

    @Test
    void explicitDropPolicyRetainsTheFormerBoundedTailSamplingBehaviour() {
        MethodSpanAdvice.configureTraceSampling(0, 60_000, 1, 1, TailOverflowPolicy.DROP);

        SpanContext root = MethodSpanAdvice.onEnter("example.OrderService", "placeOrder", new Object[0]);
        SpanContext child = MethodSpanAdvice.onEnter("example.OrderRepository", "save", new Object[0]);

        MethodSpanAdvice.removeContext(child);
        MethodSpanAdvice.complete(child, completed(child, 1_000_000, false));
        MethodSpanAdvice.removeContext(root);
        MethodSpanAdvice.complete(root, completed(root, 2_000_000, false));

        assertTrue(root.traceSampling.overflowed());
        assertFalse(root.traceSampling.promoted());
        assertTrue(exporter.spans.isEmpty());
    }

    @Test
    void exactClassWhitelistMatchesOnlyTheNamedClassAndStillHonoursExclusions() {
        MethodSpanAdvice.PKG_FILTERS = new String[0];
        MethodSpanAdvice.CLS_FILTERS = new String[] { "example.StandaloneService" };

        assertTrue(MethodSpanAdvice.matchesFilter("example.StandaloneService"));
        assertFalse(MethodSpanAdvice.matchesFilter("example.StandaloneServiceHelper"));

        MethodSpanAdvice.EXCL_CLS_FILTERS = new String[] { "example.StandaloneService" };
        assertFalse(MethodSpanAdvice.matchesFilter("example.StandaloneService"));
    }

    @Test
    void aSingleOversizedSpanCannotBypassTheDropBudget() {
        TraceSamplingState state = new TraceSamplingState(false, 0, 10, 1024, TailOverflowPolicy.DROP);
        SpanContext context = new SpanContext("trace", "root", "", "Service", "work", "test", 0,
                0, 0, "x".repeat(2_000_000));
        assertTrue(state.complete(completed(context, 1, false), true).isEmpty());
        assertTrue(state.overflowed());
        assertFalse(state.promoted());
    }

    @Test
    void attributesAndExceptionEventsAlsoCountAgainstTheTailBudget() {
        TraceSamplingState state = new TraceSamplingState(false, 0, 10, 1024, TailOverflowPolicy.DROP);
        SpanData span = new SpanData("trace", "span", "", "Service", "work", "()V", "test", 0, 0, 1,
                com.agentmonitor.agent.model.SpanStatus.ERROR, "", "", "", com.agentmonitor.model.span.SpanKind.BUSINESS,
                java.util.Map.of(), "", List.of(new com.agentmonitor.agent.model.SpanEventData("exception", 0,
                        java.util.Map.of("stack", "x".repeat(10000)))));
        assertTrue(state.complete(span, true).isEmpty());
        assertTrue(state.overflowed());
    }

    @Test
    void aSingleOversizedSpanPromotesWithAnExplicitReason() {
        TraceSamplingState state = new TraceSamplingState(false, 0, 10, 1024, TailOverflowPolicy.PROMOTE, 10);
        SpanContext context = new SpanContext("trace", "root", "", "Service", "work", "test", 0,
                0, 0, "x".repeat(2000));
        List<SpanData> spans = state.complete(completed(context, 1, false), true);
        assertTrue(state.overflowed());
        assertTrue(state.promoted());
        assertEquals("tail_overflow", spans.get(0).attributes().get(com.agentmonitor.model.span.SpanAttribute.SAMPLING_REASON));
        assertEquals("10", spans.get(0).attributes().get(com.agentmonitor.model.span.SpanAttribute.SAMPLING_RATE));
    }

    private static SpanData completed(SpanContext context, long durationNanos, boolean error) {
        return new SpanData(context, "", context.startedAtEpochMillis, durationNanos, error, "", "");
    }

}
