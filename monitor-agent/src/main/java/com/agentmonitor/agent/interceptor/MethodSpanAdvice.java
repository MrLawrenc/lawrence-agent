package com.agentmonitor.agent.interceptor;
import java.util.concurrent.atomic.AtomicLong;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentmonitor.agent.exporter.SpanExporter;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy;
import com.agentmonitor.agent.model.SpanEventData;
import com.agentmonitor.agent.model.SpanStatus;
import com.agentmonitor.agent.protocol.SpanPayloadLimits;
import com.agentmonitor.model.span.SpanAttribute;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class MethodSpanAdvice {

    private static final int MAX_STACK_TRACE_CHARACTERS = 8_000;
    private static final int MAX_VALUE_CHARACTERS = 200;
    private static final int SAMPLING_BUCKETS = 100;

    public static volatile SpanExporter EXPORTER;
    public static volatile boolean    ACTIVE             = false;
    public static volatile String[]   PKG_FILTERS        = new String[0];
    public static volatile String[]   CLS_FILTERS        = new String[0];
    public static volatile String[]   EXCL_PKG_FILTERS   = new String[0];
    public static volatile String[]   EXCL_CLS_FILTERS   = new String[0];
    public static volatile String[] EXCL_PACKAGE_GLOBS = new String[0];
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

    private static final AtomicLong NEXT_ROOT_SAMPLING_DECISION = new AtomicLong();

    public static final ThreadLocal<ArrayDeque<SpanContext>> SPAN_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Advice.OnMethodEnter
    public static SpanContext onEnter(@Advice.Origin("#t") String className,
                                      @Advice.Origin("#m") String methodName,
                                      @Advice.AllArguments Object[] allArgs) {
        if (!ACTIVE || ValueCapture.active() || !matchesFilter(className)) return null;
        long observerStarted = System.nanoTime();
        ArrayDeque<SpanContext> stack = SPAN_STACK.get();
        SpanContext parent = stack.peek();
        if (parent == null) parent = AsyncSpanSupport.current();
        InFlightSpans.Ticket invocation = InFlightSpans.enter(parent == null);
        if (invocation == null) return null;
        try {
            TraceSamplingState sampling = parent == null ? newTraceSamplingState() : parent.traceSampling;
            ValueCapture.Result captured = CAPTURE_ARGUMENTS && OverheadController.detailEnabled() ? ValueCapture.arguments(allArgs)
                    : new ValueCapture.Result("", false);
            String spanId = nextId();
            Map<String,String> captureAttributes=new LinkedHashMap<>();captureAttributes.put("agent.overhead.entry_mode",OverheadController.mode());
            if(captured.truncated())captureAttributes.put(SpanAttribute.CAPTURE_TRUNCATED,"arguments");
            if(CAPTURE_ARGUMENTS&&!OverheadController.detailEnabled())captureAttributes.merge(SpanAttribute.CAPTURE_TRUNCATED,"arguments_adaptive",(a,b)->a+","+b);
            SpanContext context = new SpanContext(parent == null ? nextTraceId() : parent.traceId,
                    spanId, parent == null ? "" : parent.spanId, className, methodName,
                    Thread.currentThread().getName(), parent == null ? 0 : parent.depth + 1, System.nanoTime(), System.currentTimeMillis(),
                    captured.text(), com.agentmonitor.model.span.SpanKind.BUSINESS,
                    captureAttributes,
                    sampling, invocation);
            stack.push(context);
            recordEntryOverhead(context, observerStarted);
            return context;
        } catch (Throwable error) {
            invocation.close();
            throw error;
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin("#t") String className,
                               @Advice.Origin("#m") String methodName,
                               @Advice.Origin("#d") String descriptor,
                               @Advice.Enter SpanContext context,
                               @Advice.Thrown Throwable thrown,
                               @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue) {
        if (context == null) return;
        long observerStarted = System.nanoTime();
        long duration = durationNanos(context, observerStarted);
        removeContext(context);
        try {
            ValueCapture.Result returned = thrown != null || !CAPTURE_RETURN_VALUE || !OverheadController.detailEnabled()
                    ? new ValueCapture.Result("", false) : ValueCapture.capture(returnValue);
            Map<String, String> attributes = new LinkedHashMap<>(context.attributes);
            if(thrown==null&&CAPTURE_RETURN_VALUE&&!OverheadController.detailEnabled())attributes.merge(SpanAttribute.CAPTURE_TRUNCATED,"returnValue_adaptive",(a,b)->a+","+b);
            if (returned.truncated()) attributes.merge(SpanAttribute.CAPTURE_TRUNCATED, "returnValue", (a, b) -> a + "," + b);
            complete(context, completedSpan(context, descriptor, duration, thrown, returned.text(), attributes));
        } finally {
            recordExitOverhead(context, observerStarted);
            if (context.invocation != null) context.invocation.close();
        }
    }

    public static void recordEntryOverhead(SpanContext context, long started) {
        context.entryOverheadNanos = Math.max(0, System.nanoTime() - started);
        context.traceSampling.addObserverNanos(context.entryOverheadNanos);
        OverheadController.recordAdvice(context.entryOverheadNanos);
        context.observerNanosAtStart = context.traceSampling.observerNanos();
    }

    public static void recordExitOverhead(SpanContext context, long started) {
        long elapsed=Math.max(0,System.nanoTime()-started);
        context.traceSampling.addObserverNanos(elapsed);OverheadController.recordAdvice(elapsed);
    }

    public static long durationNanos(SpanContext context, long ended) {
        long nestedObserver = Math.max(0, context.traceSampling.observerNanos() - context.observerNanosAtStart);
        return Math.max(0, ended - context.startNanos - nestedObserver);
    }

    public static String nextId() {
        long value;
        do { value = java.util.concurrent.ThreadLocalRandom.current().nextLong(); } while (value == 0);
        return java.util.HexFormat.of().toHexDigits(value);
    }
    public static String nextTraceId() { return nextId() + nextId(); }

    public static void removeContext(SpanContext context) {
        AgentDiagnostics.exit(context);
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
        attributes.put("agent.span.elapsed_nanos", Long.toString(duration
                + Math.max(0, context.traceSampling.observerNanos() - context.observerNanosAtStart)));
        attributes.put(SpanAttribute.OBSERVER_EXCLUDED_NANOS, Long.toString(context.entryOverheadNanos
                + Math.max(0, context.traceSampling.observerNanos() - context.observerNanosAtStart)));
        attributes.put(SpanAttribute.SAMPLING_RATE, Integer.toString(context.traceSampling.headRatePercent()));
        attributes.put("agent.overhead.mode",OverheadController.mode());
        attributes.put("thread.id",Long.toString(context.threadId));
        attributes.put("agent.local.root_span_id",context.traceSampling.localRootId());
        if(!context.traceSampling.traceState().isBlank())attributes.put("w3c.tracestate",context.traceSampling.traceState());
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
        try {
            SpanExporter exporter = EXPORTER;
            boolean rootSpan = context.depth == 0;
            if (exporter == null) {
                if (rootSpan) context.traceSampling.discard();
                return;
            }
            long completedCount=context.traceSampling.nextCompletedSpan();
            if(rootSpan){var attrs=new LinkedHashMap<>(span.attributes());attrs.put("agent.trace.completed_spans",Long.toString(completedCount));span=new SpanData(span.traceId(),span.spanId(),span.parentSpanId(),span.className(),span.methodName(),span.signature(),span.threadName(),span.depth(),span.startedAtEpochMillis(),span.durationNanos(),span.status(),span.arguments(),span.returnValue(),span.stackTrace(),span.kind(),attrs,span.statusDescription(),span.events());}
            span = SpanPayloadLimits.limit(span);
            if (context.traceSampling.isHeadSampled()) {
                exporter.export(context.traceSampling.annotate(span, "head"));
                return;
            }
            for (SpanData completed : context.traceSampling.complete(span, rootSpan)) exporter.export(completed);
        } finally {
            if (context.invocation != null) context.invocation.close();
        }
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
        int effectiveRate=OverheadController.samplingRate(SAMPLING_RATE_PERCENT);
        return new TraceSamplingState(headSampled(effectiveRate), nanosFromMillis(SLOW_TRACE_THRESHOLD_MILLIS),
                TAIL_MAX_BUFFERED_SPANS, TAIL_MAX_BUFFERED_BYTES, TAIL_OVERFLOW_POLICY, effectiveRate);
    }

    private static boolean headSampled(int rate) {
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

    public static String formatArgs(Object[] args) { return ValueCapture.arguments(args).text(); }

    public static String safeFormatArgs(Object[] args) { return formatArgs(args); }

    public static String formatValue(Object value) { return ValueCapture.capture(value).text(); }

    public static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "…" : value;
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
        for (String p : EXCL_PACKAGE_GLOBS) { if (com.agentmonitor.model.config.PackagePatternMatcher.matchesClassName(p,className)) return false; }
        for (String r : EXCL_REGEX_FILTERS) { if (!r.isEmpty() && className.matches(r))      return false; }
        return true;
    }
}
