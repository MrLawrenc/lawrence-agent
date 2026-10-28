package com.agentmonitor.model.trace;

import com.agentmonitor.model.span.SpanKind;
import java.util.*;

/** JDK-only call tree. The assembler owns mutation and freezes a tree before publishing it. */
public final class TraceNode {
    private final String fullClassName, methodName, thread, signature, args, retVal, stackTrace;
    private final long durationNanos;
    private final boolean error;
    private final int depth;
    private final SpanKind kind;
    private final Map<String, String> attributes;
    private final List<TraceNode> children = new ArrayList<>();
    private final String traceId;
    private boolean frozen;

    public TraceNode(String className, String methodName, String thread, long durationNanos, boolean error, int depth) {
        this(className, methodName, thread, durationNanos, error, depth, "", "", "", "", SpanKind.BUSINESS, Map.of());
    }
    public TraceNode(String className, String methodName, String thread, long durationNanos, boolean error, int depth,
                     String signature, String args, String retVal, String stackTrace, SpanKind kind, Map<String, String> attributes) {
        this(className, methodName, thread, durationNanos, error, depth, signature, args, retVal, stackTrace, kind, attributes, "");
    }
    public TraceNode(String className, String methodName, String thread, long durationNanos, boolean error, int depth,
                     String signature, String args, String retVal, String stackTrace, SpanKind kind, Map<String, String> attributes, String traceId) {
        this.fullClassName = Objects.requireNonNull(className); this.methodName = Objects.requireNonNull(methodName);
        this.thread = thread == null ? "unknown" : thread; this.durationNanos = durationNanos; this.error = error; this.depth = depth;
        this.signature = Objects.requireNonNullElse(signature, ""); this.args = Objects.requireNonNullElse(args, "");
        this.retVal = Objects.requireNonNullElse(retVal, ""); this.stackTrace = Objects.requireNonNullElse(stackTrace, "");
        this.kind = kind == null ? SpanKind.BUSINESS : kind; this.attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        this.traceId = Objects.requireNonNullElse(traceId, "");
    }
    public void addChild(TraceNode child) {
        if (frozen) throw new IllegalStateException("Published trace trees are immutable");
        children.add(Objects.requireNonNull(child));
    }
    /** Independent immutable preview; assembly remains open until the session delivery barrier. */
    public TraceNode snapshot() {
        Map<TraceNode,TraceNode> copies = new IdentityHashMap<>();
        ArrayDeque<TraceNode> pending = new ArrayDeque<>(); pending.add(this);
        while (!pending.isEmpty()) {
            TraceNode node = pending.removeFirst();
            if (copies.containsKey(node)) throw new IllegalStateException("Invalid call tree");
            copies.put(node,new TraceNode(node.fullClassName,node.methodName,node.thread,node.durationNanos,node.error,node.depth,
                    node.signature,node.args,node.retVal,node.stackTrace,node.kind,node.attributes,node.traceId));
            pending.addAll(node.children);
        }
        copies.forEach((original,copy) -> original.children.forEach(child -> copy.addChild(copies.get(child))));
        return copies.get(this).freeze();
    }
    public TraceNode freeze() {
        Set<TraceNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<TraceNode> pending = new ArrayDeque<>(); pending.add(this);
        while (!pending.isEmpty()) {
            TraceNode node = pending.removeFirst();
            if (!seen.add(node)) throw new IllegalStateException("A call tree cannot contain cycles or shared children");
            node.frozen = true; pending.addAll(node.children);
        }
        return this;
    }
    public String getFullClassName() { return fullClassName; }
    public String getTraceId() { return traceId; }
    public String getMethodName2() { return methodName; }
    public String getThread() { return thread; }
    public String getSignature() { return signature; }
    public String getArgs() { return args; }
    public String getRetVal() { return retVal; }
    public String getStackTrace() { return stackTrace; }
    public long getDurationNanos() { return durationNanos; }
    public boolean isError() { return error; }
    public int getDepth() { return depth; }
    public SpanKind getKind() { return kind; }
    public Map<String, String> getAttributes() { return attributes; }
    public List<TraceNode> getChildren() { return Collections.unmodifiableList(children); }
}
