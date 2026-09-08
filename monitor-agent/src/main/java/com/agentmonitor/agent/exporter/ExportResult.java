package com.agentmonitor.agent.exporter;

/** Immutable delivery outcome that keeps span loss distinct from partial sink failure. */
record ExportResult(int acceptedDestinations, int rejectedDestinations) {

    static ExportResult accepted() { return new ExportResult(1, 0); }
    static ExportResult rejected() { return new ExportResult(0, 1); }

    ExportResult combine(ExportResult other) {
        return new ExportResult(acceptedDestinations + other.acceptedDestinations,
                rejectedDestinations + other.rejectedDestinations);
    }

    boolean delivered() { return acceptedDestinations > 0; }
}
