package com.lawrence.monitor.trace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 ThreadLocal 的调用链上下文。
 * 统一管理方法 Span 的入栈/出栈，替代原 TimingMonitor.CALL_STACK 及 StackNode + ThreadLocalUtil。
 * <p>
 * 每个 Monitor 持有独立的 TraceContext 实例，互不干扰。
 */
public final class TraceContext {

    private final ThreadLocal<Deque<SpanNode>> stack =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final AtomicInteger SEQ = new AtomicInteger(Integer.MIN_VALUE);

    private static String nextId() {
        return SEQ.incrementAndGet() + "#" + System.currentTimeMillis();
    }

    /**
     * 开始一个新 Span，自动建立父子关系。
     * 若当前调用栈为空，则为根 Span（traceId = spanId）。
     */
    public SpanNode beginSpan(String className, String methodName) {
        Deque<SpanNode> s = stack.get();
        SpanNode parent = s.isEmpty() ? null : s.peek();

        String spanId  = nextId();
        String traceId = (parent == null) ? spanId : parent.getTraceId();

        SpanNode node = new SpanNode(traceId, spanId, className, methodName,
                System.nanoTime(), parent);
        if (parent != null) parent.addChild(node);
        s.push(node);
        return node;
    }

    /**
     * 结束当前（栈顶）Span，返回已完成的节点。
     * 若栈随后为空，清理 ThreadLocal 防止泄漏。
     */
    public SpanNode endSpan() {
        Deque<SpanNode> s = stack.get();
        if (s.isEmpty()) return null;
        SpanNode node = s.pop();
        node.finish(System.nanoTime());
        if (s.isEmpty()) stack.remove();
        return node;
    }

    /**
     * 标记当前栈顶 Span 为异常。
     */
    public void markError() {
        Deque<SpanNode> s = stack.get();
        if (!s.isEmpty()) s.peek().markError();
    }

    /**
     * 当前线程是否处于活跃 Span 中。
     */
    public boolean isActive() {
        return !stack.get().isEmpty();
    }
}
