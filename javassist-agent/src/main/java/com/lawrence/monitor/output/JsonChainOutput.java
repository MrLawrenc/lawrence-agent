package com.lawrence.monitor.output;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.lawrence.monitor.trace.SpanNode;

/**
 * 将调用链（SpanNode 树）序列化为 JSON 字符串的抽象基类。
 * <p>
 * 子类只需实现 {@link #send(String)} 方法，将 JSON 投递到目标（MQ、ELK、HTTP 等）。
 *
 * <pre>
 * JSON 结构：
 * {
 *   "traceId"   : "xxx",
 *   "thread"    : "http-nio-8080-exec-1",
 *   "totalMs"   : 257,
 *   "timestamp" : 1700000000000,   // 近似请求开始的 epoch ms
 *   "root"      : { ...SpanNode 递归... }
 * }
 *
 * SpanNode 节点结构：
 * {
 *   "spanId"     : "1.2",
 *   "class"      : "com.example.OrderService",
 *   "method"     : "createOrder",
 *   "durationMs" : 240,
 *   "error"      : false,
 *   "children"   : [ ...递归... ]
 * }
 * </pre>
 */
public abstract class JsonChainOutput implements ChainOutput {

    @Override
    public final void output(SpanNode root) {
        send(toJson(root));
    }

    /**
     * 将根 SpanNode 树序列化为 JSON 字符串。
     * 可在子类中复用，也可单独测试。
     */
    public String toJson(SpanNode root) {
        long totalMs = root.durationMs();
        long timestamp = System.currentTimeMillis() - totalMs;

        JsonObject payload = Json.object()
                .add("type",      "TimingChain")
                .add("traceId",   root.getTraceId())
                .add("thread",    Thread.currentThread().getName())
                .add("totalMs",   totalMs)
                .add("timestamp", timestamp)
                .add("root",      buildSpanJson(root));

        return payload.toString();
    }

    /**
     * 投递序列化后的 JSON。子类在此实现具体的传输逻辑（MQ / ELK / HTTP 等）。
     *
     * @param json 完整的调用链 JSON 字符串
     */
    protected abstract void send(String json);

    // ── 私有工具 ──────────────────────────────────────────────────────────

    private JsonObject buildSpanJson(SpanNode node) {
        JsonObject obj = Json.object()
                .add("spanId",     node.getSpanId())
                .add("class",      node.getClassName())
                .add("method",     node.getMethodName())
                .add("durationMs", node.durationMs())
                .add("error",      node.isError());

        if (!node.getChildren().isEmpty()) {
            JsonArray children = Json.array();
            for (SpanNode child : node.getChildren()) {
                children.add(buildSpanJson(child));
            }
            obj.add("children", children);
        }

        return obj;
    }
}
