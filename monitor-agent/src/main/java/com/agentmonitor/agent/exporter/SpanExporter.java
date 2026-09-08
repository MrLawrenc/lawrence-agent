package com.agentmonitor.agent.exporter;

import com.agentmonitor.agent.model.SpanData;

public interface SpanExporter extends AutoCloseable {

    /** Stable destination name used in diagnostics and future health endpoints. */
    String name();

    /** Opens the destination. Failed exporters do not prevent another destination from starting. */
    boolean start();

    /** Strategy operation: exports one domain span without exposing transport details. */
    ExportResult export(SpanData span);

    default void ready() {
        // Only live outputs need the instrumentation-ready signal.
    }

    /**
     * Flushes any already accepted output within the supplied bounded timeout.
     *
     * <p>Leaf exporters normally only need to flush their transport or file writer. The async
     * decorator also closes admission and waits for its in-memory queue before it calls this
     * method on its delegate.</p>
     */
    default ExporterDrainResult drain(long timeoutMillis) {
        return ExporterDrainResult.success();
    }

    @Override
    void close();
}
