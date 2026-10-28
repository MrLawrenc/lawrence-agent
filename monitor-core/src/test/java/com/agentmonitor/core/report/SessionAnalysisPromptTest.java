package com.agentmonitor.core.report;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.report.analysis.PerformanceReportCollector;
import com.agentmonitor.model.report.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class SessionAnalysisPromptTest {
    @TempDir Path directory;
    @Test void everyFinalizedSessionExportsTheSamePromptAndMachineReadableContractAsTheCopyAction() throws Exception {
        var session=MonitoringSession.create(directory.toString());
        var collector=new PerformanceReportCollector(AnalysisConfig.disabled(),session);collector.start();
        collector.finish(ReportStatus.INCOMPLETE,"quality unavailable",CaptureQuality.unavailable());
        String prompt=Files.readString(session.sessionDirectory().resolve("ai-analysis-prompt.txt"));
        assertEquals(SessionAnalysisPrompt.forSession(session),prompt);
        assertTrue(prompt.contains(session.sessionId()));assertTrue(prompt.contains(session.sessionDirectory().toString()));
        assertTrue(prompt.contains("没有有效样本"));assertTrue(prompt.contains("不填猜测"));assertTrue(prompt.contains("有偏样本"));assertTrue(prompt.contains("JSON Pointer"));
        var schema=new ObjectMapper().readTree(session.reportsDirectory().resolve("ai-analysis-output.schema.json").toFile());
        assertFalse(schema.path("properties").path("quality").path("properties").path("populationStatisticsAllowed").path("const").asBoolean(true));
        assertEquals(1,schema.path("properties").path("findings").path("items").path("properties").path("evidence").path("minItems").asInt());
        assertEquals(5,schema.path("properties").path("findings").path("maxItems").asInt());
    }
}
