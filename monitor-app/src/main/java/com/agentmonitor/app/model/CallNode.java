package com.agentmonitor.app.model;

import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.Map;

import com.agentmonitor.model.span.SpanKind;

public class CallNode {

    private final StringProperty method;
    private final StringProperty thread;
    private final LongProperty durationNanos;
    private final boolean error;
    private final int depth;
    private final String fullClassName;
    private final String methodName;
    private final String signature;
    private final String args;
    private final String retVal;
    private final String stackTrace;
    private final SpanKind kind;
    private final Map<String, String> attributes;
    private final ObservableList<CallNode> children = FXCollections.observableArrayList();

    public CallNode(String className, String methodName, String thread,
                    long durationNanos, boolean error, int depth) {
        this(className, methodName, thread, durationNanos, error, depth, "", "", "");
    }

    public CallNode(String className, String methodName, String thread,
                    long durationNanos, boolean error, int depth, String signature) {
        this(className, methodName, thread, durationNanos, error, depth, signature, "", "");
    }

    public CallNode(String className, String methodName, String thread,
                    long durationNanos, boolean error, int depth, String signature,
                    String args, String retVal) {
        this(className, methodName, thread, durationNanos, error, depth, signature, args, retVal, "");
    }

    public CallNode(String className, String methodName, String thread,
                    long durationNanos, boolean error, int depth, String signature,
                    String args, String retVal, String stackTrace) {
        this(className, methodName, thread, durationNanos, error, depth, signature, args, retVal, stackTrace,
                SpanKind.BUSINESS, Map.of());
    }

    public CallNode(String className, String methodName, String thread,
                    long durationNanos, boolean error, int depth, String signature,
                    String args, String retVal, String stackTrace, SpanKind kind, Map<String, String> attributes) {
        this.fullClassName = className;
        this.methodName    = methodName;
        this.signature     = signature != null ? signature : "";
        this.args          = args      != null ? args      : "";
        this.retVal        = retVal    != null ? retVal    : "";
        this.stackTrace    = stackTrace != null ? stackTrace : "";
        this.kind = kind == null ? SpanKind.BUSINESS : kind;
        this.attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        String simpleClass = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;
        this.method = new SimpleStringProperty(simpleClass + "." + methodName + "()");
        this.thread = new SimpleStringProperty(thread);
        this.durationNanos = new SimpleLongProperty(durationNanos);
        this.error = error;
        this.depth = depth;
    }

    public String getMethod()       { return method.get(); }
    public StringProperty methodProperty() { return method; }

    public String getThread()       { return thread.get(); }
    public StringProperty threadProperty() { return thread; }

    public long getDurationNanos()  { return durationNanos.get(); }
    public LongProperty durationNanosProperty() { return durationNanos; }

    public boolean isError()        { return error; }
    public int getDepth()           { return depth; }
    public String getFullClassName() { return fullClassName; }
    public String getMethodName2()   { return methodName; }
    public String getSignature()     { return signature; }
    public String getArgs()          { return args; }
    public String getRetVal()        { return retVal; }
    public String getStackTrace()    { return stackTrace; }
    public SpanKind getKind() { return kind; }
    public Map<String, String> getAttributes() { return attributes; }

    public ObservableList<CallNode> getChildren() { return children; }

    public String getDurationDisplay() {
        long ns = durationNanos.get();
        if (ns < 1_000L)           return ns + " ns";
        if (ns < 1_000_000L)       return String.format("%.1f µs", ns / 1_000.0);
        if (ns < 1_000_000_000L)   return String.format("%.2f ms", ns / 1_000_000.0);
        return String.format("%.2f s", ns / 1_000_000_000.0);
    }

    public String getDurationStyle() {
        if (error) return "error";
        long ns = durationNanos.get();
        if (ns < 1_000_000L)   return "d0"; // <1ms
        long ms = ns / 1_000_000;
        if (ms <    5) return "d1"; // 1–5ms
        if (ms <   20) return "d2"; // 5–20ms
        if (ms <   50) return "d3"; // 20–50ms
        if (ms <  100) return "d4"; // 50–100ms
        if (ms <  300) return "d5"; // 100–300ms
        if (ms < 1000) return "d6"; // 300ms–1s
        if (ms < 3000) return "d7"; // 1–3s
        return "d8";               // >3s
    }
}
