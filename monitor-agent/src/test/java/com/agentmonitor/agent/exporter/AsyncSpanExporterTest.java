package com.agentmonitor.agent.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.model.SpanStatus;
import com.agentmonitor.model.span.SpanKind;

class AsyncSpanExporterTest {

    @Test
    void drainClosesAdmissionThenFlushesEveryAcceptedSpan() {
        RecordingExporter delegate = new RecordingExporter();
        AsyncSpanExporter exporter = new AsyncSpanExporter(delegate);
        try {
            assertTrue(exporter.start());
            assertTrue(exporter.export(span("one")).delivered());
            assertTrue(exporter.export(span("two")).delivered());

            ExporterDrainResult result = exporter.drain(1_000);

            assertTrue(result.drained(), result::summary);
            assertEquals(2, delegate.exported.get());
            assertEquals(1, delegate.drainCalls.get());
            assertFalse(exporter.export(span("after-stop")).delivered(),
                    "drain must establish a finite submission boundary");
            assertEquals(0, result.droppedSpans(),
                    "the drain result describes the finite boundary itself");
            assertEquals(1, exporter.droppedCount(),
                    "an exit that races after admission closes is retained in final Agent diagnostics");
            assertTrue(exporter.statistics().reported());
            assertEquals(2, exporter.statistics().enqueuedSpans());
            assertEquals(1, exporter.statistics().queueDroppedSpans());
            assertEquals(0, exporter.statistics().deliveryDroppedSpans());
            assertEquals(0, exporter.statistics().pendingSpans());
        } finally {
            exporter.close();
        }
    }

    private static SpanData span(String id) {
        return new SpanData("trace", id, "", "example.Test", "work", "()V", "test", 0,
                1L, 1L, SpanStatus.SUCCESS, "", "", "", SpanKind.BUSINESS, java.util.Map.of());
    }

    private static final class RecordingExporter implements SpanExporter {
        private final AtomicInteger exported = new AtomicInteger();
        private final AtomicInteger drainCalls = new AtomicInteger();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean start() {
            return true;
        }

        @Override
        public ExportResult export(SpanData span) {
            exported.incrementAndGet();
            return ExportResult.accepted();
        }

        @Override
        public ExporterDrainResult drain(long timeoutMillis) {
            drainCalls.incrementAndGet();
            return ExporterDrainResult.success();
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
