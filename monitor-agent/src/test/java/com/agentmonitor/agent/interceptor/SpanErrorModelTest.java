package com.agentmonitor.agent.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.model.SpanStatus;
import com.agentmonitor.model.span.SpanAttribute;
import com.agentmonitor.model.span.SpanKind;

class SpanErrorModelTest {

    @Test
    void recordsAnUnhandledExceptionAsStatusAttributesAndEvent() {
        SpanData span = MethodSpanAdvice.completedSpan(context(), "()V", 42,
                new IllegalStateException("broken order"), "", Map.of());

        assertEquals(SpanStatus.ERROR, span.status());
        assertEquals("java.lang.IllegalStateException", span.attributes().get(SpanAttribute.ERROR_TYPE));
        assertEquals("broken order", span.statusDescription());
        assertEquals(1, span.events().size());
        assertEquals("exception", span.events().get(0).name());
        assertEquals("java.lang.IllegalStateException",
                span.events().get(0).attributes().get(SpanAttribute.EXCEPTION_TYPE));
        assertTrue(span.events().get(0).attributes().containsKey(SpanAttribute.EXCEPTION_STACKTRACE));
    }

    @Test
    void representsSuccessfulCompletionWithUnsetStatus() {
        SpanData span = MethodSpanAdvice.completedSpan(context(), "()V", 42, null, "ok", Map.of());

        assertEquals(SpanStatus.UNSET, span.status());
        assertTrue(span.events().isEmpty());
        assertFalse(span.attributes().containsKey(SpanAttribute.ERROR_TYPE));
    }

    @Test
    void usesSqlStateAndVendorCodeForDatabaseFailures() {
        SpanData span = MethodSpanAdvice.completedSpan(context(), "()V", 42,
                new SQLException("duplicate", "23505", 1062), "", Map.of());

        assertEquals("23505/1062", span.attributes().get(SpanAttribute.ERROR_TYPE));
        assertEquals("23505/1062", span.attributes().get(SpanAttribute.DB_RESPONSE_STATUS_CODE));
    }

    private static SpanContext context() {
        return new SpanContext("trace", "span", "", "example.OrderService", "placeOrder", "test", 0,
                System.nanoTime(), System.currentTimeMillis(), "", SpanKind.BUSINESS, Map.of());
    }
}
