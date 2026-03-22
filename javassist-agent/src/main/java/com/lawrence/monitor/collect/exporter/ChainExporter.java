package com.lawrence.monitor.collect.exporter;

import com.lawrence.monitor.collect.StatisticsExporter;
import com.lawrence.monitor.output.ChainOutput;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;

/**
 * 将调用链以树形格式输出（控制台或文件）的导出器。
 * 由 TimingMonitor.init() 在 StatisticsCollector 中注册。
 */
public class ChainExporter implements StatisticsExporter {

    private final ChainOutput output;

    public ChainExporter(ChainOutput output) {
        this.output = output;
    }

    @Override
    public void export(Statistics statistics, SpanNode rootSpan) {
        if (rootSpan != null) {
            output.output(rootSpan);
        }
    }
}
