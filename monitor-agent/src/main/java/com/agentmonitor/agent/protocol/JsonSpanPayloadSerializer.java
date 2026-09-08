package com.agentmonitor.agent.protocol;

import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.model.protocol.ProtocolMessageType;
import com.agentmonitor.model.protocol.TelemetryProtocol;

/** Schema-v1 JSON encoder shared by file and Netty exporters. */
public final class JsonSpanPayloadSerializer implements SpanPayloadSerializer {

    @Override
    public String serialize(SpanData span) {
        StringBuilder json = new StringBuilder(512);
        json.append('{')
                .append("\"").append(TelemetryProtocol.TYPE_FIELD).append("\":\"")
                .append(ProtocolMessageType.SPAN.wireValue()).append("\",")
                .append("\"").append(TelemetryProtocol.SCHEMA_VERSION_FIELD).append("\":")
                .append(TelemetryProtocol.SCHEMA_VERSION).append(',');
        stringField(json, "traceId", span.traceId()).append(',');
        stringField(json, "spanId", span.spanId()).append(',');
        stringField(json, "parentSpanId", span.parentSpanId()).append(',');
        stringField(json, "className", span.className()).append(',');
        stringField(json, "methodName", span.methodName()).append(',');
        stringField(json, "signature", span.signature()).append(',');
        stringField(json, "threadName", span.threadName()).append(',');
        json.append("\"depth\":").append(span.depth()).append(',')
                .append("\"startedAtEpochMillis\":").append(span.startedAtEpochMillis()).append(',')
                .append("\"durationNanos\":").append(span.durationNanos()).append(',')
                .append("\"error\":").append(span.status().isError()).append(',');
        stringField(json, "kind", span.kind().name());
        stringField(json.append(','), "status", span.status().isError() ? "ERROR" : "UNSET");
        optionalStringField(json, "statusDescription", span.statusDescription());
        attributesField(json, span.attributes());
        eventsField(json, span.events());
        optionalStringField(json, "arguments", span.arguments());
        optionalStringField(json, "returnValue", span.returnValue());
        optionalStringField(json, "stackTrace", span.stackTrace());
        return json.append('}').toString();
    }

    @Override
    public String readyMessage() {
        return TelemetryProtocol.message(ProtocolMessageType.READY);
    }

    private static StringBuilder stringField(StringBuilder json, String name, String value) {
        return json.append('\"').append(name).append("\":\"").append(escape(value)).append('\"');
    }

    private static void optionalStringField(StringBuilder json, String name, String value) {
        if (value == null || value.isEmpty()) return;
        json.append(',');
        stringField(json, name, value);
    }

    private static void attributesField(StringBuilder json, java.util.Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return;
        json.append(",\"attributes\":{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!first) json.append(',');
            stringField(json, entry.getKey(), entry.getValue());
            first = false;
        }
        json.append('}');
    }

    private static void eventsField(StringBuilder json, java.util.List<com.agentmonitor.agent.model.SpanEventData> events) {
        if (events == null || events.isEmpty()) return;
        json.append(",\"events\":[");
        boolean first = true;
        for (com.agentmonitor.agent.model.SpanEventData event : events) {
            if (!first) json.append(',');
            json.append('{');
            stringField(json, "name", event.name()).append(',');
            json.append("\"timestampEpochMillis\":").append(event.timestampEpochMillis());
            attributesField(json, event.attributes());
            json.append('}');
            first = false;
        }
        json.append(']');
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }
}
