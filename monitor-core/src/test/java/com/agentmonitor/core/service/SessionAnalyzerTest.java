package com.agentmonitor.core.service;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.model.MonitoringSession;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionAnalyzerTest {
    @TempDir Path directory;
    @Test void offlineAnalysisPreservesTraceIdsAndCannotHideUnreceivedSpans() throws Exception {
        var session = MonitoringSession.create(directory.toString());
        String spans = "[{\"type\":\"span\",\"schemaVersion\":1,\"traceId\":\"source-trace\",\"spanId\":\"root\","
                + "\"parentSpanId\":\"\",\"className\":\"example.Service\",\"methodName\":\"work\",\"durationNanos\":4000000000}]";
        Path file = session.spansDirectory().resolve("spans-1.json"); Files.writeString(file, spans);
        Path manifest = session.spansDirectory().resolve("manifest.json");
        String template = "{\"schemaVersion\":1,\"captureComplete\":true,\"writtenSpans\":1,\"outputQuality\":{\"reported\":true,"
                + "\"enqueuedSpans\":%d,\"queueDroppedSpans\":0,\"deliveryDroppedSpans\":0,\"rejectedDestinations\":0,\"pendingSpans\":0}}";
        Files.writeString(manifest, template.formatted(2));
        var json = new ObjectMapper(); var analyzer = new SessionAnalyzer();
        JsonNode incomplete = json.valueToTree(analyzer.analyze(session.sessionDirectory()));
        assertFalse(incomplete.path("report").path("captureComplete").asBoolean());
        assertEquals(1, incomplete.path("report").path("captureQuality").path("unreceivedSpans").asInt());
        assertEquals("source-trace", incomplete.path("report").path("slowTraces").get(0).path("traceId").asText());
        Files.writeString(manifest, template.formatted(1));
        JsonNode complete = json.valueToTree(analyzer.analyze(session.spansDirectory()));
        assertTrue(complete.path("report").path("captureComplete").asBoolean());
        assertEquals(spans, Files.readString(file)); assertFalse(Files.exists(session.performanceReportPath()));
        Files.delete(manifest);
        assertFalse(json.valueToTree(analyzer.analyze(session.sessionDirectory())).path("report").path("captureComplete").asBoolean());
    }
}
