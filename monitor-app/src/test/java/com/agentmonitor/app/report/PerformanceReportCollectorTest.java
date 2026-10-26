package com.agentmonitor.app.report;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.app.model.*;
import com.agentmonitor.app.report.analysis.PerformanceReportCollector;
import com.agentmonitor.app.report.model.*;
import com.agentmonitor.model.output.ExporterStatistics;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class PerformanceReportCollectorTest {
    @TempDir Path directory;
    private static CaptureQuality quality() { return new CaptureQuality(new ExporterStatistics(true, 3, 0, 0, 0, 0), 3, 0, 0); }
    private PerformanceReportCollector collector(MonitoringSession session, int samples) {
        var result = new PerformanceReportCollector(new AnalysisConfig(true, 1, 1, 1, true, samples, 5, 20), session);
        result.start(); return result;
    }
    private static CallNode node(String method, long ms) { return new CallNode("example.Service", method, "request", ms * 1_000_000, false, 0); }
    @Test void calculatesSelfTimeWithDirectChildrenAndKeepsNestedEvidence() throws Exception {
        var session = MonitoringSession.create(directory.toString());
        var collector = collector(session, 20);
        CallNode root = node("root", 100), child = node("child", 60), grandchild = node("leaf", 20);
        child.getChildren().add(grandchild); root.getChildren().add(child);
        collector.accept("trace", root); collector.finish(ReportStatus.COMPLETED, "restored", quality());
        JsonNode report = new ObjectMapper().readTree(session.performanceReportPath().toFile());
        assertEquals("COMPLETED", report.path("status").asText()); assertTrue(report.path("analysisReady").asBoolean());
        assertEquals(3, report.path("analyzedSpans").asLong());
        for (JsonNode hot : report.path("hotspots")) {
            if (hot.path("method").asText().contains("#root")) assertEquals(40_000_000, hot.path("selfNanos").asLong());
            if (hot.path("method").asText().contains("#child")) assertEquals(40_000_000, hot.path("selfNanos").asLong());
        }
        assertFalse(report.has("p95")); assertFalse(report.path("limitations").isEmpty());
        assertTrue(Files.isRegularFile(session.sessionDirectory().resolve("ai-analysis-prompt.txt")));
    }
    @Test void outputLossMakesTheDurableReportIncompleteAndFinishIsIdempotent() throws Exception {
        var session = MonitoringSession.create(directory.toString()); var collector = collector(session, 20);
        collector.accept("trace", node("root", 10));
        collector.finish(ReportStatus.COMPLETED, "flush complete", new CaptureQuality(new ExporterStatistics(true, 1, 3, 0, 0, 0), 1, 0, 0));
        String first = Files.readString(session.performanceReportPath());
        collector.finish(ReportStatus.COMPLETED, "second cleanup", quality());
        assertEquals(first, Files.readString(session.performanceReportPath()));
        JsonNode report = new ObjectMapper().readTree(first);
        assertEquals("INCOMPLETE", report.path("status").asText()); assertFalse(report.path("analysisReady").asBoolean());
    }
    @Test void retainsTheSlowestSamplesAndFlagsInconsistentChildDurations() throws Exception {
        var session = MonitoringSession.create(directory.toString()); var collector = collector(session, 1);
        collector.accept("small", node("root", 5));
        CallNode large = node("root", 10); large.getChildren().add(node("child", 20));
        collector.accept("large", large); collector.finish(ReportStatus.COMPLETED, "done", quality());
        JsonNode report = new ObjectMapper().readTree(session.performanceReportPath().toFile());
        assertEquals(1, report.path("slowTraces").size()); assertEquals("large", report.path("slowTraces").get(0).path("traceId").asText());
        assertTrue(report.path("analysisLimits").path("sampleLimitReached").asBoolean());
        assertEquals(1, report.path("analysisLimits").path("inconsistentChildDurations").asLong());
        for (JsonNode hot : report.path("hotspots")) assertTrue(hot.path("selfNanos").asLong() >= 0);
    }
}
