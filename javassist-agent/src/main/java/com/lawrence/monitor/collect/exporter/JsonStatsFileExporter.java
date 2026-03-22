package com.lawrence.monitor.collect.exporter;

import com.eclipsesource.json.JsonObject;
import com.lawrence.monitor.collect.StatisticsExporter;
import com.lawrence.monitor.statistics.JdbcStatistics;
import com.lawrence.monitor.statistics.ServletStatistics;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 将统计信息序列化为 JSON 追加写入文件，控制台仅打印文件路径。
 */
public class JsonStatsFileExporter implements StatisticsExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonStatsFileExporter.class);

    private final String filePath;

    public JsonStatsFileExporter(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public void export(Statistics statistics, SpanNode rootSpan) {
        JsonObject obj = new JsonObject();
        obj.add("type", statistics.getClass().getSimpleName());
        obj.add("traceId", statistics.getTraceId());
        obj.add("costMs", statistics.getCostMs());
        obj.add("hasError", statistics.hasError());
        if (statistics.hasError() && statistics.getError() != null) {
            obj.add("error", statistics.getError().getMessage());
        }
        if (statistics instanceof ServletStatistics) {
            ServletStatistics s = (ServletStatistics) statistics;
            obj.add("httpMethod", s.getMethod() != null ? s.getMethod() : "");
            obj.add("url", s.getUrl() != null ? s.getUrl() : "");
            obj.add("params", s.getUrlData() != null ? s.getUrlData() : "");
            obj.add("body", s.getBodyData() != null ? s.getBodyData() : "");
            obj.add("respStatus", s.getRespStatus());
        } else if (statistics instanceof JdbcStatistics) {
            JdbcStatistics j = (JdbcStatistics) statistics;
            obj.add("dbUrl", j.getUrl() != null ? j.getUrl() : "");
            obj.add("sql", j.getSql() != null ? j.getSql() : "");
            obj.add("count", j.getCount());
        } else {
            obj.add("className", statistics.getClassName() != null ? statistics.getClassName() : "");
            obj.add("methodName", statistics.getMethodName() != null ? statistics.getMethodName() : "");
        }
        String json = obj.toString();
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            pw.println(json);
        } catch (IOException e) {
            LOGGER.error("JsonStatsFileExporter write failed [{}]: {}", filePath, e.getMessage());
        }
        LOGGER.info("[JSON stats written to: {}]", filePath);
    }
}
