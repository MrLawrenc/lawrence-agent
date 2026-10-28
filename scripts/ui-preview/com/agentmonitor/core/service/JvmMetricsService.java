package com.agentmonitor.core.service;

import com.agentmonitor.model.jvm.JvmMetrics;

/** Fixture metrics for the production controller. Does not open JMX or attach. */
public final class JvmMetricsService implements AutoCloseable {
    public JvmMetricsService(String pid) {}
    public JvmMetrics sample() {
        long mb=1024L*1024;
        return new JvmMetrics(284*mb,512*mb,1024*mb,96*mb,128*mb,42,29,6240,6280,40,12,146,System.currentTimeMillis(),"");
    }
    @Override public void close() {}
}
