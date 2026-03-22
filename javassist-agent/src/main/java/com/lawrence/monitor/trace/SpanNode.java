package com.lawrence.monitor.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 方法调用跨度节点。
 * 统一替代原 {@code output.CallNode} 与 {@code stack.StackNode.Node}，
 * 表示调用链中一次具体的方法调用。
 */
public final class SpanNode {

    private final String traceId;
    private final String spanId;
    private final String className;
    private final String methodName;
    private final long startNano;
    private long endNano;
    private boolean error;

    private final SpanNode parent;
    private final List<SpanNode> children = new ArrayList<>();

    SpanNode(String traceId, String spanId, String className, String methodName,
             long startNano, SpanNode parent) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.className = className;
        this.methodName = methodName;
        this.startNano = startNano;
        this.parent = parent;
    }

    void finish(long endNano) {
        this.endNano = endNano;
    }

    void markError() {
        this.error = true;
    }

    void addChild(SpanNode child) {
        children.add(child);
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getTraceId()    { return traceId; }
    public String getSpanId()     { return spanId; }
    public String getClassName()  { return className; }
    public String getMethodName() { return methodName; }
    public long getStartNano()    { return startNano; }
    public long getEndNano()      { return endNano; }
    public boolean isError()      { return error; }
    public SpanNode getParent()   { return parent; }

    public List<SpanNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /** 根节点（无父节点） */
    public boolean isRoot() {
        return parent == null;
    }

    /** 持续时间（毫秒） */
    public long durationMs() {
        return (endNano - startNano) / 1_000_000L;
    }

    @Override
    public String toString() {
        return "SpanNode{" + className + "#" + methodName
                + ", " + durationMs() + "ms"
                + (error ? ", ERROR" : "") + "}";
    }
}
