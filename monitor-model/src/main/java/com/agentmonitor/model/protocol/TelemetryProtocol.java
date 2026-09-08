package com.agentmonitor.model.protocol;

import java.util.Map;

import com.agentmonitor.model.output.ExporterStatistics;

/** Shared protocol constants and JSON envelopes for every monitoring transport. */
public final class TelemetryProtocol {

    public static final int SCHEMA_VERSION = 1;
    public static final String TYPE_FIELD = "type";
    public static final String SCHEMA_VERSION_FIELD = "schemaVersion";
    public static final String MESSAGE_FIELD = "message";
    public static final String STOP_COMMAND = "STOP";
    public static final String NEWLINE = "\n";

    private TelemetryProtocol() { }

    public static String message(ProtocolMessageType type) {
        return message(type, "", false);
    }

    public static String message(ProtocolMessageType type, String message) {
        return message(type, message, true);
    }

    /** JSON envelope for the resource that is constant for one Agent session. */
    public static String resourceMessage(Map<String, String> attributes) {
        StringBuilder payload = new StringBuilder("{\"").append(TYPE_FIELD).append("\":\"")
                .append(ProtocolMessageType.RESOURCE.wireValue()).append("\",\"")
                .append(SCHEMA_VERSION_FIELD).append("\":").append(SCHEMA_VERSION).append(",\"attributes\":{");
        boolean first = true;
        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                if (!first) payload.append(',');
                payload.append('"').append(escape(entry.getKey())).append("\":\"")
                        .append(escape(entry.getValue())).append('"');
                first = false;
            }
        }
        return payload.append("}}").toString();
    }

    /** Final, structured exporter quality snapshot. It is ordered before the STOP acknowledgement. */
    public static String outputQualityMessage(ExporterStatistics statistics) {
        ExporterStatistics value = statistics == null ? ExporterStatistics.unavailable() : statistics;
        return "{\"" + TYPE_FIELD + "\":\"" + ProtocolMessageType.OUTPUT_QUALITY.wireValue()
                + "\",\"" + SCHEMA_VERSION_FIELD + "\":" + SCHEMA_VERSION
                + ",\"reported\":" + value.reported()
                + ",\"enqueuedSpans\":" + value.enqueuedSpans()
                + ",\"queueDroppedSpans\":" + value.queueDroppedSpans()
                + ",\"deliveryDroppedSpans\":" + value.deliveryDroppedSpans()
                + ",\"rejectedDestinations\":" + value.rejectedDestinations()
                + ",\"pendingSpans\":" + value.pendingSpans() + "}";
    }

    private static String message(ProtocolMessageType type, String message, boolean includeMessage) {
        String payload = "{\"" + TYPE_FIELD + "\":\"" + type.wireValue() + "\",\""
                + SCHEMA_VERSION_FIELD + "\":" + SCHEMA_VERSION;
        if (includeMessage) payload += ",\"" + MESSAGE_FIELD + "\":\"" + escape(message) + "\"";
        return payload + "}";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
