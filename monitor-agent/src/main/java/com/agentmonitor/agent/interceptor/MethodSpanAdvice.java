package com.agentmonitor.agent.interceptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import com.agentmonitor.agent.exporter.SpanExporter;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy;
import com.agentmonitor.agent.model.SpanEventData;
import com.agentmonitor.agent.model.SpanStatus;
import com.agentmonitor.model.span.SpanAttribute;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class MethodSpanAdvice {

    private static final String TRACE_ID_PREFIX = "trace-";
    private static final char ID_SEPARATOR = '-';
    private static final int SERIALIZATION_DEPTH = 2;
    private static final int MAX_STACK_TRACE_CHARACTERS = 8_000;
    private static final int MAX_VALUE_CHARACTERS = 200;
    private static final int MAX_ARRAY_ELEMENTS = 10;
    private static final int MAX_MAP_ENTRIES = 20;
    private static final int SAMPLING_BUCKETS = 100;

    public static volatile SpanExporter EXPORTER;
    public static volatile boolean    ACTIVE             = false;
    public static volatile String[]   PKG_FILTERS        = new String[0];
    public static volatile String[]   CLS_FILTERS        = new String[0];
    public static volatile String[]   EXCL_PKG_FILTERS   = new String[0];
    public static volatile String[]   EXCL_CLS_FILTERS   = new String[0];
    public static volatile String[]   EXCL_REGEX_FILTERS = new String[0];
    public static volatile boolean    CAPTURE_ARGUMENTS  = true;
    public static volatile boolean    CAPTURE_RETURN_VALUE = true;
    /** Percentage of root traces that are recorded immediately; remaining traces use tail promotion. */
    public static volatile int        SAMPLING_RATE_PERCENT = 10;
    /** Root duration that promotes a trace missed by head sampling, expressed in milliseconds. */
    public static volatile long       SLOW_TRACE_THRESHOLD_MILLIS = 50;
    /** Maximum tail-buffered spans before the configured overflow policy takes effect. */
    public static volatile int        TAIL_MAX_BUFFERED_SPANS = MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SPANS;
    /** Maximum tail-buffered payload before the configured overflow policy takes effect. */
    public static volatile int        TAIL_MAX_BUFFERED_BYTES = bytesFromMegabytes(
            MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB);
    public static volatile TailOverflowPolicy TAIL_OVERFLOW_POLICY = MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY;

    private static final String PROCESS_ID = Long.toUnsignedString(System.nanoTime(), 36);
    private static final AtomicLong NEXT_SPAN_ID = new AtomicLong();
    private static final AtomicLong NEXT_ROOT_SAMPLING_DECISION = new AtomicLong();

    public static final ThreadLocal<ArrayDeque<SpanContext>> SPAN_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Advice.OnMethodEnter
    public static SpanContext onEnter(@Advice.Origin("#t") String className,
                                      @Advice.Origin("#m") String methodName,
                                      @Advice.AllArguments Object[] allArgs) {
        if (!MethodSpanAdvice.ACTIVE || !MethodSpanAdvice.matchesFilter(className)) return null;
        ArrayDeque<SpanContext> stack = SPAN_STACK.get();
        SpanContext parent = stack.peek();
        TraceSamplingState traceSampling = parent == null ? newTraceSamplingState() : parent.traceSampling;
        String spanId = nextId();
        SpanContext context = new SpanContext(
                parent == null ? TRACE_ID_PREFIX + spanId : parent.traceId,
                spanId,
                parent == null ? "" : parent.spanId,
                className,
                methodName,
                Thread.currentThread().getName(),
                stack.size(),
                System.nanoTime(),
                System.currentTimeMillis(),
                CAPTURE_ARGUMENTS ? safeFormatArgs(allArgs) : "",
                com.agentmonitor.model.span.SpanKind.BUSINESS, Map.of(), traceSampling);
        stack.push(context);
        return context;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin("#t") String className,
                               @Advice.Origin("#m") String methodName,
                               @Advice.Origin("#d") String descriptor,
                               @Advice.Enter SpanContext context,
                               @Advice.Thrown Throwable thrown,
                               @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue) {
        if (context == null) return;
        removeContext(context);
        long duration = System.nanoTime() - context.startNanos;
        String retVal = thrown != null || !CAPTURE_RETURN_VALUE ? null : formatValue(returnValue);
        complete(context, completedSpan(context, descriptor, duration, thrown, retVal, context.attributes));
    }

    public static String nextId() {
        return PROCESS_ID + ID_SEPARATOR + Long.toUnsignedString(NEXT_SPAN_ID.incrementAndGet(), 36);
    }

    public static void removeContext(SpanContext context) {
        Deque<SpanContext> stack = SPAN_STACK.get();
        if (!stack.isEmpty() && stack.peek() == context) stack.pop();
        else stack.removeFirstOccurrence(context);
        if (stack.isEmpty()) SPAN_STACK.remove();
    }

    /** Public because Advice is inlined into classes from the target application. */
    public static void send(SpanContext context, String descriptor, long duration,
                            boolean error, String returnValue, String stackTrace) {
        complete(context, new SpanData(context, descriptor, context.startedAtEpochMillis,
                duration, error, returnValue, stackTrace));
    }

    /**
     * Creates a completed span with standard error attributes/events while retaining the legacy
     * `error` and `stackTrace` fields consumed by the local viewer.
     */
    public static SpanData completedSpan(SpanContext context, String descriptor, long duration,
                                         Throwable error, String returnValue,
                                         Map<String, String> spanAttributes) {
        Map<String, String> attributes = new LinkedHashMap<>(spanAttributes == null ? Map.of() : spanAttributes);
        String stackTrace = "";
        String statusDescription = "";
        List<SpanEventData> events = List.of();
        if (error != null) {
            String exceptionType = error.getClass().getName();
            String message = error.getMessage() == null ? "" : truncate(error.getMessage(), MAX_VALUE_CHARACTERS);
            stackTrace = formatStackTrace(error);
            String errorType = attributes.getOrDefault(SpanAttribute.ERROR_TYPE, sqlErrorType(error, exceptionType));
            attributes.put(SpanAttribute.ERROR_TYPE, errorType);
            if (error instanceof java.sql.SQLException sqlError) {
                String responseCode = sqlResponseCode(sqlError);
                if (!responseCode.isBlank()) attributes.put(SpanAttribute.DB_RESPONSE_STATUS_CODE, responseCode);
            }
            Map<String, String> exceptionAttributes = new LinkedHashMap<>();
            exceptionAttributes.put(SpanAttribute.EXCEPTION_TYPE, exceptionType);
            if (!message.isBlank()) exceptionAttributes.put(SpanAttribute.EXCEPTION_MESSAGE, message);
            if (!stackTrace.isBlank()) exceptionAttributes.put(SpanAttribute.EXCEPTION_STACKTRACE, stackTrace);
            events = List.of(new SpanEventData("exception", System.currentTimeMillis(), exceptionAttributes));
            statusDescription = message;
        }
        return new SpanData(context.traceId, context.spanId, context.parentSpanId,
                context.className, context.methodName, descriptor == null ? "" : descriptor,
                context.threadName, context.depth, context.startedAtEpochMillis, Math.max(0, duration),
                SpanStatus.fromError(error != null), context.arguments, returnValue == null ? "" : returnValue,
                stackTrace, context.kind, attributes, statusDescription, events);
    }

    /**
     * Applies the trace-level decision to one completed span.  Dependency collectors call this
     * too, so JDBC and HTTP evidence cannot diverge from their owning business trace.
     */
    static void complete(SpanContext context, SpanData span) {
        if (context == null || span == null) return;
        SpanExporter exporter = EXPORTER;
        boolean rootSpan = context.parentSpanId == null || context.parentSpanId.isBlank();
        if (exporter == null) {
            if (rootSpan) context.traceSampling.discard();
            return;
        }
        if (context.traceSampling.isHeadSampled()) {
            exporter.export(span);
            return;
        }
        List<SpanData> promotedTrace = context.traceSampling.complete(span, rootSpan);
        for (SpanData completedSpan : promotedTrace) exporter.export(completedSpan);
    }

    private static String sqlErrorType(Throwable error, String fallback) {
        if (error instanceof java.sql.SQLException sqlError) {
            String responseCode = sqlResponseCode(sqlError);
            if (!responseCode.isBlank()) return responseCode;
        }
        return fallback;
    }

    private static String sqlResponseCode(java.sql.SQLException error) {
        String state = error.getSQLState() == null ? "" : error.getSQLState().trim();
        int vendorCode = error.getErrorCode();
        if (state.isBlank()) return vendorCode == 0 ? "" : String.valueOf(vendorCode);
        return vendorCode == 0 ? state : state + "/" + vendorCode;
    }

    /** Configures root-trace sampling for one Agent generation. */
    public static void configureTraceSampling(int samplingRatePercent, long slowThresholdMillis) {
        configureTraceSampling(samplingRatePercent, slowThresholdMillis,
                MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SPANS,
                MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB,
                MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY);
    }

    /** Configures head sampling and the bounded tail buffer for one Agent generation. */
    public static void configureTraceSampling(int samplingRatePercent, long slowThresholdMillis,
                                              int tailMaxBufferedSpans, int tailMaxBufferedSizeMb,
                                              TailOverflowPolicy tailOverflowPolicy) {
        SAMPLING_RATE_PERCENT = Math.max(0, Math.min(SAMPLING_BUCKETS, samplingRatePercent));
        SLOW_TRACE_THRESHOLD_MILLIS = Math.max(0, slowThresholdMillis);
        TAIL_MAX_BUFFERED_SPANS = Math.max(1, tailMaxBufferedSpans);
        TAIL_MAX_BUFFERED_BYTES = bytesFromMegabytes(tailMaxBufferedSizeMb);
        TAIL_OVERFLOW_POLICY = tailOverflowPolicy == null
                ? MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY : tailOverflowPolicy;
    }

    static TraceSamplingState newTraceSamplingState() {
        return new TraceSamplingState(headSampled(), nanosFromMillis(SLOW_TRACE_THRESHOLD_MILLIS),
                TAIL_MAX_BUFFERED_SPANS, TAIL_MAX_BUFFERED_BYTES, TAIL_OVERFLOW_POLICY);
    }

    private static boolean headSampled() {
        int rate = SAMPLING_RATE_PERCENT;
        if (rate <= 0) return false;
        if (rate >= SAMPLING_BUCKETS) return true;
        // Multiplication by 37 permutes the 100 buckets. This yields exactly `rate` decisions
        // per 100 roots while spreading them out more evenly than sampling the first N roots.
        long sequence = NEXT_ROOT_SAMPLING_DECISION.getAndIncrement();
        int bucket = (int) Math.floorMod(sequence * 37L + 17L, SAMPLING_BUCKETS);
        return bucket < rate;
    }

    private static long nanosFromMillis(long millis) {
        if (millis <= 0) return 0;
        try {
            return Math.multiplyExact(millis, 1_000_000L);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static int bytesFromMegabytes(int megabytes) {
        long normalized = Math.max(1L, megabytes);
        return (int) Math.min(Integer.MAX_VALUE, normalized * 1024L * 1024L);
    }

    public static String formatStackTrace(Throwable thrown) {
        if (thrown == null) return "";
        try {
            java.io.StringWriter writer = new java.io.StringWriter();
            thrown.printStackTrace(new java.io.PrintWriter(writer));
            return truncate(writer.toString(), MAX_STACK_TRACE_CHARACTERS);
        } catch (Throwable error) {
            return thrown.getClass().getName() + ": " + String.valueOf(thrown.getMessage());
        }
    }

    public static String formatArgs(Object[] args) {
        if (args == null || args.length == 0) return "";
        if (args.length == 1) return toJson(args[0], SERIALIZATION_DEPTH);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(toJson(args[i], SERIALIZATION_DEPTH));
        }
        sb.append("]");
        return sb.toString();
    }

    public static String safeFormatArgs(Object[] args) {
        try {
            return formatArgs(args);
        } catch (Throwable error) {
            return "{\"_serializeError\":\"" + escapeJson(error.getClass().getSimpleName()) + "\"}";
        }
    }

    public static String formatValue(Object val) {
        if (val == null) return "null";
        try { return toJson(val, SERIALIZATION_DEPTH); }
        catch (Exception e) { return "{\"_t\":\"" + val.getClass().getSimpleName() + "\"}"; }
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "\u2026" : s;
    }

    private static String toJson(Object val, int depth) {
        if (val == null) return "null";
        if (val instanceof Boolean || val instanceof Number) return val.toString();
        if (val instanceof Character) return "\"" + val + "\"";
        if (val instanceof String) return "\"" + escapeJson((String) val) + "\"";
        if (isUnsafeReactorQueue(val)) return typeSummary(val);
        if (val.getClass().isArray()) return arrayToJson(val, depth);
        if (val instanceof Iterable) return iterableToJson((Iterable<?>) val, depth);
        if (val instanceof Map) return mapToJson((Map<?, ?>) val, depth);
        try {
            String s = val.toString();
            if (!isDefaultToString(val, s)) return "\"" + escapeJson(truncate(s, MAX_VALUE_CHARACTERS)) + "\"";
        } catch (Exception ignored) {}
        if (depth <= 0) return "{\"_t\":\"" + val.getClass().getSimpleName() + "\"}";
        return pojoToJson(val, depth - 1);
    }

    private static boolean isDefaultToString(Object val, String s) {
        return s != null && s.contains("@")
                && (s.startsWith(val.getClass().getName()) || s.startsWith(val.getClass().getSimpleName()));
    }

    private static String pojoToJson(Object val, int depth) {
        StringBuilder sb = new StringBuilder("{");
        int count = 0;
        for (Class<?> c = val.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (count > 0) sb.append(", ");
                    sb.append("\"").append(f.getName()).append("\": ")
                      .append(toJson(f.get(val), depth));
                    count++;
                } catch (Exception ignored) {}
            }
        }
        return sb.append("}").toString();
    }

    private static String arrayToJson(Object arr, int depth) {
        int len = java.lang.reflect.Array.getLength(arr);
        if (len == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(len, MAX_ARRAY_ELEMENTS);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(depth > 0 ? toJson(java.lang.reflect.Array.get(arr, i), depth - 1) : "\"...\"");
        }
        if (len > MAX_ARRAY_ELEMENTS) sb.append(", \"...\"");
        return sb.append("]").toString();
    }

    private static String iterableToJson(Iterable<?> it, int depth) {
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        try {
            for (Object elem : it) {
                if (count > 0) sb.append(", ");
                sb.append(depth > 0 ? toJson(elem, depth - 1) : "\"...\"");
                count++;
                if (count >= MAX_ARRAY_ELEMENTS) {
                    sb.append(", \"...\"");
                    break;
                }
            }
        } catch (Throwable error) {
            return typeSummary(it);
        }
        return sb.append("]").toString();
    }

    private static String mapToJson(Map<?, ?> map, int depth) {
        StringBuilder sb = new StringBuilder("{");
        int count = 0;
        try {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (count > 0) sb.append(", ");
                sb.append("\"").append(escapeJson(String.valueOf(e.getKey()))).append("\": ")
                  .append(depth > 0 ? toJson(e.getValue(), depth - 1) : "\"...\"");
                count++;
                if (count >= MAX_MAP_ENTRIES) {
                    sb.append(", \"...\": \"...\"");
                    break;
                }
            }
        } catch (Throwable error) {
            return typeSummary(map);
        }
        return sb.append("}").toString();
    }

    private static boolean isUnsafeReactorQueue(Object val) {
        if (!(val instanceof Queue)) return false;
        String name = val.getClass().getName();
        for (Class<?> c = val.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                String ifaceName = iface.getName();
                if ("reactor.core.Fuseable$QueueSubscription".equals(ifaceName)) return true;
                if (ifaceName.startsWith("reactor.core.") && ifaceName.contains("QueueSubscription")) return true;
            }
        }
        return name.startsWith("reactor.") || name.contains("QueueSubscription");
    }

    private static String typeSummary(Object val) {
        if (val == null) return "null";
        return "{\"_t\":\"" + escapeJson(val.getClass().getName()) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public static boolean matchesFilter(String className) {
        String[] pkgs = PKG_FILTERS;
        String[] classes = CLS_FILTERS;
        boolean inTarget = false;
        for (String p : pkgs) {
            if (!p.isEmpty() && className.startsWith(p)) {
                inTarget = true;
                break;
            }
        }
        if (!inTarget) {
            for (String candidate : classes) {
                if (!candidate.isEmpty() && className.equals(candidate)) {
                    inTarget = true;
                    break;
                }
            }
        }
        if (!inTarget) return false;
        for (String p : EXCL_PKG_FILTERS)   { if (!p.isEmpty() && className.startsWith(p))  return false; }
        for (String c : EXCL_CLS_FILTERS)   { if (!c.isEmpty() && (className.equals(c) || className.endsWith("." + c))) return false; }
        for (String r : EXCL_REGEX_FILTERS) { if (!r.isEmpty() && className.matches(r))      return false; }
        return true;
    }
}
