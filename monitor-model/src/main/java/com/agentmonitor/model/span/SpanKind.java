package com.agentmonitor.model.span;

/** Execution category used to distinguish business work from external dependency work. */
public enum SpanKind {
    BUSINESS,
    HTTP_SERVER,
    SQL;

    public static SpanKind fromWireValue(String value) {
        try {
            return value == null || value.isBlank() ? BUSINESS : SpanKind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return BUSINESS;
        }
    }
}
