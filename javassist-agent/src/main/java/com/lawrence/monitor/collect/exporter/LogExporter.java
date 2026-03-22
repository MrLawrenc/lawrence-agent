package com.lawrence.monitor.collect.exporter;

import com.lawrence.monitor.collect.StatisticsExporter;
import com.lawrence.monitor.statistics.JdbcStatistics;
import com.lawrence.monitor.statistics.ServletStatistics;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;

/**
 * 以日志方式输出统计摘要，用于 Servlet / JDBC 等监控。
 */
public class LogExporter implements StatisticsExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogExporter.class);

    @Override
    public void export(Statistics statistics, SpanNode rootSpan) {
        if (statistics instanceof ServletStatistics) {
            ServletStatistics s = (ServletStatistics) statistics;
            LOGGER.info("[ServletStatistics] {} {} -> {} cost={}ms | params={} | body={}{}",
                    s.getMethod(), s.getUrl(), s.getRespStatus(), s.getCostMs(),
                    s.getUrlData(), s.getBodyData(),
                    s.hasError() ? " ERROR=" + s.getError().getMessage() : "");
        } else if (statistics instanceof JdbcStatistics) {
            JdbcStatistics j = (JdbcStatistics) statistics;
            LOGGER.info("[JdbcStatistics] {} sql=[{}] count={} cost={}ms{}",
                    j.getUrl(), j.getSql(), j.getCount(), j.getCostMs(),
                    j.hasError() ? " ERROR=" + j.getError().getMessage() : "");
        } else {
            LOGGER.info("[{}] {}#{} cost={}ms{}",
                    statistics.getClass().getSimpleName(),
                    statistics.getClassName() != null ? statistics.getClassName() : "-",
                    statistics.getMethodName() != null ? statistics.getMethodName() : "-",
                    statistics.getCostMs(),
                    statistics.hasError() ? " ERROR=" + statistics.getError().getMessage() : "");
        }
    }
}
