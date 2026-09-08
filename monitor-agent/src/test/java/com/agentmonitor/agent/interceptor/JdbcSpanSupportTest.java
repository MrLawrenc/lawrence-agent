package com.agentmonitor.agent.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayDeque;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agentmonitor.model.span.SpanAttribute;
import com.agentmonitor.model.span.SpanKind;

class JdbcSpanSupportTest {

    @BeforeEach
    void setUp() {
        JdbcSpanSupport.ENABLED = true;
        JdbcSpanSupport.CAPTURE_PARAMETERS = true;
        MethodSpanAdvice.ACTIVE = true;
        JdbcSpanSupport.clearStateForTests();
        MethodSpanAdvice.SPAN_STACK.remove();
    }

    @AfterEach
    void tearDown() {
        JdbcSpanSupport.clearStateForTests();
        JdbcSpanSupport.CAPTURE_PARAMETERS = true;
        MethodSpanAdvice.ACTIVE = false;
        MethodSpanAdvice.SPAN_STACK.remove();
    }

    @Test
    void preparedStatementUsesStandardJdbcTemplateAndBoundParameters() {
        Object statement = new Object();
        JdbcSpanSupport.onStatementCreated("prepareStatement", new Object(),
                new Object[] { "select *\nfrom orders where customer_id = ? and status = ?" }, statement);

        JdbcSpanSupport.Context firstParameter = JdbcSpanSupport.enter("setLong", statement,
                new Object[] { 1, 42L });
        JdbcSpanSupport.exit(firstParameter, null);
        JdbcSpanSupport.Context secondParameter = JdbcSpanSupport.enter("setString", statement,
                new Object[] { 2, "PAID" });
        JdbcSpanSupport.exit(secondParameter, null);

        SpanContext root = businessRoot();
        MethodSpanAdvice.SPAN_STACK.get().push(root);
        JdbcSpanSupport.Context execution = JdbcSpanSupport.enter("executeQuery", statement, new Object[0]);

        assertNotNull(execution);
        assertEquals(root.traceId, execution.span().traceId);
        assertEquals(root.spanId, execution.span().parentSpanId);
        assertSame(root.traceSampling, execution.span().traceSampling);
        assertEquals(SpanKind.SQL, execution.span().kind);
        assertEquals("SELECT", execution.span().methodName);
        assertEquals("select * from orders where customer_id = ? and status = ?",
                execution.attributes().get(SpanAttribute.SQL));
        assertEquals("select * from orders where customer_id = ? and status = ?",
                execution.attributes().get(SpanAttribute.DB_QUERY_TEXT));
        assertEquals("SELECT", execution.attributes().get(SpanAttribute.DB_OPERATION_NAME));
        assertEquals("SELECT orders", execution.attributes().get(SpanAttribute.DB_QUERY_SUMMARY));
        assertEquals("{\"1\":42,\"2\":\"PAID\"}",
                execution.attributes().get(SpanAttribute.SQL_PARAMETERS));

        JdbcSpanSupport.exit(execution, null);
        assertEquals(root, MethodSpanAdvice.SPAN_STACK.get().peek());
    }

    @Test
    void sqlExecutionNeverStartsANewTraceOrNestsUnderAnotherSqlSpan() {
        Object statement = new Object();
        JdbcSpanSupport.onStatementCreated("prepareStatement", new Object(),
                new Object[] { "delete from orders where id = ?" }, statement);

        assertNull(JdbcSpanSupport.enter("executeUpdate", statement, new Object[0]));

        SpanContext root = businessRoot();
        SpanContext outerSql = new SpanContext(root.traceId, "sql-parent", root.spanId, "JDBC", "SELECT",
                "test", 1, System.nanoTime(), System.currentTimeMillis(), "", SpanKind.SQL, java.util.Map.of());
        ArrayDeque<SpanContext> stack = MethodSpanAdvice.SPAN_STACK.get();
        stack.push(root);
        stack.push(outerSql);

        assertNull(JdbcSpanSupport.enter("executeUpdate", statement, new Object[0]));
        assertFalse(stack.isEmpty());
    }

    @Test
    void canKeepSqlMetadataWithoutRetainingBoundValues() {
        JdbcSpanSupport.CAPTURE_PARAMETERS = false;
        Object statement = new Object();
        JdbcSpanSupport.onStatementCreated("prepareStatement", new Object(),
                new Object[] { "select * from orders where customer_id = ?" }, statement);
        assertNull(JdbcSpanSupport.enter("setLong", statement, new Object[] { 1, 42L }));

        SpanContext root = businessRoot();
        MethodSpanAdvice.SPAN_STACK.get().push(root);
        JdbcSpanSupport.Context execution = JdbcSpanSupport.enter("executeQuery", statement, new Object[0]);

        assertNotNull(execution);
        assertEquals("select * from orders where customer_id = ?",
                execution.attributes().get(SpanAttribute.SQL));
        assertFalse(execution.attributes().containsKey(SpanAttribute.SQL_PARAMETERS));
    }

    private static SpanContext businessRoot() {
        return new SpanContext("trace-root", "root", "", "example.OrderService", "placeOrder", "test", 0,
                System.nanoTime(), System.currentTimeMillis(), "", SpanKind.BUSINESS, java.util.Map.of());
    }
}
