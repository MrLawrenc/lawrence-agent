package com.lawrence.monitor.collect.exporter;

import com.lawrence.monitor.collect.StatisticsExporter;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 将统计摘要以文本行形式追加写入指定文件，用于 FILE / BOTH 输出模式。
 */
public class FileStatsExporter implements StatisticsExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileStatsExporter.class);

    private final String filePath;

    public FileStatsExporter(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public void export(Statistics statistics, SpanNode rootSpan) {
        String line = String.format("[%s] %s#%s cost=%dms%s",
                statistics.getClass().getSimpleName(),
                statistics.getClassName() != null ? statistics.getClassName() : "-",
                statistics.getMethodName() != null ? statistics.getMethodName() : "-",
                statistics.getCostMs(),
                statistics.hasError() ? " ERROR=" + statistics.getError().getMessage() : "");
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            pw.println(line);
        } catch (IOException e) {
            LOGGER.error("FileStatsExporter write failed [{}]: {}", filePath, e.getMessage());
        }
    }
}
