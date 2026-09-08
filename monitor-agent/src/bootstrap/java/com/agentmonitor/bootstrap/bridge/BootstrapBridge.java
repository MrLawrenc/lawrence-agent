package com.agentmonitor.bootstrap.bridge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only Agent API made visible to instrumented application classes.
 *
 * <p>This class is packaged in a small, dependency-free JAR and appended to the Bootstrap class
 * loader. Its ABI deliberately uses JDK types and opaque {@link InvocationToken}s only; Agent
 * core classes, Byte Buddy and Netty must never leak into an application's class loader.</p>
 *
 * <p>The Bridge also owns the cross-class-loader lifecycle. A thin entry reserves a
 * {@link Startup} before it creates an isolated core loader. This makes reattach and STOP
 * linearizable even while that core is still installing its transformer.</p>
 */
public final class BootstrapBridge {

    /** Increment whenever an already-loaded bridge cannot safely serve a newer core. */
    private static final int API_VERSION = 4;
    private static final long STARTUP_WAIT_TIMEOUT_MILLIS = 10_000;
    private static final Object LIFECYCLE_LOCK = new Object();

    /** Volatile keeps the hot path lock-free while lifecycle changes remain serialized. */
    private static volatile Runtime activeRuntime;
    /** Guarded by {@link #LIFECYCLE_LOCK}; only the detach transaction finally releases it. */
    private static Runtime detachingRuntime;
    /** Guarded by {@link #LIFECYCLE_LOCK}; reserves a generation from entry through activation. */
    private static Startup startingStartup;
    /** Guarded by {@link #LIFECYCLE_LOCK}. */
    private static long nextStartupId;

    private BootstrapBridge() { }

    public static int apiVersion() {
        return API_VERSION;
    }

    /**
     * Reserves the next isolated core generation and restores any prior generation first.
     *
     * <p>This is intentionally called by the thin entry before it creates the core loader or
     * opens an exporter connection. A concurrent entry waits for the in-flight generation rather
     * than loading a second core with no globally visible owner.</p>
     */
    public static Startup beginStartup() {
        Runtime previous;
        Startup startup;
        synchronized (LIFECYCLE_LOCK) {
            waitForIdleLocked();
            startup = new Startup(++nextStartupId);
            startingStartup = startup;
            previous = activeRuntime;
            if (previous == null) return startup;
            activeRuntime = null;
            detachingRuntime = previous;
        }

        DetachStatus result = detachRuntime(previous, null, null);
        if (result.restored()) return startup;

        synchronized (LIFECYCLE_LOCK) {
            if (startingStartup == startup) startingStartup = null;
            // detachRuntime restores the prior runtime on failure. Keep the reservation-free
            // state visible before the caller receives its error.
            signalLifecycleChangeLocked();
        }
        throw new IllegalStateException("无法还原上一轮监控的字节码: " + result.message());
    }

    /** Binds a prepared core to its reservation, but does not yet route application calls to it. */
    public static void bindStartup(Startup startup, Runtime runtime) {
        Objects.requireNonNull(startup, "startup");
        Objects.requireNonNull(runtime, "runtime");
        synchronized (LIFECYCLE_LOCK) {
            requireCurrentStartupLocked(startup);
            if (startup.stopping) {
                throw new IllegalStateException("Agent startup has already been stopped");
            }
            if (startup.runtime != null && startup.runtime != runtime) {
                throw new IllegalStateException("Agent startup is already bound to another runtime");
            }
            startup.runtime = runtime;
            signalLifecycleChangeLocked();
        }
    }

    /**
     * Publishes a completely installed core. Returns false when a STOP/reattach won the race;
     * the caller must reset any transformer it has just installed instead of becoming active.
     */
    public static boolean activateStartup(Startup startup, Runtime runtime) {
        Objects.requireNonNull(startup, "startup");
        Objects.requireNonNull(runtime, "runtime");
        synchronized (LIFECYCLE_LOCK) {
            if (startingStartup != startup || startup.runtime != runtime || startup.stopping
                    || detachingRuntime == runtime) {
                return false;
            }
            activeRuntime = runtime;
            startingStartup = null;
            signalLifecycleChangeLocked();
            return true;
        }
    }

    /**
     * Aborts a startup whose core could not be handed off. A failed restore deliberately retains
     * its runtime in the Bridge so a later STOP/attach can retry without closing its loader.
     */
    public static DetachStatus abortStartup(Startup startup) {
        if (startup == null) return DetachStatus.success("没有待取消的 Agent 启动");
        Runtime runtime;
        synchronized (LIFECYCLE_LOCK) {
            if (startingStartup != startup) return DetachStatus.success("Agent 启动已结束");
            runtime = startup.runtime;
            if (runtime == null) {
                startingStartup = null;
                signalLifecycleChangeLocked();
                return DetachStatus.success("Agent 启动已取消");
            }
            if (detachingRuntime != null) {
                return DetachStatus.failed("上一轮字节码还原仍在进行");
            }
            startup.stopping = true;
            detachingRuntime = runtime;
        }
        return detachRuntime(runtime, startup, null);
    }

    /**
     * Stops an active or still-starting runtime. New method entries become no-ops immediately,
     * while old exits still route through their token to the original runtime.
     */
    public static DetachStatus detachActive() {
        return detachActive(null);
    }

    /**
     * Same as {@link #detachActive()}, with a lifecycle callback used by the control protocol.
     * A successful callback runs only after the Bridge has logically detached the generation, but
     * before the core closes the Netty channel that carries the acknowledgement.
     */
    public static DetachStatus detachActive(DetachListener listener) {
        Runtime runtime;
        Startup startup;
        synchronized (LIFECYCLE_LOCK) {
            if (detachingRuntime != null) {
                DetachStatus result = DetachStatus.failed("上一轮字节码还原仍在进行");
                notifyListener(listener, result);
                return result;
            }
            startup = startingStartup;
            if (startup != null) {
                runtime = startup.runtime;
                if (runtime == null) {
                    DetachStatus result = DetachStatus.failed("Agent 正在启动，尚未可安全停止");
                    notifyListener(listener, result);
                    return result;
                }
                startup.stopping = true;
            } else {
                runtime = activeRuntime;
                if (runtime == null) {
                    DetachStatus result = DetachStatus.success("没有活动的字节码增强");
                    notifyListener(listener, result);
                    return result;
                }
                activeRuntime = null;
            }
            detachingRuntime = runtime;
        }
        return detachRuntime(runtime, startup, listener);
    }

    /** True only after a generation has installed and been published to application calls. */
    public static boolean hasActiveRuntime() {
        return activeRuntime != null;
    }

    /**
     * Marks a core logically detached before it closes its exporter/loader. It deliberately does
     * not release {@code detachingRuntime}; only the bridge detach transaction's finally can do
     * that, preventing another attach from crossing the teardown boundary.
     */
    public static void runtimeDetachedBeforeClose(Runtime runtime) {
        if (runtime == null) return;
        synchronized (LIFECYCLE_LOCK) {
            markLogicallyDetachedLocked(runtime);
            signalLifecycleChangeLocked();
        }
    }

    /**
     * Reports an installation failure after the core has successfully reset any partial
     * transformer. A runtime that cannot be reset must remain registered for retry.
     */
    public static void startupFailed(Startup startup, Runtime runtime) {
        if (startup == null || runtime == null) return;
        synchronized (LIFECYCLE_LOCK) {
            if (detachingRuntime == runtime) return;
            if (startingStartup == startup && startup.runtime == runtime) startingStartup = null;
            if (activeRuntime == runtime) activeRuntime = null;
            signalLifecycleChangeLocked();
        }
    }

    /**
     * Compatibility helper for an internal caller that has already completed its own teardown.
     * New core code uses the ticketed methods above; this retains a safe API for shutdown paths.
     */
    public static void unregister(Runtime runtime) {
        runtimeDetachedBeforeClose(runtime);
    }

    /**
     * Compatibility helper for simple tests and legacy integrations. It is intentionally not
     * used by the isolated core because publication must go through a {@link Startup} ticket.
     */
    public static void register(Runtime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        synchronized (LIFECYCLE_LOCK) {
            if (startingStartup != null || detachingRuntime != null) {
                throw new IllegalStateException("an Agent lifecycle transition is already in progress");
            }
            if (activeRuntime != null && activeRuntime != runtime) {
                throw new IllegalStateException("an Agent runtime is already active");
            }
            activeRuntime = runtime;
            signalLifecycleChangeLocked();
        }
    }

    public static InvocationToken enterBusiness(String className, String methodName, Object[] arguments) {
        Runtime runtime = activeRuntime;
        if (runtime == null) return null;
        try {
            Object state = runtime.enterBusiness(className, methodName, arguments);
            return state == null ? null : new InvocationToken(runtime, state);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void exitBusiness(InvocationToken token, String className, String methodName,
                                    String descriptor, Throwable error, Object returnValue) {
        if (token == null) return;
        try {
            token.runtime.exitBusiness(token.state, className, methodName, descriptor, error, returnValue);
        } catch (Throwable ignored) {
            // Instrumentation must never alter application behaviour because telemetry failed.
        }
    }

    public static InvocationToken enterDependency(String className, String methodName,
                                                   Object target, Object[] arguments) {
        Runtime runtime = activeRuntime;
        if (runtime == null) return null;
        try {
            Object state = runtime.enterDependency(className, methodName, target, arguments);
            return state == null ? null : new InvocationToken(runtime, state);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void exitDependency(InvocationToken token, Object target, Object[] arguments,
                                      Throwable error) {
        if (token == null) return;
        try {
            token.runtime.exitDependency(token.state, target, arguments, error);
        } catch (Throwable ignored) {
            // Instrumentation must never alter application behaviour because telemetry failed.
        }
    }

    public static InvocationToken enterJdbc(String methodName, Object statement, Object[] arguments) {
        Runtime runtime = activeRuntime;
        if (runtime == null) return null;
        try {
            Object state = runtime.enterJdbc(methodName, statement, arguments);
            return state == null ? null : new InvocationToken(runtime, state);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void exitJdbc(InvocationToken token, Throwable error) {
        if (token == null) return;
        try {
            token.runtime.exitJdbc(token.state, error);
        } catch (Throwable ignored) {
            // Instrumentation must never alter application behaviour because telemetry failed.
        }
    }

    /** Binds a JDBC connection exit to the runtime generation that entered the method. */
    public static InvocationToken enterJdbcConnection() {
        Runtime runtime = activeRuntime;
        return runtime == null ? null : new InvocationToken(runtime, null);
    }

    public static void jdbcStatementCreated(InvocationToken token, String methodName, Object connection,
                                            Object[] arguments, Object statement, Throwable error) {
        if (token == null || error != null) return;
        try {
            token.runtime.jdbcStatementCreated(methodName, connection, arguments, statement);
        } catch (Throwable ignored) {
            // Instrumentation must never alter application behaviour because telemetry failed.
        }
    }

    private static DetachStatus detachRuntime(Runtime runtime, Startup startup, DetachListener listener) {
        AtomicBoolean successNotified = new AtomicBoolean(false);
        DetachListener beforeClose = result -> {
            DetachStatus normalized = normalize(result);
            if (!normalized.restored() || !successNotified.compareAndSet(false, true)) return;
            runtimeDetachedBeforeClose(runtime);
            notifyListener(listener, normalized);
        };
        DetachStatus result;
        try {
            result = normalize(runtime.detach(beforeClose));
        } catch (Throwable error) {
            result = DetachStatus.failed(describe(error));
        }

        synchronized (LIFECYCLE_LOCK) {
            if (result.restored()) {
                markLogicallyDetachedLocked(runtime);
                if (detachingRuntime == runtime) detachingRuntime = null;
            } else {
                restoreAfterFailedDetachLocked(runtime, startup);
            }
            signalLifecycleChangeLocked();
        }
        if (result.restored()) {
            if (successNotified.compareAndSet(false, true)) notifyListener(listener, result);
        } else {
            notifyListener(listener, result);
        }
        return result;
    }

    private static void restoreAfterFailedDetachLocked(Runtime runtime, Startup startup) {
        if (detachingRuntime == runtime) detachingRuntime = null;
        if (startingStartup != null && startingStartup.runtime == runtime) {
            startingStartup.stopping = false;
        } else if (activeRuntime == null) {
            activeRuntime = runtime;
        }
        // A reservation created by beginStartup has no runtime until the new core binds. It must
        // never be cleared or mistaken for the old runtime while its restore is in progress.
        if (startup != null && startingStartup == startup && startup.runtime == runtime) {
            startup.stopping = false;
        }
    }

    private static void markLogicallyDetachedLocked(Runtime runtime) {
        if (activeRuntime == runtime) activeRuntime = null;
        if (startingStartup != null && startingStartup.runtime == runtime) startingStartup = null;
    }

    private static DetachStatus normalize(DetachStatus result) {
        return result == null ? DetachStatus.failed("Agent runtime 未返回还原结果") : result;
    }

    private static void requireCurrentStartupLocked(Startup startup) {
        if (startingStartup != startup) {
            throw new IllegalStateException("Agent startup is no longer current");
        }
    }

    private static void waitForIdleLocked() {
        long deadline = System.nanoTime() + STARTUP_WAIT_TIMEOUT_MILLIS * 1_000_000L;
        while (startingStartup != null || detachingRuntime != null) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IllegalStateException("上一轮 Agent 启动或字节码还原未在超时时间内结束");
            }
            long millis = Math.max(1L, remainingNanos / 1_000_000L);
            try {
                LIFECYCLE_LOCK.wait(millis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待上一轮 Agent 生命周期结束时被中断", error);
            }
        }
    }

    private static void signalLifecycleChangeLocked() {
        LIFECYCLE_LOCK.notifyAll();
    }

    private static void notifyListener(DetachListener listener, DetachStatus result) {
        if (listener == null) return;
        try {
            listener.onDetached(result);
        } catch (Throwable ignored) {
            // A control-plane acknowledgement must not change bytecode lifecycle semantics.
        }
    }

    /** A reservation owned by one thin-entry invocation; opaque to application code. */
    public static final class Startup {
        private final long generation;
        private Runtime runtime;
        private boolean stopping;

        private Startup(long generation) {
            this.generation = generation;
        }

        @Override
        public String toString() {
            return "Startup{" + generation + '}';
        }
    }

    /** Callback implemented by the isolated Agent core. Keep this ABI JDK-only. */
    public interface Runtime {
        Object enterBusiness(String className, String methodName, Object[] arguments);

        void exitBusiness(Object state, String className, String methodName, String descriptor,
                          Throwable error, Object returnValue);

        Object enterDependency(String className, String methodName, Object target, Object[] arguments);

        void exitDependency(Object state, Object target, Object[] arguments, Throwable error);

        Object enterJdbc(String methodName, Object statement, Object[] arguments);

        void exitJdbc(Object state, Throwable error);

        void jdbcStatementCreated(String methodName, Object connection, Object[] arguments, Object statement);

        DetachStatus detach();

        /**
         * The core may invoke {@code listener} after bytecode reset but before it closes the
         * transport used for STOP acknowledgement. Legacy/simple implementations use the safe
         * default, which reports only after their detach method has returned.
         */
        default DetachStatus detach(DetachListener listener) {
            DetachStatus result = detach();
            if (listener != null) listener.onDetached(result);
            return result;
        }
    }

    /** JDK-only lifecycle acknowledgement callback. */
    @FunctionalInterface
    public interface DetachListener {
        void onDetached(DetachStatus result);
    }

    /**
     * A generation-bound, opaque advice state. An invocation that began before a reattach exits
     * through its original runtime instead of accidentally touching the new core class loader.
     */
    public static final class InvocationToken {
        private final Runtime runtime;
        private final Object state;

        private InvocationToken(Runtime runtime, Object state) {
            this.runtime = runtime;
            this.state = state;
        }
    }

    /**
     * STOP outcome across the Bootstrap/core boundary.
     *
     * <p>A reset and an output drain are deliberately distinct facts. A JVM can have safely
     * restored bytecode while a bounded final exporter flush times out; callers may start a new
     * monitor in that case, but must surface the incomplete tail output.</p>
     */
    public record DetachStatus(boolean restored, boolean outputDrained, String message,
                               OutputStatistics outputStatistics) {
        public DetachStatus {
            outputStatistics = outputStatistics == null ? OutputStatistics.unavailable() : outputStatistics;
        }

        public static DetachStatus success(String message) {
            return new DetachStatus(true, true, message == null ? "字节码已还原" : message,
                    OutputStatistics.unavailable());
        }

        public static DetachStatus success(String message, OutputStatistics outputStatistics) {
            return new DetachStatus(true, true, message == null ? "字节码已还原" : message, outputStatistics);
        }

        public static DetachStatus outputDrainFailed(String message) {
            return new DetachStatus(true, false, message == null || message.isBlank()
                    ? "字节码已还原，但尾部输出 drain/flush 未完成"
                    : message, OutputStatistics.unavailable());
        }

        public static DetachStatus outputDrainFailed(String message, OutputStatistics outputStatistics) {
            return new DetachStatus(true, false, message == null || message.isBlank()
                    ? "字节码已还原，但尾部输出 drain/flush 未完成"
                    : message, outputStatistics);
        }

        public static DetachStatus failed(String message) {
            return new DetachStatus(false, false, message == null || message.isBlank()
                    ? "字节码还原失败"
                    : message, OutputStatistics.unavailable());
        }

        public static DetachStatus failed(String message, OutputStatistics outputStatistics) {
            return new DetachStatus(false, false, message == null || message.isBlank()
                    ? "字节码还原失败"
                    : message, outputStatistics);
        }
    }

    /** JDK-only output counters that may safely cross the Bootstrap/core class-loader boundary. */
    public record OutputStatistics(boolean reported, long enqueuedSpans, long queueDroppedSpans,
                                   long deliveryDroppedSpans, long rejectedDestinations,
                                   long pendingSpans) {
        public OutputStatistics {
            enqueuedSpans = Math.max(0, enqueuedSpans);
            queueDroppedSpans = Math.max(0, queueDroppedSpans);
            deliveryDroppedSpans = Math.max(0, deliveryDroppedSpans);
            rejectedDestinations = Math.max(0, rejectedDestinations);
            pendingSpans = Math.max(0, pendingSpans);
        }

        public static OutputStatistics unavailable() {
            return new OutputStatistics(false, 0, 0, 0, 0, 0);
        }
    }

    private static String describe(Throwable error) {
        if (error == null) return "未知异常";
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
