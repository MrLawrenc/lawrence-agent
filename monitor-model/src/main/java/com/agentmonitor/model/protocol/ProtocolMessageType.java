package com.agentmonitor.model.protocol;

import java.util.Arrays;

/** Stable message types for the versioned Agent-to-Collector wire protocol. */
public enum ProtocolMessageType {
    HELLO("hello"),
    /** Session-stable identity of the JVM observed by this Agent connection. */
    RESOURCE("resource"),
    /** Final Agent-side output counters, sent immediately before a STOP result. */
    OUTPUT_QUALITY("output_quality"),
    READY("ready"),
    SPAN("span"),
    /** Agent asks the Collector to acknowledge receipt of every preceding span frame. */
    DRAIN_REQUEST("drain_request"),
    /** Collector-to-Agent response to {@link #DRAIN_REQUEST}. */
    DRAIN_ACK("drain_ack"),
    STOPPED("stopped"),
    /** Bytecode reset completed, but the final bounded output drain did not. */
    DRAIN_FAILED("drain_failed"),
    RESET_FAILED("reset_failed"),
    UNKNOWN("");

    private final String wireValue;

    ProtocolMessageType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ProtocolMessageType fromWireValue(String wireValue) {
        return Arrays.stream(values())
                .filter(type -> type.wireValue.equals(wireValue))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
