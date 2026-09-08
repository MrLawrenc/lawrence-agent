package com.agentmonitor.agent.model;

import java.util.Map;

/** A timestamped event attached to one completed span, such as an unhandled exception. */
public record SpanEventData(String name, long timestampEpochMillis, Map<String, String> attributes) {

    public SpanEventData {
        name = name == null ? "" : name;
        timestampEpochMillis = Math.max(0, timestampEpochMillis);
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
