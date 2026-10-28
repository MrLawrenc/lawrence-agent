package com.agentmonitor.core.diagnostics;
import com.agentmonitor.model.trace.TraceNode;
import com.agentmonitor.model.span.SpanKind;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EvidenceAnalysisTest {
    @TempDir Path directory;
    @Test @org.junit.jupiter.api.Timeout(10) void anUnresponsiveConnectorCannotBlockStopOrOverwriteItsTimeoutStatus()throws Exception{
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var session=com.agentmonitor.core.model.MonitoringSession.create(directory.toString());
        var recorder=new DiagnosticRecorder("123",com.agentmonitor.model.config.MonitoringConfig.Diagnostics.defaults(),session,()->{
            entered.countDown();release.await();throw new java.io.IOException("simulated blocked connector");
        });
        recorder.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
        try{long before=System.nanoTime();recorder.close();assertTrue(System.nanoTime()-before<java.util.concurrent.TimeUnit.SECONDS.toNanos(7));
            var manifest=new ObjectMapper().readTree(session.sessionDirectory().resolve("diagnostics/manifest.json").toFile());
            assertTrue(manifest.path("finalizationTimedOut").asBoolean());assertEquals("DEGRADED",manifest.path("status").asText());
        }finally{release.countDown();}
        Thread.sleep(150);
        var manifest=new ObjectMapper().readTree(session.sessionDirectory().resolve("diagnostics/manifest.json").toFile());
        assertTrue(manifest.path("finalizationTimedOut").asBoolean());assertEquals("DEGRADED",manifest.path("status").asText());
    }
    @Test void diagnosticOnlyYamlMayOmitBusinessScopeButCaptureStillRequiresIt(){
        String yaml="version: 2\ndiagnostics:\n  jfr: true\n";
        assertTrue(com.agentmonitor.core.config.MonitoringConfigYaml.parseDiagnostics(yaml).diagnostics().jfr());
        assertThrows(IllegalArgumentException.class,()->com.agentmonitor.core.config.MonitoringConfigYaml.parse(yaml));
    }
    @Test void aggregatesRepeatedQueriesWithoutClaimingNPlusOneOrGlobalPercentiles(){
        TraceNode root=new TraceNode("OrderService","list","request",10000000,false,0);
        for(int i=0;i<6;i++)root.addChild(new TraceNode("JDBC","executeQuery","request",1000000,i==2,1,"","","","",SpanKind.BUSINESS,Map.of("sqlFingerprint","select * from orders where id = ?","error.type","SQLTimeoutException")));
        EvidenceAggregation aggregate=new EvidenceAggregation();aggregate.accept("request-trace",root);
        JsonNode result=new ObjectMapper().valueToTree(aggregate.document());
        assertEquals(6,result.path("sql").get(0).path("observedCalls").asInt());assertEquals(6000000,result.path("sql").get(0).path("inclusiveNanos").asLong());
        assertEquals("request-trace",result.path("repeatedQueries").get(0).path("traceId").asText());assertEquals("repeated_query_candidate",result.path("repeatedQueries").get(0).path("classification").asText());
        assertEquals(1,result.path("exceptions").get(0).path("observedErrors").asInt());assertFalse(result.has("p95"));
    }
    @Test void comparesSampleMeansAndFlagsDifferentScopeAndIncompleteCapture()throws Exception{
        ObjectMapper json=new ObjectMapper();Path before=directory.resolve("before.json"),after=directory.resolve("after.json");
        json.writeValue(before.toFile(),Map.of("sessionId","a","captureComplete",true,"monitoringConfig",Map.of("scope","a"),"hotspots",List.of(Map.of("method","example#call","observedCalls",2,"inclusiveNanos",200))));
        json.writeValue(after.toFile(),Map.of("sessionId","b","captureComplete",false,"monitoringConfig",Map.of("scope","b"),"hotspots",List.of(Map.of("method","example#call","observedCalls",4,"inclusiveNanos",200))));
        JsonNode result=json.valueToTree(new SessionComparison().compare(before,after,false));
        assertEquals(-50,result.path("methods").get(0).path("observedMeanChangePercent").asDouble());assertEquals(3,result.path("warnings").size());
        assertFalse(result.has("optimizationGain"));
    }
    @Test void rejectsUnknownOptionsBeforeAttachingAndReportsIncompleteDiagnosticExit(){
        assertThrows(IllegalArgumentException.class,()->DiagnosticCommands.execute(new String[]{"diag","snapshot","--pid","1","--unknown","x"}));
        assertThrows(IllegalArgumentException.class,()->DiagnosticCommands.execute(new String[]{"compare","--before","a","--after","b","--kind","invalid"}));
        assertEquals(5,DiagnosticCommands.exitCode(Map.of("manifest",Map.of("samples",0,"errors",List.of("failed")))));
        assertEquals(0,DiagnosticCommands.exitCode(Map.of("manifest",Map.of("samples",3,"errors",List.of()))));
    }
}
