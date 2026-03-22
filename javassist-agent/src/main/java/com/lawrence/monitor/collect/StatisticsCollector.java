package com.lawrence.monitor.collect;

import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局统计数据收集器。
 * Monitor 在根 Span 结束时调用 {@link #collect}，数据按统计类型分发至已注册的导出器。
 * <p>
 * 每种 Statistics 子类可注册专属导出器，也可注册全局导出器（对所有类型生效）。
 */
public final class StatisticsCollector {

    private static final Map<Class<? extends Statistics>, List<StatisticsExporter>> TYPED
            = new ConcurrentHashMap<>();

    private static final List<StatisticsExporter> GLOBAL = new CopyOnWriteArrayList<>();

    private StatisticsCollector() {
    }

    /**
     * 注册全局导出器（对所有 Statistics 类型生效）。
     */
    public static void register(StatisticsExporter exporter) {
        GLOBAL.add(exporter);
    }

    /**
     * 注册类型专属导出器（仅对指定的 Statistics 子类生效）。
     * 同一导出器 Class 对同一 Statistics 类型只注册一次。
     */
    public static void register(Class<? extends Statistics> type, StatisticsExporter exporter) {
        List<StatisticsExporter> list = TYPED.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        boolean alreadyRegistered = list.stream().anyMatch(e -> e.getClass() == exporter.getClass());
        if (!alreadyRegistered) {
            list.add(exporter);
        }
    }

    /**
     * 收集一次完整的统计，分发到匹配的导出器。
     * 通常在根 Span 结束时由 Monitor 调用。
     */
    public static void collect(Statistics statistics, SpanNode rootSpan) {
        List<StatisticsExporter> typed = TYPED.get(statistics.getClass());
        if (typed != null) {
            for (StatisticsExporter e : typed) safeExport(e, statistics, rootSpan);
        }
        for (StatisticsExporter e : GLOBAL) safeExport(e, statistics, rootSpan);
    }

    public static void close() {
        TYPED.values().forEach(list -> list.forEach(StatisticsExporter::close));
        GLOBAL.forEach(StatisticsExporter::close);
    }

    private static void safeExport(StatisticsExporter e, Statistics statistics, SpanNode root) {
        try {
            e.export(statistics, root);
        } catch (Exception ex) {
            // isolate exporter failures
        }
    }
}
