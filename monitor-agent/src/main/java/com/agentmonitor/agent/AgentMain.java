package com.agentmonitor.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.agentmonitor.agent.config.AgentConfig;
import com.agentmonitor.agent.exporter.AsyncSpanExporter;
import com.agentmonitor.agent.exporter.ExporterDrainResult;
import com.agentmonitor.agent.exporter.SpanExporter;
import com.agentmonitor.agent.exporter.SpanExporterFactory;
import com.agentmonitor.agent.interceptor.MethodSpanAdvice;
import com.agentmonitor.agent.interceptor.BridgeMethodAdvice;
import com.agentmonitor.agent.interceptor.DependencySpanAdvice;
import com.agentmonitor.agent.interceptor.DependencySpanSupport;
import com.agentmonitor.agent.interceptor.JdbcConnectionAdvice;
import com.agentmonitor.agent.interceptor.JdbcSpanAdvice;
import com.agentmonitor.agent.interceptor.JdbcSpanSupport;
import com.agentmonitor.agent.lifecycle.DetachResult;
import com.agentmonitor.agent.log.AgentLog;
import com.agentmonitor.agent.log.EnhancementResultWriter;
import com.agentmonitor.agent.runtime.AgentRuntimeBridge;
import com.agentmonitor.bootstrap.bridge.BootstrapBridge;
import com.agentmonitor.model.config.PackagePatternMatcher;
import com.agentmonitor.model.output.ExporterStatistics;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

public class AgentMain {

    private static final long INSTALL_STOP_TIMEOUT_MILLIS = 3_000;
    private static final long STOP_EXPORTER_DRAIN_TIMEOUT_MILLIS = 3_000;
    private static final int RETRANSFORMATION_BATCH_SIZE = 64;
    private static final Set<String> ACCESSOR_METHOD_PATTERNS = Set.of("get*", "is*", "set*");
    private static final Set<String> ENTRY_CLASS_SUFFIXES = Set.of(
            "API", "Application", "Controller", "Resource", "Endpoint");

    private static volatile net.bytebuddy.agent.builder.ResettableClassFileTransformer transformer;
    private static volatile Instrumentation instrumentation;
    private static volatile Thread installThread;
    private static final Object DETACH_LOCK = new Object();
    private static final Object TRANSFORMER_LOCK = new Object();
    private static final AtomicBoolean STOP_REQUESTED = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean RUNTIME_LOADER_CLOSED = new AtomicBoolean(false);
    private static final AgentRuntimeBridge RUNTIME_BRIDGE = new AgentRuntimeBridge();
    private static volatile Thread shutdownHook;
    private static volatile BootstrapBridge.Startup startup;

    public static void premain(String agentArgs, Instrumentation inst) throws Exception {
        agentmain(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        BootstrapBridge.Startup ticket = BootstrapBridge.beginStartup();
        boolean handedOff = false;
        try {
            agentmain(agentArgs, inst, ticket);
            handedOff = true;
        } finally {
            if (!handedOff) BootstrapBridge.abortStartup(ticket);
        }
    }

    /** Invoked reflectively by the thin entry after it reserved a Bootstrap lifecycle ticket. */
    public static void agentmain(String agentArgs, Instrumentation inst, BootstrapBridge.Startup ticket)
            throws Exception {
        if (BootstrapBridge.apiVersion() != 4) {
            throw new IllegalStateException("unsupported Bootstrap bridge API: " + BootstrapBridge.apiVersion());
        }
        if (ticket == null) throw new IllegalArgumentException("Bootstrap startup ticket is required");
        instrumentation = inst;
        startup = ticket;
        detachLegacyAgentRuntimes(inst);
        STOP_REQUESTED.set(false);

        AgentConfig config = AgentConfig.parse(agentArgs);
        AgentLog.configure(config.getAgentLogDirectory());
        EnhancementResultWriter enhancementResultWriter =
                new EnhancementResultWriter(config.getAgentLogDirectory());
        logInfo("Bootstrap bridge installed; Agent core is isolated from application class loaders");

        // ── Positive scope: package prefixes and exact class-name whitelist ──
        String[] rawPkgs = config.getPackagePrefixes();
        List<String> pkgPrefixList = new ArrayList<>();
        for (String p : rawPkgs) {
            String t = p.trim();
            if (!t.isEmpty()) pkgPrefixList.add(t.endsWith(".") ? t : t + ".");
        }
        List<String> includeClassList = new ArrayList<>();
        for (String className : config.getIncludeClasses()) {
            String trimmed = className.trim();
            if (!trimmed.isEmpty() && !includeClassList.contains(trimmed)) includeClassList.add(trimmed);
        }
        logInfo("PKG_FILTERS: " + pkgPrefixList);
        logInfo("CLS_FILTERS: " + includeClassList);
        if (pkgPrefixList.isEmpty() && includeClassList.isEmpty()) {
            logError("install aborted: empty positive scope");
            AgentLog.close();
            throw new IllegalArgumentException("监控包或类白名单不能为空");
        }

        SpanExporter exporter = new SpanExporterFactory().create(config);
        if (exporter == null) {
            logError("install aborted: no exporter is available");
            AgentLog.close();
            throw new IllegalStateException("没有可用的 Trace 导出器");
        }
        MethodSpanAdvice.EXPORTER = exporter;
        MethodSpanAdvice.ACTIVE = false;
        MethodSpanAdvice.CAPTURE_ARGUMENTS = config.isCaptureArguments();
        MethodSpanAdvice.CAPTURE_RETURN_VALUE = config.isCaptureReturnValue();
        MethodSpanAdvice.configureTraceSampling(config.getSamplingRatePercent(),
                config.getSlowThresholdMillis(), config.getTailMaxBufferedSpans(),
                config.getTailMaxBufferedSizeMb(), config.getTailOverflowPolicy());
        MethodSpanAdvice.PKG_FILTERS = pkgPrefixList.toArray(new String[0]);
        MethodSpanAdvice.CLS_FILTERS = includeClassList.toArray(new String[0]);
        JdbcSpanSupport.ENABLED = config.isJdbcEnabled();
        JdbcSpanSupport.CAPTURE_PARAMETERS = config.isCaptureSqlParameters();
        DependencySpanSupport.HTTP_ENABLED = config.isHttpEnabled();
        logInfo("trace capture policy: head sampling=" + config.getSamplingRatePercent()
                + "%, tail promotion=root >= " + config.getSlowThresholdMillis()
                + "ms or any error; tail buffer=" + config.getTailMaxBufferedSpans()
                + " spans/" + config.getTailMaxBufferedSizeMb() + "MiB, overflow="
                + config.getTailOverflowPolicy().configValue());

        // ── EXCL_* filters: parse structured conditions ──
        List<String> exclPkg   = new ArrayList<>();
        List<String> exclCls   = new ArrayList<>();
        List<String> exclRegex = new ArrayList<>();
        for (String cond : config.getExcludeConditions()) {
            String c = cond.trim();
            if (c.startsWith("pkg:")) {
                String v = c.substring(4).trim();
                if (!v.isEmpty()) {
                    if (PackagePatternMatcher.hasWildcard(v)) exclRegex.add(PackagePatternMatcher.toRegex(v));
                    else exclPkg.add(v.endsWith(".") ? v : v + ".");
                }
            } else if (c.startsWith("cls:")) {
                String v = c.substring(4).trim();
                if (!v.isEmpty()) {
                    if (PackagePatternMatcher.hasWildcard(v)) exclRegex.add(PackagePatternMatcher.toRegex(v));
                    else exclCls.add(v);
                }
            }
        }
        MethodSpanAdvice.EXCL_PKG_FILTERS   = exclPkg.toArray(new String[0]);
        MethodSpanAdvice.EXCL_CLS_FILTERS   = exclCls.toArray(new String[0]);
        MethodSpanAdvice.EXCL_REGEX_FILTERS = exclRegex.toArray(new String[0]);
        String[] methodExcludes = config.getExcludeMethods();
        logInfo("METHOD_EXCLUDES: " + java.util.Arrays.toString(methodExcludes));

        // ── Byte Buddy type matcher: OR across target packages and exact target classes ──
        net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.NamedElement> typeMatcher =
                ElementMatchers.none();
        for (String prefix : pkgPrefixList) {
            String base = prefix.substring(0, prefix.length() - 1);
            typeMatcher = typeMatcher.or(ElementMatchers.nameStartsWith(prefix).or(ElementMatchers.named(base)));
        }
        for (String className : includeClassList) {
            typeMatcher = typeMatcher.or(ElementMatchers.named(className));
        }
        for (String cond : config.getExcludeConditions()) {
            String c = cond.trim();
            if (c.startsWith("pkg:")) {
                String v = c.substring(4).trim();
                if (!v.isEmpty()) {
                    if (PackagePatternMatcher.hasWildcard(v)) {
                        typeMatcher = typeMatcher.and(ElementMatchers.not(
                                // The class's full name includes its package, so this glob also
                                // excludes every class below a matched package.  The UI evaluates
                                // the same expression against packageName only.
                                ElementMatchers.nameMatches(PackagePatternMatcher.toRegex(v))));
                    } else {
                        String pref = v.endsWith(".") ? v : v + ".";
                        typeMatcher = typeMatcher.and(ElementMatchers.not(
                                ElementMatchers.named(v).or(ElementMatchers.nameStartsWith(pref))));
                    }
                }
            } else if (c.startsWith("cls:")) {
                String v = c.substring(4).trim();
                if (!v.isEmpty()) {
                    if (PackagePatternMatcher.hasWildcard(v)) {
                        typeMatcher = typeMatcher.and(ElementMatchers.not(
                                ElementMatchers.nameMatches(PackagePatternMatcher.toRegex(v))));
                    } else {
                        typeMatcher = typeMatcher.and(ElementMatchers.not(ElementMatchers.named(v)));
                    }
                }
            }
        }
        final net.bytebuddy.matcher.ElementMatcher.Junction<net.bytebuddy.description.NamedElement> finalMatcher = typeMatcher;
        // Match the standard Servlet interface hierarchy, not a framework base class.  This
        // covers direct Servlet implementations and HttpServlet subclasses for both namespace
        // generations without linking the Agent to either servlet API at compile time.
        final net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> servletMatcher =
                ElementMatchers.hasSuperType(ElementMatchers.named("jakarta.servlet.Servlet"))
                        .or(ElementMatchers.hasSuperType(ElementMatchers.named("javax.servlet.Servlet")));
        // Keep Servlet classes out of business-method Advice completely.  Besides ensuring a
        // single HTTP root, this prevents framework type checks from being inlined into every
        // selected business method and therefore cannot affect application return values.
        final ElementMatcher<TypeDescription> businessTypeMatcher = typeDescription ->
                finalMatcher.matches(typeDescription) && !servletMatcher.matches(typeDescription);
        // JDBC is matched by the standard interfaces rather than by a framework or a finite
        // list of driver class names.  This covers pool proxies and all compliant drivers while
        // keeping the collection boundary at Connection/Statement only.
        final net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> jdbcStatementMatcher =
                ElementMatchers.isSubTypeOf(java.sql.Statement.class);
        final net.bytebuddy.matcher.ElementMatcher.Junction<TypeDescription> jdbcConnectionMatcher =
                ElementMatchers.isSubTypeOf(java.sql.Connection.class);
        ElementMatcher.Junction<MethodDescription> methodMatcher = ElementMatchers.isMethod()
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isNative()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                .and(ElementMatchers.not(new ExcludedMethodMatcher(methodExcludes)));
        ElementMatcher.Junction<MethodDescription> servletMethodMatcher = ElementMatchers.isMethod()
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isNative()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                .and(ElementMatchers.named("service"));
        ElementMatcher.Junction<MethodDescription> jdbcStatementMethodMatcher = ElementMatchers.isMethod()
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isNative()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                .and(ElementMatchers.named("execute")
                        .or(ElementMatchers.named("executeQuery"))
                        .or(ElementMatchers.named("executeUpdate"))
                        .or(ElementMatchers.named("executeLargeUpdate"))
                        .or(ElementMatchers.named("executeBatch"))
                        .or(ElementMatchers.named("executeLargeBatch"))
                        .or(ElementMatchers.named("addBatch"))
                        .or(ElementMatchers.named("clearParameters"))
                        .or(ElementMatchers.named("close"))
                        .or(ElementMatchers.nameStartsWith("set")
                                .and(ElementMatchers.takesArgument(0, int.class))
                                .and(ElementMatchers.takesArgument(1, ElementMatchers.any()))));
        ElementMatcher.Junction<MethodDescription> jdbcConnectionMethodMatcher = ElementMatchers.isMethod()
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isNative()))
                .and(ElementMatchers.not(ElementMatchers.isSynthetic()))
                .and(ElementMatchers.named("prepareStatement")
                        .or(ElementMatchers.named("prepareCall"))
                                .or(ElementMatchers.named("createStatement")));
        TransformationDiagnostics transformationDiagnostics = new TransformationDiagnostics(
                enhancementResultWriter, businessTypeMatcher, servletMatcher, jdbcStatementMatcher,
                jdbcConnectionMatcher, methodMatcher, servletMethodMatcher, jdbcStatementMethodMatcher,
                jdbcConnectionMethodMatcher);
        RetransformationDiagnostics retransformationDiagnostics =
                new RetransformationDiagnostics(transformationDiagnostics);
        AtomicReference<net.bytebuddy.agent.builder.ResettableClassFileTransformer> installingTransformer =
                new AtomicReference<>();
        Thread installer = new Thread(() -> {
            net.bytebuddy.agent.builder.ResettableClassFileTransformer installedTransformer = null;
            try {
                installedTransformer = new AgentBuilder.Default()
                        // The transformed bytecode invokes only BootstrapBridge.  Named target
                        // modules must read that Bootstrap unnamed module as well.
                        .assureReadEdgeTo(inst, BootstrapBridge.class)
                        .disableClassFormatChanges()
                        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                        // A single class rejected by the JVM must not discard a whole selected
                        // package.  Retry a failed batch in smaller pieces until only the bad
                        // class is skipped.
                        .with(AgentBuilder.RedefinitionStrategy.BatchAllocator.ForFixedSize
                                .ofSize(RETRANSFORMATION_BATCH_SIZE))
                        .with(new AgentBuilder.RedefinitionStrategy.Listener.Compound(
                                AgentBuilder.RedefinitionStrategy.Listener.BatchReallocator.splitting(),
                                retransformationDiagnostics))
                        // installOn can throw after Byte Buddy has registered the transformer. Keep
                        // that instance so the failure path can reset any partially transformed
                        // classes rather than closing a still-live isolated core.
                        .with(new AgentBuilder.InstallationListener.Adapter() {
                            @Override
                            public void onBeforeInstall(Instrumentation ignored,
                                    net.bytebuddy.agent.builder.ResettableClassFileTransformer candidate) {
                                installingTransformer.compareAndSet(null, candidate);
                            }
                        })
                        .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                        .ignore(ElementMatchers.nameStartsWith("com.agentmonitor.")
                                .or(ElementMatchers.nameStartsWith("java."))
                                .or(ElementMatchers.nameStartsWith("sun."))
                                .or(ElementMatchers.nameStartsWith("jdk."))
                                .or(ElementMatchers.nameStartsWith("com.sun.")))
                        .with(transformationDiagnostics)
                        // Business methods and external-dependency boundaries have distinct
                        // advice.  Applying both to their union lets framework internals take
                        // part in the business stack and can split an HTTP trace.
                        .type(businessTypeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                builder.visit(Advice.to(BridgeMethodAdvice.class)
                                        .on(methodMatcher)))
                        .type(servletMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                builder.visit(Advice.to(DependencySpanAdvice.class)
                                        .on(servletMethodMatcher)))
                        .type(jdbcStatementMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                builder.visit(Advice.to(JdbcSpanAdvice.class)
                                        .on(jdbcStatementMethodMatcher)))
                        .type(jdbcConnectionMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                builder.visit(Advice.to(JdbcConnectionAdvice.class)
                                        .on(jdbcConnectionMethodMatcher)))
                        .installOn(inst);
                boolean stopRequested;
                synchronized (TRANSFORMER_LOCK) {
                    transformer = installedTransformer;
                    stopRequested = STOP_REQUESTED.get();
                }
                boolean active = false;
                if (!stopRequested) {
                    // Advice is still unreachable until the Bridge publishes this generation.
                    MethodSpanAdvice.ACTIVE = true;
                    active = BootstrapBridge.activateStartup(ticket, RUNTIME_BRIDGE);
                    if (!active) {
                        MethodSpanAdvice.ACTIVE = false;
                        STOP_REQUESTED.set(true);
                    }
                }
                if (!active) {
                    DetachResult cleanupResult = resetTransformer(installedTransformer);
                    if (!cleanupResult.restored()) {
                        logError("delayed retransform failed: " + cleanupResult.message());
                    } else {
                        finishDelayedDetach();
                    }
                } else {
                    enhancementResultWriter.complete();
                    exporter.ready();
                    logInfo("instrumentation ready.");
                    logInfo(transformationDiagnostics.summary());
                }
            } catch (Throwable e) {
                logError("install failed: " + e.getMessage());
                cleanupFailedInstall(installedTransformer != null ? installedTransformer : installingTransformer.get(),
                        ticket);
            } finally {
                synchronized (TRANSFORMER_LOCK) {
                    if (installThread == Thread.currentThread()) installThread = null;
                }
            }
        }, "agent-monitor-install");
        installer.setDaemon(true);
        installer.setContextClassLoader(AgentMain.class.getClassLoader());
        try {
            BootstrapBridge.bindStartup(ticket, RUNTIME_BRIDGE);
        } catch (RuntimeException error) {
            closeExporter();
            throw error;
        }
        // Publish the not-yet-started thread before the exporter opens its control connection.
        // detach() then either joins it or marks STOP_REQUESTED while it is still NEW, and this
        // method will refuse to start it afterwards.
        synchronized (TRANSFORMER_LOCK) {
            installThread = installer;
        }
        registerShutdownHook();
        if (!exporter.start()) {
            logError("install aborted: exporter failed to start");
            DetachResult cleanupResult = detach();
            if (!cleanupResult.restored()) {
                throw new IllegalStateException("导出器启动失败且无法还原字节码: " + cleanupResult.message());
            }
            throw new IllegalStateException("没有可用的 Trace 导出器");
        }
        if (!startInstaller(installer)) {
            logInfo("installation was stopped before the transformer started");
        }
    }

    /**
     * Disables advice, resets the transformer using retransformation, and then closes outputs.
     * The callback runs after bytecode restoration but before the Netty output is closed, so a
     * Collector can receive a reliable lifecycle acknowledgement.
     */
    public static DetachResult detach() {
        return detach(null);
    }

    public static DetachResult detach(Consumer<DetachResult> beforeExporterClose) {
        synchronized (DETACH_LOCK) {
            DetachResult restoredResult = restoreClasses();
            if (restoredResult.restored()) {
                // Admission is closed only after reset succeeds. Any invocation that was already
                // in flight can still attempt to export, but is counted as a bounded-stop drop
                // once the async exporter establishes this finite queue boundary.
                DetachResult result = combineWithOutputDrain(restoredResult, drainExporter());
                // The Bridge becomes terminal before STOPPED is written, but retains its detach
                // gate until the outer transaction returns. A fast reattach therefore cannot
                // cross the exporter/loader teardown boundary.
                BootstrapBridge.runtimeDetachedBeforeClose(RUNTIME_BRIDGE);
                notifyDetachResult(beforeExporterClose, result);
                disposeAfterSuccessfulDetach();
                return result;
            } else {
                DetachResult result = restoredResult.withExporterStatistics(exporterStatistics(null));
                notifyDetachResult(beforeExporterClose, result);
                logError("Agent runtime remains available for a later restore retry: " + result.message());
                return result;
            }
        }
    }

    private static DetachResult restoreClasses() {
        STOP_REQUESTED.set(true);
        MethodSpanAdvice.ACTIVE            = false;
        MethodSpanAdvice.PKG_FILTERS       = new String[0];
        MethodSpanAdvice.CLS_FILTERS       = new String[0];
        MethodSpanAdvice.EXCL_PKG_FILTERS  = new String[0];
        MethodSpanAdvice.EXCL_CLS_FILTERS  = new String[0];
        MethodSpanAdvice.EXCL_REGEX_FILTERS = new String[0];
        MethodSpanAdvice.CAPTURE_ARGUMENTS = true;
        MethodSpanAdvice.CAPTURE_RETURN_VALUE = true;
        JdbcSpanSupport.CAPTURE_PARAMETERS = true;
        Thread it;
        synchronized (TRANSFORMER_LOCK) {
            it = installThread;
        }
        if (it != null && it.isAlive()) {
            try {
                it.join(INSTALL_STOP_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DetachResult.failed("等待插桩任务停止时被中断");
            }
            if (it.isAlive()) return DetachResult.failed("插桩任务未能在超时时间内停止");
        }

        net.bytebuddy.agent.builder.ResettableClassFileTransformer activeTransformer;
        synchronized (TRANSFORMER_LOCK) {
            activeTransformer = transformer;
        }
        if (activeTransformer == null) return DetachResult.noActiveTransformer();
        if (instrumentation == null) return DetachResult.failed("Instrumentation 不可用，无法还原字节码");

        return resetTransformer(activeTransformer);
    }

    private static DetachResult resetTransformer(
            net.bytebuddy.agent.builder.ResettableClassFileTransformer activeTransformer) {
        if (instrumentation == null) return DetachResult.failed("Instrumentation 不可用，无法还原字节码");
        try {
            boolean reset = activeTransformer.reset(instrumentation,
                    AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            if (!reset) return DetachResult.failed("JVM 拒绝重新转换已增强的类");
            synchronized (TRANSFORMER_LOCK) {
                if (transformer == activeTransformer) transformer = null;
            }
            return DetachResult.success();
        } catch (Throwable error) {
            String detail = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            logError("retransform failed: " + detail);
            return DetachResult.failed(detail);
        }
    }

    /** Starts a pre-published installer only when a STOP did not arrive through the exporter. */
    private static boolean startInstaller(Thread installer) {
        synchronized (TRANSFORMER_LOCK) {
            if (STOP_REQUESTED.get() || RUNTIME_LOADER_CLOSED.get() || installThread != installer) return false;
            installer.start();
            return true;
        }
    }

    private static void notifyDetachResult(Consumer<DetachResult> callback, DetachResult result) {
        if (callback == null) return;
        try {
            callback.accept(result);
        } catch (Exception e) {
            logError("detach acknowledgement failed: " + e.getMessage());
        }
    }

    private static void closeExporter() {
        SpanExporter exporter = MethodSpanAdvice.EXPORTER;
        MethodSpanAdvice.EXPORTER = null;
        if (exporter != null) {
            exporter.close();
            if (exporter instanceof AsyncSpanExporter asyncExporter) {
                logInfo("stopping exporter; dropped spans=" + asyncExporter.droppedCount()
                        + ", rejected destinations=" + asyncExporter.rejectedDestinationCount());
            }
        }
        AgentLog.close();
    }

    /**
     * Establishes the second STOP phase after successful retransformation. Async exporters first
     * stop admission and drain their bounded queue; every leaf then performs its own flush
     * barrier. The Netty leaf keeps its control socket open solely for the eventual ACK.
     */
    private static ExporterDrainResult drainExporter() {
        SpanExporter exporter = MethodSpanAdvice.EXPORTER;
        if (exporter == null) return ExporterDrainResult.success();
        try {
            ExporterDrainResult result = exporter.drain(STOP_EXPORTER_DRAIN_TIMEOUT_MILLIS);
            return result == null
                    ? ExporterDrainResult.failed(0, 0, 0, "导出器未返回 drain 结果")
                    : result;
        } catch (Throwable error) {
            String detail = error.getClass().getSimpleName()
                    + (error.getMessage() == null || error.getMessage().isBlank()
                    ? "" : ": " + error.getMessage());
            return ExporterDrainResult.failed(0, 0, 0, detail);
        }
    }

    private static DetachResult combineWithOutputDrain(DetachResult restoredResult,
                                                        ExporterDrainResult drainResult) {
        String message = restoredResult.message() + "；" + drainResult.summary();
        ExporterStatistics statistics = exporterStatistics(drainResult);
        if (drainResult.drained()) {
            logInfo("stop output drain completed: " + drainResult.summary());
            return new DetachResult(true, true, message, statistics);
        }
        logError("stop output drain incomplete: " + drainResult.summary());
        return DetachResult.outputDrainFailed(message).withExporterStatistics(statistics);
    }

    private static ExporterStatistics exporterStatistics(ExporterDrainResult drainResult) {
        SpanExporter exporter = MethodSpanAdvice.EXPORTER;
        if (!(exporter instanceof AsyncSpanExporter asyncExporter)) return ExporterStatistics.unavailable();
        ExporterStatistics snapshot = asyncExporter.statistics();
        if (drainResult == null) return snapshot;
        return new ExporterStatistics(true, snapshot.enqueuedSpans(), snapshot.queueDroppedSpans(),
                snapshot.deliveryDroppedSpans(),
                snapshot.rejectedDestinations() + drainResult.rejectedDestinations(),
                Math.max(snapshot.pendingSpans(), drainResult.pendingSpans()));
    }

    /** Best-effort migration from the previous revisioned, full-Bootstrap Agent packaging. */
    private static void detachLegacyAgentRuntimes(Instrumentation inst) {
        for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
            if (!isLegacyAgentMain(loadedClass)) continue;
            try {
                Method detach = loadedClass.getMethod("detach");
                Object result = detach.invoke(null);
                if (result != null) {
                    Method restored = result.getClass().getMethod("restored");
                    if (!Boolean.TRUE.equals(restored.invoke(result))) {
                        Method message = result.getClass().getMethod("message");
                        throw new IllegalStateException(String.valueOf(message.invoke(result)));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot restore previous Agent runtime "
                        + loadedClass.getName(), e);
            }
        }
    }

    private static boolean isLegacyAgentMain(Class<?> candidate) {
        if (candidate == null || candidate == AgentMain.class) return false;
        String name = candidate.getName();
        String legacyMain = "com.agentmonitor" + ".agent.AgentMain";
        String runtimePrefix = "com.agentmonitor" + ".runtime.r";
        if (name.startsWith(runtimePrefix) && name.endsWith(".AgentMain")) return true;
        // New isolated cores intentionally reuse the historical FQN but are loaded by the
        // private AgentRuntimeClassLoader. Only the previous fat Agent was system-loaded.
        return legacyMain.equals(name) && candidate.getClassLoader() == ClassLoader.getSystemClassLoader();
    }

    private static void registerShutdownHook() {
        if (RUNTIME_LOADER_CLOSED.get()) return;
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) return;
        Thread hook = new Thread(BootstrapBridge::detachActive, "agent-monitor-shutdown");
        hook.setContextClassLoader(AgentMain.class.getClassLoader());
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook = hook;
        } catch (IllegalStateException error) {
            SHUTDOWN_HOOK_REGISTERED.set(false);
        }
    }

    private static void unregisterShutdownHook() {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook == null || hook == Thread.currentThread()) return;
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down; retaining this hook no longer matters.
        } finally {
            SHUTDOWN_HOOK_REGISTERED.set(false);
        }
    }

    private static void cleanupFailedInstall(
            net.bytebuddy.agent.builder.ResettableClassFileTransformer installedTransformer,
            BootstrapBridge.Startup ticket) {
        MethodSpanAdvice.ACTIVE = false;
        DetachResult restoreResult = installedTransformer == null
                ? DetachResult.success()
                : resetTransformer(installedTransformer);
        if (!restoreResult.restored()) {
            logError("failed install left a transformer available for retry: " + restoreResult.message());
            return;
        }
        synchronized (DETACH_LOCK) {
            BootstrapBridge.runtimeDetachedBeforeClose(RUNTIME_BRIDGE);
            disposeAfterSuccessfulDetach();
        }
        BootstrapBridge.startupFailed(ticket, RUNTIME_BRIDGE);
    }

    /** Completes a stop that originally timed out while the installation thread was still running. */
    private static void finishDelayedDetach() {
        Thread finisher = new Thread(() -> {
            synchronized (DETACH_LOCK) {
                if (!STOP_REQUESTED.get()) return;
                BootstrapBridge.runtimeDetachedBeforeClose(RUNTIME_BRIDGE);
                ExporterDrainResult drainResult = drainExporter();
                if (!drainResult.drained()) {
                    logError("delayed stop output drain incomplete: " + drainResult.summary());
                }
                disposeAfterSuccessfulDetach();
            }
        }, "agent-monitor-delayed-cleanup");
        finisher.setDaemon(true);
        finisher.setContextClassLoader(AgentMain.class.getClassLoader());
        finisher.start();
    }

    private static void disposeAfterSuccessfulDetach() {
        closeExporter();
        unregisterShutdownHook();
        closeRuntimeClassLoader();
    }

    /** Releases the isolated core JAR after its transformer, callbacks and background threads stop. */
    private static void closeRuntimeClassLoader() {
        if (!RUNTIME_LOADER_CLOSED.compareAndSet(false, true)) return;
        ClassLoader loader = AgentMain.class.getClassLoader();
        if (!(loader instanceof URLClassLoader urlClassLoader)) return;
        try {
            urlClassLoader.close();
        } catch (Exception ignored) {
            // Closing only releases JAR handles; it must not alter the target application's state.
        }
    }

    private static void logInfo(String message) {
        AgentLog.info("[agent-monitor] " + message);
    }

    private static void logError(String message) {
        AgentLog.error("[agent-monitor] " + message);
    }

    private static class ExcludedMethodMatcher implements ElementMatcher<MethodDescription> {
        private final String[] patterns;

        ExcludedMethodMatcher(String[] patterns) {
            this.patterns = patterns == null ? new String[0] : patterns;
        }

        @Override
        public boolean matches(MethodDescription target) {
            if (target == null || patterns.length == 0) return false;
            String methodName = target.getActualName();
            String className = target.getDeclaringType().asErasure().getActualName();
            for (String pattern : patterns) {
                // get*/is*/set* are useful noise filters for ordinary domain objects,
                // but are often the public HTTP operation names on API classes.  Never
                // let the default accessor rules remove a selected API entry point.
                if (isBusinessEntryClass(className) && isAccessorPattern(pattern)) continue;
                if (methodPatternMatches(pattern, className, methodName)) return true;
            }
            return false;
        }
    }

    private static boolean isBusinessEntryClass(String className) {
        if (className == null || className.isBlank()) return false;
        int separator = className.lastIndexOf('.');
        String simpleName = separator < 0 ? className : className.substring(separator + 1);
        int generatedClassSeparator = simpleName.indexOf('$');
        if (generatedClassSeparator >= 0) simpleName = simpleName.substring(0, generatedClassSeparator);
        return ENTRY_CLASS_SUFFIXES.stream().anyMatch(simpleName::endsWith);
    }

    private static boolean isAccessorPattern(String pattern) {
        if (pattern == null) return false;
        return ACCESSOR_METHOD_PATTERNS.contains(pattern.trim());
    }

    private static boolean methodPatternMatches(String pattern, String className, String methodName) {
        if (pattern == null || pattern.isBlank()) return false;
        String value = pattern.trim();
        if (value.matches("[A-Za-z_$][\\w$]*\\.\\*")
                && methodName.matches(wildcardToRegex(value.substring(0, value.length() - 2) + "*"))) {
            return true;
        }
        String target = value.contains(".") ? className + "." + methodName : methodName;
        return target.matches(wildcardToRegex(value));
    }

    private static String wildcardToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if ("\\.[]{}()+-^$?|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }

    /**
     * Reports only transformation outcomes relevant to the active monitor configuration.
     * A failed retransformation used to be invisible because AgentBuilder's default listener
     * discards it, which made a package-selection problem indistinguishable from a bytecode
     * transformation problem.
     */
    private static final class TransformationDiagnostics extends AgentBuilder.Listener.Adapter {
        private final AtomicInteger businessClasses = new AtomicInteger();
        private final AtomicInteger dependencyClasses = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final EnhancementResultWriter resultWriter;
        private final ElementMatcher<TypeDescription> businessTypeMatcher;
        private final ElementMatcher<TypeDescription> servletMatcher;
        private final ElementMatcher<TypeDescription> jdbcStatementMatcher;
        private final ElementMatcher<TypeDescription> jdbcConnectionMatcher;
        private final ElementMatcher<MethodDescription> businessMethodMatcher;
        private final ElementMatcher<MethodDescription> servletMethodMatcher;
        private final ElementMatcher<MethodDescription> jdbcStatementMethodMatcher;
        private final ElementMatcher<MethodDescription> jdbcConnectionMethodMatcher;

        private TransformationDiagnostics(EnhancementResultWriter resultWriter,
                                          ElementMatcher<TypeDescription> businessTypeMatcher,
                                          ElementMatcher<TypeDescription> servletMatcher,
                                          ElementMatcher<TypeDescription> jdbcStatementMatcher,
                                          ElementMatcher<TypeDescription> jdbcConnectionMatcher,
                                          ElementMatcher<MethodDescription> businessMethodMatcher,
                                          ElementMatcher<MethodDescription> servletMethodMatcher,
                                          ElementMatcher<MethodDescription> jdbcStatementMethodMatcher,
                                          ElementMatcher<MethodDescription> jdbcConnectionMethodMatcher) {
            this.resultWriter = resultWriter;
            this.businessTypeMatcher = businessTypeMatcher;
            this.servletMatcher = servletMatcher;
            this.jdbcStatementMatcher = jdbcStatementMatcher;
            this.jdbcConnectionMatcher = jdbcConnectionMatcher;
            this.businessMethodMatcher = businessMethodMatcher;
            this.servletMethodMatcher = servletMethodMatcher;
            this.jdbcStatementMethodMatcher = jdbcStatementMethodMatcher;
            this.jdbcConnectionMethodMatcher = jdbcConnectionMethodMatcher;
        }

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            String className = typeDescription.getActualName();
            if (businessTypeMatcher.matches(typeDescription)) {
                businessClasses.incrementAndGet();
                recordTransformed("business", className, loaded, typeDescription, businessMethodMatcher);
            } else if (servletMatcher.matches(typeDescription)) {
                dependencyClasses.incrementAndGet();
                recordTransformed("servlet", className, loaded, typeDescription, servletMethodMatcher);
            } else if (jdbcStatementMatcher.matches(typeDescription)) {
                dependencyClasses.incrementAndGet();
                recordTransformed("jdbc-statement", className, loaded, typeDescription, jdbcStatementMethodMatcher);
            } else if (jdbcConnectionMatcher.matches(typeDescription)) {
                dependencyClasses.incrementAndGet();
                recordTransformed("jdbc-connection", className, loaded, typeDescription, jdbcConnectionMethodMatcher);
            }
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            if (!MethodSpanAdvice.matchesFilter(typeName)
                    && !DependencySpanSupport.isSupportedClassName(typeName)
                    && !JdbcSpanSupport.isLikelyJdbcClass(typeName)) {
                return;
            }
            failures.incrementAndGet();
            String reason = throwable == null ? "unknown" : throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage();
            resultWriter.failed(typeName, MethodSpanAdvice.matchesFilter(typeName) ? "business" : "dependency", reason);
            logError("transformation failed for " + typeName + ": " + reason);
        }

        private String summary() {
            return "transformation summary: business=" + businessClasses.get()
                    + ", dependencies=" + dependencyClasses.get()
                    + ", failures=" + failures.get();
        }

        private void recordTransformed(String category, String className, boolean loaded,
                                       TypeDescription typeDescription,
                                       ElementMatcher<MethodDescription> methodMatcher) {
            List<String> methodNames = typeDescription.getDeclaredMethods().filter(methodMatcher).stream()
                    .map(MethodDescription::getActualName)
                    .distinct()
                    .sorted()
                    .toList();
            resultWriter.transformed(className, category, loaded, methodNames);
            logInfo("transformed " + category + " class: "
                    + className + " (" + (loaded ? "retransformed" : "loaded after attach") + ")");
        }

        private void onRetransformationFailure(Class<?> type, Throwable throwable) {
            failures.incrementAndGet();
            String reason = throwable == null ? "unknown" : throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage();
            resultWriter.failed(type.getName(), MethodSpanAdvice.matchesFilter(type.getName())
                    ? "business" : "dependency", reason);
            logError("retransform failed for " + type.getName() + ": " + reason);
        }
    }

    /** Reports the final per-class failures after a failed retransformation batch is split. */
    private static final class RetransformationDiagnostics
            extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {
        private final TransformationDiagnostics diagnostics;

        private RetransformationDiagnostics(TransformationDiagnostics diagnostics) {
            this.diagnostics = diagnostics;
        }

        @Override
        public Iterable<? extends List<Class<?>>> onError(int index, List<Class<?>> batch,
                                                            Throwable throwable,
                                                            List<Class<?>> types) {
            if (batch.size() == 1) diagnostics.onRetransformationFailure(batch.get(0), throwable);
            return super.onError(index, batch, throwable, types);
        }
    }

}
