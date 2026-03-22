package com.lawrence.monitor;

import com.lawrence.monitor.collect.StatisticsExporter;
import com.lawrence.monitor.collect.exporter.FileStatsExporter;
import com.lawrence.monitor.collect.exporter.JsonStatsFileExporter;
import com.lawrence.monitor.collect.exporter.LogExporter;
import com.lawrence.monitor.output.ChainOutput;
import com.lawrence.monitor.output.ConsoleChainOutput;
import com.lawrence.monitor.output.FileJsonChainOutput;
import com.lawrence.monitor.output.FileChainOutput;
import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 全局输出配置，适用于所有 Monitor。
 * <p>
 * {@link #modes} 支持同时指定多个输出目标（如 CONSOLE + JSON），
 * 框架会将它们合并为一个组合输出器，无需在配置中写 BOTH 之类的特殊值。
 */
@Data
public class OutputConfig {

    /** 单一输出目标 */
    public enum OutputMode {
        /** 控制台文本树 */
        CONSOLE,
        /** 写入文件（文本） */
        FILE,
        /** 控制台 JSON（便于对接 MQ/ELK） */
        JSON
    }

    /** 激活的输出目标集合，默认仅控制台。可同时配置多个，如 [CONSOLE, JSON]。 */
    private Set<OutputMode> modes = new LinkedHashSet<>(Arrays.asList(OutputMode.CONSOLE));

    /** modes 包含 FILE 时的输出文件路径 */
    private String outputFile = "agent-output.log";

    /** 便捷方法：判断是否包含某模式 */
    public boolean hasMode(OutputMode mode) {
        return modes != null && modes.contains(mode);
    }

    /**
     * 根据当前 modes 创建组合 {@link ChainOutput}（TimingMonitor 树形/JSON 输出）。
     */
    public ChainOutput buildChainOutput() {
        List<ChainOutput> outputs = new ArrayList<>();
        if (modes == null || modes.isEmpty()) {
            return new ConsoleChainOutput();
        }
        for (OutputMode m : modes) {
            switch (m) {
                case CONSOLE: outputs.add(new ConsoleChainOutput()); break;
                case FILE:    outputs.add(new FileChainOutput(outputFile)); break;
                case JSON:    outputs.add(new FileJsonChainOutput(outputFile)); break;
            }
        }
        if (outputs.size() == 1) return outputs.get(0);
        return root -> outputs.forEach(o -> o.output(root));
    }

    /**
     * 根据当前 modes 创建组合 {@link StatisticsExporter}（JDBC/Servlet 扁平统计输出）。
     */
    public StatisticsExporter buildStatsExporter() {
        List<StatisticsExporter> exporters = new ArrayList<>();
        if (modes == null || modes.isEmpty()) {
            return new LogExporter();
        }
        for (OutputMode m : modes) {
            switch (m) {
                case CONSOLE: exporters.add(new LogExporter()); break;
                case FILE:    exporters.add(new FileStatsExporter(outputFile)); break;
                case JSON:    exporters.add(new JsonStatsFileExporter(outputFile)); break;
            }
        }
        if (exporters.size() == 1) return exporters.get(0);
        return (statistics, span) -> exporters.forEach(e -> e.export(statistics, span));
    }
}
