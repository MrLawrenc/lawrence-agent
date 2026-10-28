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
        InFlightSpans.startSession();
        JdbcSpanSupport.ENABLED = true;
        JdbcSpanSupport.CAPTURE_PARAMETERS = true;
        MethodSpanAdvice.ACTIVE = true;
        JdbcSpanSupport.clearStateForTests();
        MethodSpanAdvice.SPAN_STACK.remove();
    }

    @AfterEach
    void tearDown() {
        OverheadController.configure(com.agentmonitor.model.config.MonitoringConfig.Overhead.defaults());
        JdbcSpanSupport.clearStateForTests();
        JdbcSpanSupport.CAPTURE_PARAMETERS = true;
        MethodSpanAdvice.ACTIVE = false;
        MethodSpanAdvice.SPAN_STACK.remove();
    }

    @Test void adaptiveDetailRecoveryNeverResurrectsAnOldBinding() {
        Object statement=prepared();JdbcSpanSupport.exit(JdbcSpanSupport.enter("setString",statement,new Object[]{1,"OLD"}),null);
        OverheadController.configure(new com.agentmonitor.model.config.MonitoringConfig.Overhead(true,65536,75,35,1,50_000_000,1000));
        long base=System.nanoTime();OverheadController.observe(60000,base+300_000_000);
        JdbcSpanSupport.exit(JdbcSpanSupport.enter("setString",statement,new Object[]{1,"NEW"}),null);
        OverheadController.configure(com.agentmonitor.model.config.MonitoringConfig.Overhead.defaults());
        MethodSpanAdvice.SPAN_STACK.get().push(businessRoot());var execution=JdbcSpanSupport.enter("executeQuery",statement,new Object[0]);
        assertFalse(execution.attributes().get(SpanAttribute.SQL_PARAMETERS).contains("OLD"));
        assertEquals("sqlParameters_adaptive",execution.attributes().get(SpanAttribute.CAPTURE_TRUNCATED));JdbcSpanSupport.exit(execution,null);
        OverheadController.configure(new com.agentmonitor.model.config.MonitoringConfig.Overhead(true,65536,75,35,1,50_000_000,1000));OverheadController.observe(60000,System.nanoTime()+300_000_000);
        JdbcSpanSupport.exit(JdbcSpanSupport.enter("clearParameters",statement,new Object[0]),null);OverheadController.configure(com.agentmonitor.model.config.MonitoringConfig.Overhead.defaults());
        execution=JdbcSpanSupport.enter("executeQuery",statement,new Object[0]);assertEquals("{}",execution.attributes().get(SpanAttribute.SQL_PARAMETERS));JdbcSpanSupport.exit(execution,null);
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

    @Test
    void recordsActualBatchCountSeparatelyFromTheLastTwentyParameterSamples() {
        Object statement = prepared();
        addBatches(statement, 25);
        MethodSpanAdvice.SPAN_STACK.get().push(businessRoot());
        var batch = JdbcSpanSupport.enter("executeBatch", statement, new Object[0]);
        assertEquals("25", batch.attributes().get(SpanAttribute.DB_OPERATION_BATCH_SIZE));
        assertEquals("20", batch.attributes().get(SpanAttribute.BATCH_PARAMETER_SAMPLES));
        assertEquals("true", batch.attributes().get(SpanAttribute.BATCH_PARAMETERS_TRUNCATED));
        assertEquals(20, batch.attributes().get(SpanAttribute.SQL_PARAMETERS).split("\\{", -1).length - 1);
        JdbcSpanSupport.exit(batch, null);
        var next = JdbcSpanSupport.enter("executeBatch", statement, new Object[0]);
        assertFalse(next.attributes().containsKey(SpanAttribute.DB_OPERATION_BATCH_SIZE));
        JdbcSpanSupport.exit(next, null);
    }

    @Test
    void batchCountDoesNotDependOnCapturingParameterValues() {
        JdbcSpanSupport.CAPTURE_PARAMETERS = false;
        Object statement = prepared();
        addBatches(statement, 30);
        MethodSpanAdvice.SPAN_STACK.get().push(businessRoot());
        var batch = JdbcSpanSupport.enter("executeBatch", statement, new Object[0]);
        assertEquals("30", batch.attributes().get(SpanAttribute.DB_OPERATION_BATCH_SIZE));
        assertEquals("0", batch.attributes().get(SpanAttribute.BATCH_PARAMETER_SAMPLES));
        assertFalse(batch.attributes().containsKey(SpanAttribute.SQL_PARAMETERS));
        JdbcSpanSupport.exit(batch, null);
    }

    @Test
    void clearBatchResetsTheCountButNonBatchExecutionDoesNot() {
        Object statement = prepared();
        addBatches(statement, 3);
        MethodSpanAdvice.SPAN_STACK.get().push(businessRoot());
        var query = JdbcSpanSupport.enter("executeUpdate", statement, new Object[0]);
        assertFalse(query.attributes().containsKey(SpanAttribute.DB_OPERATION_BATCH_SIZE));
        JdbcSpanSupport.exit(query, null);
        var batch = JdbcSpanSupport.enter("executeBatch", statement, new Object[0]);
        assertEquals("3", batch.attributes().get(SpanAttribute.DB_OPERATION_BATCH_SIZE));
        JdbcSpanSupport.exit(batch, null);
        addBatches(statement, 5);
        JdbcSpanSupport.exit(JdbcSpanSupport.enter("clearBatch", statement, new Object[0]), null);
        var empty = JdbcSpanSupport.enter("executeBatch", statement, new Object[0]);
        assertFalse(empty.attributes().containsKey(SpanAttribute.DB_OPERATION_BATCH_SIZE));
        JdbcSpanSupport.exit(empty, null);
    }

    private static Object prepared() {
        Object statement = new Object();
        JdbcSpanSupport.onStatementCreated("prepareStatement", null,
                new Object[]{"insert into orders(id) values (?)"}, statement);
        return statement;
    }
    private static void addBatches(Object statement, int count) {
        for (int i = 0; i < count; i++) {
            JdbcSpanSupport.exit(JdbcSpanSupport.enter("setInt", statement, new Object[]{1, i}), null);
            JdbcSpanSupport.exit(JdbcSpanSupport.enter("addBatch", statement, new Object[0]), null);
        }
    }

    private static SpanContext businessRoot() {
        return new SpanContext("trace-root", "root", "", "example.OrderService", "placeOrder", "test", 0,
                System.nanoTime(), System.currentTimeMillis(), "", SpanKind.BUSINESS, java.util.Map.of());
    }
}
