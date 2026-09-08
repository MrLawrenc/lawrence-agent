package com.agentmonitor.agent.interceptor;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import com.agentmonitor.model.span.SpanAttribute;
import com.agentmonitor.model.span.SpanKind;

/**
 * JDBC-only collection based on {@link Connection} and {@link Statement}.  No driver, pool, ORM,
 * or SQL framework classes are referenced, so a compliant JDBC implementation is sufficient.
 */
public final class JdbcSpanSupport {

    private static final int MAX_BATCHES = 20;
    private static final Map<Object, StatementState> STATEMENTS = new WeakHashMap<>();

    public static volatile boolean ENABLED = true;
    /** Whether the raw values bound to PreparedStatement placeholders are retained. */
    public static volatile boolean CAPTURE_PARAMETERS = true;

    private JdbcSpanSupport() { }

    /** Records the SQL template when a standard JDBC Connection creates a Statement. */
    public static void onStatementCreated(String methodName, Object connection, Object[] arguments, Object result) {
        if (!ENABLED || !MethodSpanAdvice.ACTIVE || result == null) return;
        String sql = isPreparedStatementMethod(methodName) ? firstSql(arguments) : "";
        String datasource = datasourceName(connection);
        synchronized (STATEMENTS) {
            StatementState state = STATEMENTS.computeIfAbsent(result, ignored -> new StatementState());
            if (!sql.isBlank()) state.sql = normalizeSql(sql);
            if (!datasource.isBlank()) state.datasource = datasource;
        }
    }

    /** Handles parameter binding, batch lifecycle, and SQL execution from one JDBC Statement. */
    public static Context enter(String methodName, Object statement, Object[] arguments) {
        if (!ENABLED || !MethodSpanAdvice.ACTIVE || statement == null) return null;
        String method = methodName == null ? "" : methodName;
        if (isParameterSetter(method, arguments)) {
            return CAPTURE_PARAMETERS
                    ? Context.parameter(statement, parameterIndex(arguments), parameterValue(method, arguments))
                    : null;
        }
        if ("clearParameters".equals(method)) return CAPTURE_PARAMETERS ? Context.clearParameters(statement) : null;
        if ("addBatch".equals(method)) return CAPTURE_PARAMETERS ? Context.addBatch(statement) : null;
        if ("close".equals(method)) return Context.close(statement);
        if (!isExecutionMethod(method)) return null;

        // A JDBC operation is evidence inside an existing trace.  It must never create a trace
        // root, and a proxy delegating to another JDBC implementation must not create a nested,
        // duplicate SQL span.
        SpanContext parent = MethodSpanAdvice.SPAN_STACK.get().peek();
        if (parent == null || parent.kind == SpanKind.SQL) return null;

        StatementSnapshot snapshot = snapshot(statement, arguments);
        if (snapshot.sql.isBlank()) return null;
        long startedAt = System.currentTimeMillis();
        SpanContext span = new SpanContext(parent.traceId, MethodSpanAdvice.nextId(), parent.spanId,
                "JDBC", sqlType(snapshot.sql), Thread.currentThread().getName(), parent.depth + 1,
                System.nanoTime(), startedAt, "", SpanKind.SQL, sqlAttributes(snapshot), parent.traceSampling);
        MethodSpanAdvice.SPAN_STACK.get().push(span);
        return Context.execution(span, statement, new LinkedHashMap<>(span.attributes));
    }

    /** Applies successful statement mutations and exports a completed execution span. */
    public static void exit(Context context, Throwable error) {
        if (context == null) return;
        switch (context.operation) {
            case PARAMETER -> {
                if (error == null) putParameter(context.statement, context.parameterIndex, context.parameterValue);
            }
            case CLEAR_PARAMETERS -> {
                if (error == null) clearParameters(context.statement);
            }
            case ADD_BATCH -> {
                if (error == null) addBatch(context.statement);
            }
            case CLOSE -> {
                if (error == null) forget(context.statement);
            }
            case EXECUTION -> completeExecution(context, error);
        }
    }

    /** A lightweight diagnostic classification for AgentBuilder listener messages. */
    public static boolean isLikelyJdbcClass(String className) {
        String value = className == null ? "" : className.toLowerCase(Locale.ROOT);
        return value.contains("jdbc") || value.contains("statement") || value.contains("datasource");
    }

    static void clearStateForTests() {
        synchronized (STATEMENTS) {
            STATEMENTS.clear();
        }
    }

    private static void completeExecution(Context context, Throwable error) {
        MethodSpanAdvice.removeContext(context.span);
        clearBatches(context.statement);
        long duration = Math.max(0, System.nanoTime() - context.span.startNanos);
        MethodSpanAdvice.complete(context.span, MethodSpanAdvice.completedSpan(context.span, "", duration,
                error, "", context.attributes));
    }

    private static StatementSnapshot snapshot(Object statement, Object[] arguments) {
        String directSql = firstSql(arguments);
        synchronized (STATEMENTS) {
            StatementState state = STATEMENTS.computeIfAbsent(statement, ignored -> new StatementState());
            String sql = directSql.isBlank() ? state.sql : normalizeSql(directSql);
            if (!directSql.isBlank()) state.sql = sql;
            if (state.datasource.isBlank()) state.datasource = datasourceFromStatement(statement);
            List<Map<Integer, String>> batches = copyBatches(state.batches);
            Map<Integer, String> parameters = new LinkedHashMap<>(state.parameters);
            return new StatementSnapshot(sql, state.datasource, parameters, batches);
        }
    }

    private static Map<String, String> sqlAttributes(StatementSnapshot snapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        String operation = sqlType(snapshot.sql);
        values.put(SpanAttribute.DEPENDENCY_TYPE, "JDBC");
        values.put(SpanAttribute.SQL, snapshot.sql);
        values.put(SpanAttribute.SQL_FINGERPRINT, fingerprint(snapshot.sql));
        values.put(SpanAttribute.SQL_TYPE, operation);
        values.put(SpanAttribute.DB_QUERY_TEXT, snapshot.sql);
        values.put(SpanAttribute.DB_OPERATION_NAME, operation);
        values.put(SpanAttribute.DB_QUERY_SUMMARY, querySummary(snapshot.sql, operation));
        if (!snapshot.batches.isEmpty()) {
            values.put(SpanAttribute.DB_OPERATION_BATCH_SIZE, String.valueOf(snapshot.batches.size()));
        }
        DatabaseTarget database = databaseTarget(snapshot.datasource);
        if (!database.safeUrl.isBlank()) values.put(SpanAttribute.DATASOURCE_NAME, database.safeUrl);
        if (!database.system.isBlank()) values.put(SpanAttribute.DB_SYSTEM_NAME, database.system);
        if (!database.namespace.isBlank()) values.put(SpanAttribute.DB_NAMESPACE, database.namespace);
        if (!database.serverAddress.isBlank()) values.put(SpanAttribute.SERVER_ADDRESS, database.serverAddress);
        if (!database.serverPort.isBlank()) values.put(SpanAttribute.SERVER_PORT, database.serverPort);
        if (CAPTURE_PARAMETERS) {
            String parameters = snapshot.batches.isEmpty()
                    ? parametersJson(snapshot.parameters)
                    : batchesJson(snapshot.batches);
            values.put(SpanAttribute.SQL_PARAMETERS, parameters);
        }
        return values;
    }

    private static void putParameter(Object statement, int index, String value) {
        if (index < 1) return;
        synchronized (STATEMENTS) {
            STATEMENTS.computeIfAbsent(statement, ignored -> new StatementState()).parameters.put(index, value);
        }
    }

    private static void clearParameters(Object statement) {
        synchronized (STATEMENTS) {
            StatementState state = STATEMENTS.get(statement);
            if (state != null) state.parameters.clear();
        }
    }

    private static void addBatch(Object statement) {
        synchronized (STATEMENTS) {
            StatementState state = STATEMENTS.computeIfAbsent(statement, ignored -> new StatementState());
            if (state.batches.size() == MAX_BATCHES) state.batches.remove(0);
            state.batches.add(new LinkedHashMap<>(state.parameters));
        }
    }

    private static void clearBatches(Object statement) {
        synchronized (STATEMENTS) {
            StatementState state = STATEMENTS.get(statement);
            if (state != null) state.batches.clear();
        }
    }

    private static void forget(Object statement) {
        synchronized (STATEMENTS) {
            STATEMENTS.remove(statement);
        }
    }

    private static boolean isPreparedStatementMethod(String method) {
        return "prepareStatement".equals(method) || "prepareCall".equals(method);
    }

    private static boolean isExecutionMethod(String method) {
        return "execute".equals(method) || "executeQuery".equals(method)
                || "executeUpdate".equals(method) || "executeLargeUpdate".equals(method)
                || "executeBatch".equals(method) || "executeLargeBatch".equals(method);
    }

    private static boolean isParameterSetter(String method, Object[] arguments) {
        return method.startsWith("set") && arguments != null && arguments.length >= 2
                && arguments[0] instanceof Integer;
    }

    private static int parameterIndex(Object[] arguments) {
        return arguments != null && arguments.length > 0 && arguments[0] instanceof Integer index ? index : -1;
    }

    private static String parameterValue(String method, Object[] arguments) {
        // The value of setNull is semantically null; its second argument is a JDBC type code.
        if ("setNull".equals(method)) return "null";
        Object value = arguments == null || arguments.length < 2 ? null : arguments[1];
        return MethodSpanAdvice.formatValue(value);
    }

    private static String firstSql(Object[] arguments) {
        if (arguments == null || arguments.length == 0 || !(arguments[0] instanceof String sql)) return "";
        return looksLikeSql(sql) ? sql : "";
    }

    private static boolean looksLikeSql(String value) {
        if (value == null) return false;
        String candidate = value.trim();
        while (candidate.startsWith("/*")) {
            int closing = candidate.indexOf("*/", 2);
            if (closing < 0) return false;
            candidate = candidate.substring(closing + 2).trim();
        }
        while (candidate.startsWith("--")) {
            int lineEnd = candidate.indexOf('\n');
            if (lineEnd < 0) return false;
            candidate = candidate.substring(lineEnd + 1).trim();
        }
        return candidate.matches("(?is)^(?:\\{\\s*)?(?:\\?\\s*=\\s*)?"
                + "(select|insert|update|delete|merge|call|with|alter|create|drop|truncate|explain|show|grant|revoke)\\b.*");
    }

    private static String datasourceFromStatement(Object statement) {
        if (!(statement instanceof Statement jdbcStatement)) return "";
        try {
            return datasourceName(jdbcStatement.getConnection());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String datasourceName(Object value) {
        if (!(value instanceof Connection connection)) return "";
        try {
            String url = connection.getMetaData() == null ? "" : connection.getMetaData().getURL();
            return url == null ? "" : redactJdbcUrl(url);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    private static String fingerprint(String sql) {
        return normalizeSql(sql).toUpperCase(Locale.ROOT);
    }

    private static String sqlType(String sql) {
        String normalized = normalizeSql(sql);
        while (normalized.startsWith("/*")) {
            int closing = normalized.indexOf("*/", 2);
            if (closing < 0) break;
            normalized = normalized.substring(closing + 2).trim();
        }
        if (normalized.startsWith("{")) normalized = normalized.substring(1).trim();
        if (normalized.startsWith("?")) {
            int assignment = normalized.indexOf('=');
            if (assignment >= 0) normalized = normalized.substring(assignment + 1).trim();
        }
        int split = normalized.indexOf(' ');
        return (split < 0 ? normalized : normalized.substring(0, split)).toUpperCase(Locale.ROOT);
    }

    private static String querySummary(String sql, String operation) {
        String normalized = normalizeSql(sql);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b(?:from|into|update|join|call)\\s+([a-zA-Z0-9_.$\\\"]+)").matcher(normalized);
        return matcher.find() ? operation + " " + matcher.group(1) : operation;
    }

    /** Extracts low-cardinality database endpoint metadata without retaining URL credentials. */
    private static DatabaseTarget databaseTarget(String jdbcUrl) {
        String safeUrl = redactJdbcUrl(jdbcUrl);
        if (safeUrl.isBlank() || !safeUrl.regionMatches(true, 0, "jdbc:", 0, 5)) {
            return new DatabaseTarget(safeUrl, "", "", "", "");
        }
        String afterJdbc = safeUrl.substring(5);
        int driverEnd = afterJdbc.indexOf(':');
        String driver = driverEnd < 0 ? afterJdbc : afterJdbc.substring(0, driverEnd);
        String system = canonicalDatabaseSystem(driver);
        String remainder = driverEnd < 0 ? "" : afterJdbc.substring(driverEnd + 1);
        String address = "";
        String port = "";
        String namespace = "";
        try {
            if (remainder.startsWith("//")) {
                java.net.URI uri = java.net.URI.create(driver + ":" + remainder);
                address = blank(uri.getHost());
                port = uri.getPort() < 0 ? "" : String.valueOf(uri.getPort());
                namespace = trimPath(uri.getPath());
            } else if ("sqlserver".equalsIgnoreCase(driver)) {
                String endpoint = remainder.startsWith("//") ? remainder.substring(2) : remainder;
                int separator = endpoint.indexOf(';');
                String hostPort = separator < 0 ? endpoint : endpoint.substring(0, separator);
                int colon = hostPort.lastIndexOf(':');
                address = colon < 0 ? hostPort : hostPort.substring(0, colon);
                port = colon < 0 ? "" : hostPort.substring(colon + 1);
                java.util.regex.Matcher databaseName = java.util.regex.Pattern.compile(
                        "(?i)(?:^|;)databaseName=([^;]+)").matcher(endpoint);
                if (databaseName.find()) namespace = databaseName.group(1);
            } else if ("oracle".equalsIgnoreCase(driver)) {
                java.util.regex.Matcher oracle = java.util.regex.Pattern.compile(
                        "(?:thin:)?@?//?([^:/]+)(?::(\\d+))?(?:/([^?;]+))?").matcher(remainder);
                if (oracle.find()) {
                    address = blank(oracle.group(1));
                    port = blank(oracle.group(2));
                    namespace = blank(oracle.group(3));
                }
            } else if ("sqlite".equalsIgnoreCase(driver)) {
                namespace = trimPath(remainder);
            }
        } catch (Exception ignored) {
            // The original sanitized URL remains useful even when a vendor-specific URL is not parseable.
        }
        return new DatabaseTarget(safeUrl, system, namespace, address, port);
    }

    private static String canonicalDatabaseSystem(String driver) {
        return switch (driver == null ? "" : driver.toLowerCase(Locale.ROOT)) {
            case "mysql" -> "mysql";
            case "mariadb" -> "mariadb";
            case "postgresql", "postgres" -> "postgresql";
            case "sqlserver", "mssql" -> "microsoft.sql_server";
            case "oracle" -> "oracle.db";
            case "db2" -> "ibm.db2";
            case "h2" -> "h2database";
            case "hsqldb" -> "hsqldb";
            case "sqlite" -> "sqlite";
            default -> driver == null || driver.isBlank() ? "" : "other_sql";
        };
    }

    private static String redactJdbcUrl(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replaceAll("(?i)([?;&](?:password|pwd|user)=)[^;&]*", "$1***")
                .replaceAll("(?i)(//)[^/@:]+:[^/@]*@", "$1***@");
    }

    private static String trimPath(String value) {
        if (value == null) return "";
        String trimmed = value.replaceFirst("^/+", "");
        int query = trimmed.indexOf('?');
        return query < 0 ? trimmed : trimmed.substring(0, query);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String parametersJson(Map<Integer, String> parameters) {
        if (parameters.isEmpty()) return "{}";
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, String> entry : parameters.entrySet()) {
            if (!first) json.append(',');
            json.append('\"').append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        return json.append('}').toString();
    }

    private static String batchesJson(List<Map<Integer, String>> batches) {
        if (batches.isEmpty()) return "";
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < batches.size(); index++) {
            if (index > 0) json.append(',');
            json.append(parametersJson(batches.get(index)));
        }
        return json.append(']').toString();
    }

    private static List<Map<Integer, String>> copyBatches(List<Map<Integer, String>> batches) {
        List<Map<Integer, String>> result = new ArrayList<>(batches.size());
        for (Map<Integer, String> batch : batches) result.add(new LinkedHashMap<>(batch));
        return result;
    }

    private static final class StatementState {
        private String sql = "";
        private String datasource = "";
        private final Map<Integer, String> parameters = new LinkedHashMap<>();
        private final List<Map<Integer, String>> batches = new ArrayList<>();
    }

    private record StatementSnapshot(String sql, String datasource, Map<Integer, String> parameters,
                                     List<Map<Integer, String>> batches) { }

    private record DatabaseTarget(String safeUrl, String system, String namespace, String serverAddress,
                                  String serverPort) { }

    private enum Operation { PARAMETER, CLEAR_PARAMETERS, ADD_BATCH, CLOSE, EXECUTION }

    public static final class Context {
        private final Operation operation;
        private final Object statement;
        private final int parameterIndex;
        private final String parameterValue;
        private final SpanContext span;
        private final Map<String, String> attributes;

        private Context(Operation operation, Object statement, int parameterIndex, String parameterValue,
                        SpanContext span, Map<String, String> attributes) {
            this.operation = operation;
            this.statement = statement;
            this.parameterIndex = parameterIndex;
            this.parameterValue = parameterValue;
            this.span = span;
            this.attributes = attributes;
        }

        private static Context parameter(Object statement, int index, String value) {
            return new Context(Operation.PARAMETER, statement, index, value, null, Map.of());
        }

        private static Context clearParameters(Object statement) {
            return new Context(Operation.CLEAR_PARAMETERS, statement, -1, "", null, Map.of());
        }

        private static Context addBatch(Object statement) {
            return new Context(Operation.ADD_BATCH, statement, -1, "", null, Map.of());
        }

        private static Context close(Object statement) {
            return new Context(Operation.CLOSE, statement, -1, "", null, Map.of());
        }

        private static Context execution(SpanContext span, Object statement, Map<String, String> attributes) {
            return new Context(Operation.EXECUTION, statement, -1, "", span, attributes);
        }

        SpanContext span() { return span; }
        Map<String, String> attributes() { return attributes; }
    }
}
