package com.agentmonitor.agent.interceptor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.agentmonitor.agent.exporter.RecordingSpanExporter;
import com.agentmonitor.model.span.SpanAttribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaptureSafetyTest {
    private final RecordingSpanExporter sink = new RecordingSpanExporter();
    @BeforeEach void setup() {
        InFlightSpans.startSession();
        MethodSpanAdvice.ACTIVE = true;
        MethodSpanAdvice.EXPORTER = sink;
        MethodSpanAdvice.PKG_FILTERS = new String[]{"example."};
        MethodSpanAdvice.CLS_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_PKG_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_CLS_FILTERS = new String[0];
        MethodSpanAdvice.EXCL_REGEX_FILTERS = new String[0];
        MethodSpanAdvice.CAPTURE_ARGUMENTS = true;
        MethodSpanAdvice.CAPTURE_RETURN_VALUE = true;
        MethodSpanAdvice.configureTraceSampling(100, 50);
        MethodSpanAdvice.SPAN_STACK.remove();
    }
    @AfterEach void cleanup() {
        MethodSpanAdvice.ACTIVE = false;
        MethodSpanAdvice.EXPORTER = null;
        MethodSpanAdvice.SPAN_STACK.remove();
        MethodSpanAdvice.configureTraceSampling(10, 50);
        InFlightSpans.startSession();
    }
    @Test void captureCompletesBeforeTheBusinessClockStartsAndCannotReenterTracing() {
        AtomicLong captureEnded = new AtomicLong();
        Iterable<String> arguments = () -> {
            assertNull(MethodSpanAdvice.onEnter("example.Value", "iterator", new Object[0]));
            captureEnded.set(System.nanoTime());
            return List.of("value").iterator();
        };
        SpanContext context = MethodSpanAdvice.onEnter("example.Service", "work", new Object[]{arguments});
        assertTrue(captureEnded.get() > 0);
        assertTrue(context.startNanos >= captureEnded.get());
        MethodSpanAdvice.onExit("example.Service", "work", "()V", context, null, null);
        assertEquals(1, sink.spans.size());
        assertEquals("head", sink.spans.get(0).attributes().get(SpanAttribute.SAMPLING_REASON));
        assertTrue(InFlightSpans.stopAndAwait(0).complete());
    }
    @Test void neverInvokesArbitraryToStringAndMarksLargeCapture() {
        Object hostile = new Object() {
            @Override public String toString() { throw new AssertionError("Application toString must not run"); }
        };
        assertFalse(ValueCapture.capture(hostile).text().contains("serializeError"));
        SpanContext context = MethodSpanAdvice.onEnter("example.Service", "work", new Object[]{"x".repeat(1_100_000)});
        MethodSpanAdvice.onExit("example.Service", "work", "()V", context, null, "y".repeat(1_100_000));
        assertTrue(sink.spans.get(0).arguments().length() < 10_000);
        assertEquals("arguments,returnValue", sink.spans.get(0).attributes().get(SpanAttribute.CAPTURE_TRUNCATED));
    }
    @Test void excludesKnownNestedObserverTimeWithoutNegativeDurations() {
        SpanContext context = new SpanContext("trace", "span", "", "Service", "work", "test", 0, 100, 0, "");
        context.traceSampling.addObserverNanos(30);
        assertEquals(70, MethodSpanAdvice.durationNanos(context, 200));
        assertEquals(0, MethodSpanAdvice.durationNanos(context, 110));
    }
    @Test void ordinaryNumericMapKeysRetainTheirValue() {
        assertEquals("{\"42\":\"value\"}", ValueCapture.capture(Map.of(42, "value")).text());
        assertFalse(ValueCapture.capture(Map.of(42, "value")).truncated());
        assertTrue(ValueCapture.capture(Map.of(new Object(), "value")).truncated());
    }
    @Test void boundsCyclicAndWideCapturesAndEscapesControlCharacters() {
        var cycle = new java.util.ArrayList<Object>(); cycle.add(cycle);
        assertTrue(ValueCapture.capture(cycle).truncated());
        assertEquals("\"\\u0000\\\"\\\\\"", ValueCapture.capture("\u0000\"\\").text());
        assertTrue(ValueCapture.capture(java.util.Collections.nCopies(100, "value")).truncated());
        assertTrue(ValueCapture.capture(Map.of("items", java.util.Collections.nCopies(20, "x".repeat(2000)))).truncated());
        assertFalse(ValueCapture.active());
    }
}
