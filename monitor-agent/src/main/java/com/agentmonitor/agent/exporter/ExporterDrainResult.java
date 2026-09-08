package com.agentmonitor.agent.exporter;

/**
 * Bounded output-drain outcome used by the Agent STOP control path.
 *
 * <p>{@code drained} means every accepted Span reached the destination's flush barrier before
 * the timeout. It deliberately says nothing about collector-side processing: the control
 * acknowledgement remains the boundary visible to the App.</p>
 */
public record ExporterDrainResult(boolean drained, long pendingSpans, long droppedSpans,
                                  long rejectedDestinations, String detail) {

    public ExporterDrainResult {
        pendingSpans = Math.max(0, pendingSpans);
        droppedSpans = Math.max(0, droppedSpans);
        rejectedDestinations = Math.max(0, rejectedDestinations);
        detail = detail == null ? "" : detail.trim();
    }

    public static ExporterDrainResult success() {
        return new ExporterDrainResult(true, 0, 0, 0, "");
    }

    public static ExporterDrainResult success(long droppedSpans, long rejectedDestinations) {
        return new ExporterDrainResult(true, 0, droppedSpans, rejectedDestinations, "");
    }

    public static ExporterDrainResult failed(long pendingSpans, long droppedSpans,
                                             long rejectedDestinations, String detail) {
        return new ExporterDrainResult(false, pendingSpans, droppedSpans, rejectedDestinations, detail);
    }

    /** Stable, human-readable summary carried in STOP acknowledgements and Agent logs. */
    public String summary() {
        String state = drained ? "输出已 drain/flush" : "输出 drain/flush 未完成";
        String counters = "pending=" + pendingSpans + ", dropped=" + droppedSpans
                + ", rejectedDestinations=" + rejectedDestinations;
        return detail.isBlank() ? state + "（" + counters + "）"
                : state + "（" + counters + "；" + detail + "）";
    }
}
