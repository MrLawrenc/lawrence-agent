package com.agentmonitor.app.report;
import com.agentmonitor.app.model.MonitoringSession;
public final class SessionAnalysisPrompt {
    private SessionAnalysisPrompt() { }
    public static String forSession(MonitoringSession session) {
        return "请分析 Java 性能采集会话：" + session.sessionDirectory() + "\n"
                + "先读 README.md、reports/performance-report.json 与 spans/manifest.json，再按需读原始 Span 和 Agent 日志。\n"
                + "若无法访问此目录，请先索取实际报告和清单文件，不要根据路径猜测内容；结合 config.yml 确认范围和阈值。\n"
                + "先报告状态、captureQuality、截断和采样限制；缺失数据不能解释为没有问题。\n"
                + "输出有方法、耗时、调用路径证据的热点、可能根因和最小优化建议，并区分事实与假设。\n"
                + "部分普通请求与慢/异常兜底是有偏样本，禁止直接推算总体平均值、P95、错误率或吞吐。\n"
                + "JVM 内存、线程和 GC 当前仅实时展示，没有写入会话报告；不要推断某次慢调用由 GC 或内存压力导致。\n"
                + "当前仅关联同步线程；不要把同步热点路径称为完整异步关键路径。";
    }
}
