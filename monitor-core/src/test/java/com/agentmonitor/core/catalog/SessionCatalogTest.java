package com.agentmonitor.core.catalog;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.model.MonitoringSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class SessionCatalogTest {
    @TempDir Path root;
    @Test void listsNewestMetadataWithoutRewritingReportsAndShowsUnfinishedSessions() throws Exception {
        var a=MonitoringSession.create(root.toString());var b=MonitoringSession.create(root.toString());var json=new ObjectMapper();
        for(var pair:List.of(Map.entry(a,"2026-10-27T00:00:01Z"),Map.entry(b,"2026-10-27T00:00:01.100Z"))) {
            Path marker=pair.getKey().sessionDirectory().resolve("session.json");var n=json.readTree(marker.toFile());((com.fasterxml.jackson.databind.node.ObjectNode)n).put("startedAt",pair.getValue());Files.writeString(marker,json.writeValueAsString(n));
        }
        Files.writeString(a.performanceReportPath(),"{\"sessionId\":\""+a.sessionId()+"\",\"status\":\"COMPLETED\",\"captureComplete\":true,\"analysisReady\":true}");byte[] before=Files.readAllBytes(a.performanceReportPath());
        var result=new SessionCatalog().list(root,20);var rows=(List<?>)result.get("sessions");assertEquals(2,rows.size());assertEquals(b.sessionId(),((Map<?,?>)rows.get(0)).get("sessionId"));
        var unfinished=new SessionCatalog().show(b.sessionDirectory());assertEquals("NOT_FINALIZED",unfinished.get("status"));assertNull(unfinished.get("captureComplete"));
        assertTrue((Boolean)new SessionCatalog().show(a.sessionDirectory()).get("readOnly"));assertArrayEquals(before,Files.readAllBytes(a.performanceReportPath()));
    }
    @Test void malformedOrMismatchedReportsNeverPresentCaptureAsComplete() throws Exception {
        var session=MonitoringSession.create(root.toString());Files.writeString(session.performanceReportPath(),"{\"sessionId\":\"other\",\"captureComplete\":true}");
        var result=new SessionCatalog().show(session.sessionDirectory());assertEquals("UNREADABLE_REPORT",result.get("status"));assertNull(result.get("captureComplete"));
        assertEquals(List.of(),new SessionCatalog().list(root.resolve("missing"),20).get("sessions"));
    }    @Test void duplicateAndTrailingReportDocumentsNeverShowSuccessfulFlags() throws Exception {
        var session=MonitoringSession.create(root.toString());String first="{\"sessionId\":\""+session.sessionId()+"\",\"status\":\"COMPLETED\",\"captureComplete\":true}";
        for(String invalid:List.of(first+"{}",first.substring(0,first.length()-1)+",\"captureComplete\":false}")) {
            Files.writeString(session.performanceReportPath(),invalid);var result=new SessionCatalog().show(session.sessionDirectory());assertEquals("UNREADABLE_REPORT",result.get("status"));assertNull(result.get("captureComplete"));
        }
    }

}
