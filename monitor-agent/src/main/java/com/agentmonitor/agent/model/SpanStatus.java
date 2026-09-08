package com.agentmonitor.agent.model;

/** Terminal state of an intercepted method invocation. */
public enum SpanStatus {
    /** The operation completed without an error; mirrors the OpenTelemetry UNSET success state. */
    UNSET(false),
    /** Kept for binary compatibility with older Agent callers; new spans use {@link #UNSET}. */
    SUCCESS(false),
    ERROR(true);

    private final boolean error;

    SpanStatus(boolean error) {
        this.error = error;
    }

    public boolean isError() { return error; }

    public static SpanStatus fromError(boolean error) {
        return error ? ERROR : UNSET;
    }
}
