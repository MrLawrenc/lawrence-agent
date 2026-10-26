package com.agentmonitor.app.model;

import com.fasterxml.jackson.databind.*;
import com.agentmonitor.model.protocol.TelemetryProtocol;
import com.agentmonitor.model.span.SpanKind;
import java.util.*;

/** Validated completed-span wire model shared by online collection and offline replay. */
public record SpanEvent(String traceId, String spanId, String parentSpanId, String className, String methodName,
        String signature, String threadName, int depth, long startedAtEpochMillis, long durationNanos,
        boolean error, String arguments, String returnValue, String stackTrace, SpanKind kind,
        Map<String, String> attributes) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public SpanEvent { attributes = Map.copyOf(attributes); }
    public static SpanEvent parse(String payload) {
        if (payload == null || payload.length() > 1_048_576) return null;
        try { return parse(JSON.readTree(payload)); } catch (Exception ignored) { return null; }
    }
    public static SpanEvent parse(JsonNode n) {
        try {
            if (n == null || !n.isObject() || !"span".equals(n.path("type").asText())
                    || !n.path("schemaVersion").isIntegralNumber()
                    || n.path("schemaVersion").asInt() != TelemetryProtocol.SCHEMA_VERSION) return null;
            String trace = required(n, "traceId"), id = required(n, "spanId"), cls = required(n, "className"), method = required(n, "methodName");
            if (!n.path("durationNanos").isIntegralNumber() || !n.path("durationNanos").canConvertToLong()
                    || n.path("durationNanos").asLong() < 0) return null;
            String parent = n.path("parentSpanId").asText("");
            if (parent.equals(id)) return null;
            int depth = n.path("depth").asInt(0);
            long started = n.path("startedAtEpochMillis").asLong(0);
            if (depth < 0 || started < 0) return null;
            Map<String, String> attributes = new LinkedHashMap<>();
            JsonNode supplied = n.path("attributes");
            if (!supplied.isMissingNode() && !supplied.isObject()) return null;
            supplied.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode() && !entry.getValue().isNull()) attributes.put(entry.getKey(), entry.getValue().asText());
            });
            return new SpanEvent(trace, id, parent, cls, method, n.path("signature").asText(""),
                    n.path("threadName").asText("unknown"), depth, started, n.path("durationNanos").asLong(),
                    n.path("error").asBoolean(false) || "ERROR".equals(n.path("status").asText()),
                    n.path("arguments").asText(""), n.path("returnValue").asText(""), n.path("stackTrace").asText(""),
                    SpanKind.fromWireValue(n.path("kind").asText()), attributes);
        } catch (IllegalArgumentException ignored) { return null; }
    }
    private static String required(JsonNode n, String name) {
        JsonNode value = n.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(name);
        return value.asText();
    }
    public CallNode toCallNode() {
        return new CallNode(className, methodName, threadName, durationNanos, error, depth, signature,
                arguments, returnValue, stackTrace, kind, attributes);
    }
}
