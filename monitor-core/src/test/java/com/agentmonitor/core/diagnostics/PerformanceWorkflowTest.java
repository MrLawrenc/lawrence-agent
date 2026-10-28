package com.agentmonitor.core.diagnostics;

import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.evidence.EvidenceStore;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceWorkflowTest {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper();
    @Test void identityIsMandatoryAndCrossSessionSourcesAreRejected()throws Exception{
        Path arbitrary=Files.createDirectories(directory.resolve("not-session/diagnostics"));Files.writeString(arbitrary.resolve("threads-latest.json"),"{\"deadlockedThreadIds\":[42]}");
        assertThrows(IllegalArgumentException.class,()->new SymptomDiagnosis().analyze(arbitrary.getParent(),"cpu"));
        var session=MonitoringSession.create(directory.resolve("sessions").toString());Files.createDirectories(session.sessionDirectory().resolve("diagnostics"));
        Files.writeString(session.sessionDirectory().resolve("diagnostics/latest.json"),"{\"sessionId\":\"foreign\",\"ProcessCpuLoad\":1}");
        assertThrows(IllegalArgumentException.class,()->new SymptomDiagnosis().analyze(session.sessionDirectory(),"cpu"));
        Files.writeString(session.sessionDirectory().resolve("session.json"),"{\"sessionId\":\"a\",\"sessionId\":\"b\"}");assertThrows(Exception.class,()->new SymptomDiagnosis().analyze(session.sessionDirectory(),"cpu"));
    }
    @Test void requestEvidenceHasWindowTimeAndVerifiesOriginalValues()throws Exception{
        var session=MonitoringSession.create(directory.resolve("sessions").toString());Path file=session.agentLogsDirectory().resolve("request-metrics.json");
        Files.writeString(file,json.writeValueAsString(Map.of("schemaVersion",1,"sessionId",session.sessionId(),"startedAtMillis",1000,"timestampMillis",2000,"captureComplete",true,"endpoints",List.of(Map.of("endpoint","GET /orders/{id}","completed",42)))));
        var store=new EvidenceStore();store.index(session.sessionDirectory());var result=store.query(session.sessionDirectory(),new EvidenceStore.Query(1500L,1700L,null,null,"orders","request_metrics",20,0));
        var matches=(List<?>)result.get("matches");assertEquals(1,matches.size());var row=(Map<?,?>)matches.get(0);assertEquals(1000L,((Number)row.get("startedAtMillis")).longValue());assertEquals(2000L,((Number)row.get("endedAtMillis")).longValue());
        assertEquals("",row.get("method"));assertEquals("GET /orders/{id}",row.get("endpoint"));
        var original=store.get(session.sessionDirectory(),row.get("evidenceId").toString());assertTrue((Boolean)original.get("verified"));assertEquals(42,((Map<?,?>)original.get("value")).get("completed"));
        assertTrue((Boolean)new RequestStatistics().read(session.sessionDirectory()).get("captureComplete"));
    }
    @Test void spanDirectoryCannotEscapeSessionAndRemoteRootIsNotAnOrphan()throws Exception{
        var session=MonitoringSession.create(directory.resolve("sessions").toString());Path outside=Files.createDirectories(directory.resolve("outside"));Files.delete(session.spansDirectory().resolve("README.md"));Files.delete(session.spansDirectory());Files.createSymbolicLink(session.spansDirectory(),outside);
        String trace="0123456789abcdef0123456789abcdef";assertThrows(Exception.class,()->new DistributedTrace().correlate(List.of(session.sessionDirectory()),trace));
    }
    @Test void missingRequestIdentityCannotBecomeSuccessfulMetrics()throws Exception{
        var session=MonitoringSession.create(directory.resolve("sessions").toString());Files.writeString(session.agentLogsDirectory().resolve("request-metrics.json"),"{\"captureComplete\":true,\"endpoints\":[]}");
        assertThrows(IllegalArgumentException.class,()->new RequestStatistics().read(session.sessionDirectory()));
    }
}
