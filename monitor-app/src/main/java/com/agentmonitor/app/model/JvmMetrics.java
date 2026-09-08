package com.agentmonitor.app.model;

public record JvmMetrics(
        long heapUsed,
        long heapCommitted,
        long heapMax,
        long nonHeapUsed,
        long nonHeapCommitted,
        int threadCount,
        int daemonThreadCount,
        long loadedClassCount,
        long totalLoadedClassCount,
        long unloadedClassCount,
        long gcCount,
        long gcTimeMillis,
        long timestampMillis,
        String error
) {
    public static JvmMetrics unavailable(String error) {
        return new JvmMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                System.currentTimeMillis(), error);
    }

    public boolean available() {
        return error == null || error.isBlank();
    }

    public double heapUsageRatio() {
        long denominator = heapMax > 0 ? heapMax : heapCommitted;
        return denominator <= 0 ? 0 : Math.min(1.0, heapUsed / (double) denominator);
    }

    public double nonHeapUsageRatio() {
        return nonHeapCommitted <= 0 ? 0 : Math.min(1.0, nonHeapUsed / (double) nonHeapCommitted);
    }
}
