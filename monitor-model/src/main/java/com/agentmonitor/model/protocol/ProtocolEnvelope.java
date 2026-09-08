package com.agentmonitor.model.protocol;

/** Minimal parsed envelope used before dispatching a protocol payload to its domain handler. */
public record ProtocolEnvelope(ProtocolMessageType type, int schemaVersion, String message) {

    public ProtocolEnvelope {
        type = type == null ? ProtocolMessageType.UNKNOWN : type;
        message = message == null ? "" : message;
    }

    public boolean isCurrentSchema() {
        return schemaVersion == TelemetryProtocol.SCHEMA_VERSION;
    }
}
