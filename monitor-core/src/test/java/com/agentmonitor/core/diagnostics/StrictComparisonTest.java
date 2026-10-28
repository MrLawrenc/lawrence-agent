package com.agentmonitor.core.diagnostics;

import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StrictComparisonTest {
    @TempDir Path directory;ObjectMapper json=new ObjectMapper();
    private MonitoringSession fixture(String build,long nanos)throws Exception {
        var s=MonitoringSession.create(directory.toString());var config=MonitoringConfig.defaults();ObjectNode supplied=json.valueToTree(config);
        supplied.putObject("experiment").put("workloadId","orders").put("datasetId","v1").put("loadProfile","10 clients, 1m").put("warmupId","2000 requests").put("buildId",build);
        ObjectNode report=json.createObjectNode().put("schemaVersion",1).put("sessionId",s.sessionId()).put("status","COMPLETED").put("captureComplete",true).put("analysisReady",true);
        report.set("monitoringConfig",supplied);var resource=report.putObject("resource").putObject("attributes");
        for(String key:List.of("service.name","process.runtime.name","process.runtime.version","host.name","host.arch","host.available_processors","os.name","os.version","process.runtime.arguments.sha256"))resource.put(key,"same-"+key);
        var quality=report.putObject("captureQuality").put("acceptedSpans",2).put("discardedSpans",0).put("unreceivedSpans",0);
        quality.putObject("agentExporter").put("reported",true).put("enqueuedSpans",2).put("queueDroppedSpans",0).put("deliveryDroppedSpans",0).put("pendingSpans",0).put("rejectedDestinations",0);
        report.putArray("hotspots").addObject().put("method","example#call()V").put("observedCalls",2).put("inclusiveNanos",nanos);
        json.writeValue(s.performanceReportPath().toFile(),report);json.writeValue(s.spansDirectory().resolve("manifest.json").toFile(),Map.of("captureComplete",true,"writtenSpans",2));
        json.writeValue(s.agentLogsDirectory().resolve("overhead.json").toFile(),Map.of("sessionId",s.sessionId(),"status","FINAL","policy",Map.of("mode","NORMAL","transitions",List.of(),"omittedTransitions",0)));
        return s;
    }
    @Test void strictCompatibleDeclarationsAllowObservedDeltasButNeverProveOptimization()throws Exception {
        var a=fixture("before",200);var b=fixture("after",100);var result=json.valueToTree(new SessionComparison().compare(a.sessionDirectory(),b.sessionDirectory()));
        assertTrue(result.path("comparability").path("eligible").asBoolean(),result.toString());assertEquals(-50,result.path("methods").get(0).path("observedMeanChangePercent").asDouble());assertFalse(result.path("optimizationProven").asBoolean());
    }
    @Test void missingContextOrDifferentEnvironmentBlocksDerivedPercentages()throws Exception {
        var a=fixture("before",200);var b=fixture("after",100);var report=(ObjectNode)json.readTree(b.performanceReportPath().toFile());((ObjectNode)report.path("monitoringConfig").path("experiment")).remove("warmupId");json.writeValue(b.performanceReportPath().toFile(),report);
        var result=json.valueToTree(new SessionComparison().compare(a.sessionDirectory(),b.sessionDirectory()));assertFalse(result.path("comparability").path("eligible").asBoolean());assertFalse(result.path("methods").get(0).has("observedMeanChangePercent"));assertEquals(5,DiagnosticCommands.exitCode(json.convertValue(result,Map.class)));
        var observed=json.valueToTree(new SessionComparison().compare(a.sessionDirectory(),b.sessionDirectory(),false));assertTrue(observed.path("methods").get(0).has("observedMeanChangePercent"));assertFalse(observed.path("optimizationProven").asBoolean());
    }
    @Test void adaptivePolicyAndForgedCompleteFlagsDoNotPassStrictGate()throws Exception {
        var a=fixture("before",200);var b=fixture("after",100);json.writeValue(b.agentLogsDirectory().resolve("overhead.json").toFile(),Map.of("sessionId",b.sessionId(),"status","FINAL","policy",Map.of("mode","NORMAL","transitions",List.of(Map.of("mode","REDUCED")),"omittedTransitions",0)));
        var result=json.valueToTree(new SessionComparison().compare(a.sessionDirectory(),b.sessionDirectory()));assertFalse(result.path("comparability").path("eligible").asBoolean());
        var report=(ObjectNode)json.readTree(a.performanceReportPath().toFile());report.remove("captureQuality");json.writeValue(a.performanceReportPath().toFile(),report);result=json.valueToTree(new SessionComparison().compare(a.sessionDirectory(),b.sessionDirectory()));assertFalse(result.path("methods").get(0).has("observedMeanChangePercent"));
    }
    @Test void matchingUnknownCaptureAndTransitionFieldsNeverCountAsComparable()throws Exception {
        var a=fixture("before",200);var b=fixture("after",100);
        for(var s:List.of(a,b)){var report=(ObjectNode)json.readTree(s.performanceReportPath().toFile());((ObjectNode)report.path("monitoringConfig").path("output")).remove("capture");json.writeValue(s.performanceReportPath().toFile(),report);json.writeValue(s.agentLogsDirectory().resolve("overhead.json").toFile(),Map.of("sessionId",s.sessionId(),"status","FINAL","policy",Map.of("mode","NORMAL","transitions",List.of())));}
        var result=json.valueToTree(new SessionComparison().compare(a.sessionDirectory(),b.sessionDirectory()));assertFalse(result.path("comparability").path("eligible").asBoolean());assertFalse(result.path("methods").get(0).has("observedMeanChangePercent"));
    }
}
