package com.agentmonitor.agent.runtime;

import com.agentmonitor.agent.AgentMain;
import com.agentmonitor.agent.interceptor.DependencySpanSupport;
import com.agentmonitor.agent.interceptor.JdbcSpanSupport;
import com.agentmonitor.agent.interceptor.MethodSpanAdvice;
import com.agentmonitor.agent.interceptor.SpanContext;
import com.agentmonitor.agent.lifecycle.DetachResult;
import com.agentmonitor.bootstrap.bridge.BootstrapBridge;
import com.agentmonitor.model.output.ExporterStatistics;

/**
 * Core-side implementation of the Bootstrap bridge.  The Bootstrap ABI sees only opaque Object
 * states; all SpanContext, exporter and dependency types stay inside the private Agent runtime
 * class loader.
 */
public final class AgentRuntimeBridge implements BootstrapBridge.Runtime {

    @Override
    public Object enterBusiness(String className, String methodName, Object[] arguments) {
        return MethodSpanAdvice.onEnter(className, methodName, arguments);
    }

    @Override
    public void exitBusiness(Object state, String className, String methodName, String descriptor,
                             Throwable error, Object returnValue) {
        if (state instanceof SpanContext context) {
            MethodSpanAdvice.onExit(className, methodName, descriptor, context, error, returnValue);
        }
    }

    @Override
    public Object enterDependency(String className, String methodName, Object target, Object[] arguments) {
        return DependencySpanSupport.enter(className, methodName, target, arguments);
    }

    @Override
    public void exitDependency(Object state, Object target, Object[] arguments, Throwable error) {
        if (state instanceof DependencySpanSupport.Context context) {
            DependencySpanSupport.exit(context, target, arguments, null, error);
        }
    }

    @Override
    public Object enterJdbc(String methodName, Object statement, Object[] arguments) {
        return JdbcSpanSupport.enter(methodName, statement, arguments);
    }

    @Override
    public void exitJdbc(Object state, Throwable error) {
        if (state instanceof JdbcSpanSupport.Context context) JdbcSpanSupport.exit(context, error);
    }

    @Override
    public void jdbcStatementCreated(String methodName, Object connection, Object[] arguments, Object statement) {
        JdbcSpanSupport.onStatementCreated(methodName, connection, arguments, statement);
    }

    @Override
    public BootstrapBridge.DetachStatus detach() {
        return toStatus(AgentMain.detach());
    }

    @Override
    public BootstrapBridge.DetachStatus detach(BootstrapBridge.DetachListener listener) {
        DetachResult result = AgentMain.detach(detachResult -> {
            if (listener != null) listener.onDetached(toStatus(detachResult));
        });
        return toStatus(result);
    }

    private static BootstrapBridge.DetachStatus toStatus(DetachResult result) {
        BootstrapBridge.OutputStatistics outputStatistics = toOutputStatistics(result.exporterStatistics());
        if (!result.restored()) return BootstrapBridge.DetachStatus.failed(result.message(), outputStatistics);
        return result.outputDrained()
                ? BootstrapBridge.DetachStatus.success(result.message(), outputStatistics)
                : BootstrapBridge.DetachStatus.outputDrainFailed(result.message(), outputStatistics);
    }

    private static BootstrapBridge.OutputStatistics toOutputStatistics(ExporterStatistics statistics) {
        ExporterStatistics value = statistics == null ? ExporterStatistics.unavailable() : statistics;
        return new BootstrapBridge.OutputStatistics(value.reported(), value.enqueuedSpans(),
                value.queueDroppedSpans(), value.deliveryDroppedSpans(), value.rejectedDestinations(),
                value.pendingSpans());
    }
}
