package com.lawrence.monitor.core;

import com.lawrence.monitor.AgentConfig;
import com.lawrence.monitor.OutputConfig;
import com.lawrence.monitor.StatisticsType;
import com.lawrence.monitor.collect.StatisticsCollector;
import com.lawrence.monitor.collect.StatisticsExporter;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;
import com.lawrence.monitor.trace.TraceContext;

/**
 * @author : MrLawrenc
 * date  2020/7/4 19:02
 */
public abstract class AbstractMonitor implements Monitor {

    /** 每个 Monitor 实例独立的 TraceContext，子类直接使用无需重复声明。 */
    protected final TraceContext trace = new TraceContext();

    // ── init 模板 ─────────────────────────────────────────────────────────

    /**
     * 框架统一初始化模板：先调用 {@link #doInit}，再自动注册 Exporter。
     * 插件开发者无需手动调用 {@code StatisticsCollector.register()}。
     */
    public final void init(AgentConfig agentConfig) {
        doInit(agentConfig);
        OutputConfig outputConfig = agentConfig.getOutputConfig() != null
                ? agentConfig.getOutputConfig() : new OutputConfig();
        StatisticsCollector.register(statisticsClass(), buildExporter(outputConfig));
    }

    /** Monitor 自定义初始化（可覆盖）：读取配置、设置包路径等。 */
    protected void doInit(AgentConfig agentConfig) {}

    /** 本 Monitor 对应的 Statistics 子类，用于 StatisticsCollector 路由。 */
    protected abstract Class<? extends Statistics> statisticsClass();

    /**
     * 构建本 Monitor 使用的 Exporter（可覆盖）。
     * 默认走 {@link OutputConfig#buildStatsExporter()}，自动适配 CONSOLE/FILE/BOTH/JSON 模式.
     * TimingMonitor 等需树形/链路输出的 Monitor 应覆盖此方法返回 {@link com.lawrence.monitor.collect.exporter.ChainExporter}。
     */
    protected StatisticsExporter buildExporter(OutputConfig outputConfig) {
        return outputConfig.buildStatsExporter();
    }

    /** 统计类归属类型。 */
    public abstract StatisticsType type();

    // ── end 模板 ──────────────────────────────────────────────────────────

    /**
     * 框架统一收尾模板：doEnd → finish → endSpan → collect。
     * 插件开发者无需手动调用 {@code StatisticsCollector.collect()}。
     */
    @Override
    public final Object end(Statistics statistics, Object result) {
        Object finalResult = doEnd(statistics, result);
        statistics.finish(System.currentTimeMillis());
        SpanNode span = endSpan(statistics);
        if (span != null) {
            StatisticsCollector.collect(statistics, span);
        }
        return finalResult;
    }

    /** Monitor 自定义收尾逻辑（可覆盖）：包装返回值、设置响应状态码等。默认原样返回。 */
    protected Object doEnd(Statistics statistics, Object result) {
        return result;
    }

    /**
     * 结束本 Monitor 的 Trace Span（必须实现）。
     * 返回非 null 则框架触发 collect；返回 null 则跳过（如 TimingMonitor 非根节点）。
     */
    protected abstract SpanNode endSpan(Statistics statistics);
}