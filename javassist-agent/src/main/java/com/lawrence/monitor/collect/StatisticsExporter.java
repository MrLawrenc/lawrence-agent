package com.lawrence.monitor.collect;

import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;

/**
 * 统计数据导出器接口。
 * 所有监控数据的输出（控制台、文件、日志等）均通过此接口实现，
 * 将输出逻辑从 Monitor 中完全解耦。
 */
public interface StatisticsExporter {

    /**
     * @param statistics 完整的统计数据
     * @param rootSpan   调用链根节点（无链路跟踪时为 null）
     */
    void export(Statistics statistics, SpanNode rootSpan);

    default void close() {
    }
}
