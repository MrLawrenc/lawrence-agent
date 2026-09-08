package com.agentmonitor.model.output;

/**
 * Monotonic, session-scoped output counters reported by the Agent when it stops.
 *
 * <p>The counters deliberately distinguish spans refused before the asynchronous queue from
 * spans that entered the queue but could not reach any configured destination. A rejected
 * destination is a sink-level signal: a span can still be available from another destination
 * when file and Netty export are both enabled.</p>
 */
public record ExporterStatistics(boolean reported, long enqueuedSpans, long queueDroppedSpans,
                                 long deliveryDroppedSpans, long rejectedDestinations,
                                 long pendingSpans) {

    public ExporterStatistics {
        enqueuedSpans = Math.max(0, enqueuedSpans);
        queueDroppedSpans = Math.max(0, queueDroppedSpans);
        deliveryDroppedSpans = Math.max(0, deliveryDroppedSpans);
        rejectedDestinations = Math.max(0, rejectedDestinations);
        pendingSpans = Math.max(0, pendingSpans);
    }

    public static ExporterStatistics unavailable() {
        return new ExporterStatistics(false, 0, 0, 0, 0, 0);
    }

    public long droppedSpans() {
        return queueDroppedSpans + deliveryDroppedSpans;
    }

    public boolean hasDegradation() {
        return !reported || droppedSpans() > 0 || rejectedDestinations > 0 || pendingSpans > 0;
    }
}
