package com.agentmonitor.core.ai;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.model.MonitoringSession;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AiAnalysisValidatorTest {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper();
    private MonitoringSession session;private ObjectNode report,response;private Path responseFile;
    @BeforeEach void prepare() throws Exception {
        session=MonitoringSession.create(directory.toString());responseFile=directory.resolve("response.json");
        report=json.createObjectNode().put("schemaVersion",1).put("sessionId",session.sessionId()).put("status","COMPLETED").put("captureComplete",true).put("analysisReady",true).put("observedRootTraces",1);
        var quality=report.putObject("captureQuality");quality.put("acceptedSpans",1).put("discardedSpans",0).put("unreceivedSpans",0);
        quality.putObject("agentExporter").put("reported",true).put("enqueuedSpans",1).put("queueDroppedSpans",0).put("deliveryDroppedSpans",0).put("rejectedDestinations",0).put("pendingSpans",0);
        report.putArray("slowTraces").addObject().put("traceId","trace-a").put("rootMethod","example.Service#work()V [BUSINESS]").put("durationNanos",1000000);
        Files.writeString(session.spansDirectory().resolve("manifest.json"),"{\"captureComplete\":true}");
        response=json.createObjectNode().put("schemaVersion",1).put("sessionId",session.sessionId());
        response.putObject("quality").put("verdict","usable").put("captureComplete",true).put("analysisReady",true).put("populationStatisticsAllowed",false).putArray("issues");
        var finding=response.putArray("findings").addObject().put("id","F1").put("priority","P3").put("title","Observed call").put("observation","One observed millisecond, no SLO supplied.")
                .putNull("hypothesis").put("confidence","low").put("recommendation","Gather controlled baseline evidence.");
        finding.putArray("missingEvidence").add("Baseline");
        finding.putArray("evidence").addObject().put("file","reports/performance-report.json").put("jsonPointer","/slowTraces/0/durationNanos").put("traceId","trace-a").put("method","example.Service#work()V [BUSINESS]").put("value",1000000).put("unit","nanoseconds");
        var validation=finding.putObject("validation").put("baseline","Warm the same workload.").put("change","One isolated candidate.").put("acceptance","Same semantics and repeatable measured improvement.");
        validation.putArray("metrics").add("Duration");validation.putArray("risks").add("Observer effects");response.putArray("nextMeasurements").add("Controlled baseline");
    }
    private ObjectNode evidence(){return (ObjectNode)response.path("findings").get(0).path("evidence").get(0);}
    @Test void indexedHistoricalJsonlCitationsAreExactAndRejectChangedSource()throws Exception {
        Path diag=session.sessionDirectory().resolve("diagnostics");Files.createDirectories(diag);
        Path metrics=diag.resolve("metrics.jsonl");Files.writeString(metrics,"{\"sessionId\":\""+session.sessionId()+"\",\"timestampMillis\":1000,\"ProcessCpuLoad\":0.1}\n");
        var store=new com.agentmonitor.core.evidence.EvidenceStore();store.index(session.sessionDirectory());
        JsonNode rows=json.valueToTree(store.query(session.sessionDirectory(),new com.agentmonitor.core.evidence.EvidenceStore.Query(null,null,null,null,null,"metrics",1,0)));
        evidence().put("file","diagnostics/metrics.jsonl").put("line",1).put("evidenceId",rows.path("matches").get(0).path("evidenceId").asText()).put("jsonPointer","/ProcessCpuLoad").put("value",new java.math.BigDecimal("0.1")).put("unit","ratio").putNull("traceId").putNull("method");
        var valid=verify();assertTrue(valid.valid(),valid.errors().toString());
        Files.writeString(metrics,"{\"sessionId\":\""+session.sessionId()+"\",\"timestampMillis\":1000,\"ProcessCpuLoad\":0.9}\n");evidence().put("value",new java.math.BigDecimal("0.9"));assertFalse(verify().valid());
        evidence().remove("evidenceId");assertTrue(verify().valid());evidence().put("line",0);assertFalse(verify().valid());
    }
    private AiAnalysisValidator.Result verify() throws Exception {Files.writeString(session.performanceReportPath(),json.writeValueAsString(report));Files.writeString(responseFile,json.writeValueAsString(response));return new AiAnalysisValidator().verify(session.sessionDirectory(),responseFile,null);}
    @Test void acceptsExactEvidenceAndNativeContract() throws Exception {var result=verify();assertTrue(result.valid(),result.errors().toString());assertEquals(1,result.verifiedEvidenceItems());}
    @Test void rejectsBrokenReferencesUnitsAndBooleanTypeConfusion() throws Exception {
        evidence().put("value",42);assertFalse(verify().valid());evidence().put("value",1000000).put("unit","milliseconds");assertFalse(verify().valid());
        evidence().put("unit","nanoseconds").put("jsonPointer","/slowTraces/0/durationNanos~2");assertFalse(verify().valid());
        report.putObject("condition").put("value",true);evidence().put("jsonPointer","/condition").putNull("traceId").putNull("method").put("unit","text").putObject("value").put("value",1);assertFalse(verify().valid());
    }
    @Test void bindsNearestMethodAndDisallowsNullIdentityBypass() throws Exception {
        ((ObjectNode)report.path("slowTraces").get(0)).putArray("bottlenecks").addObject().put("selfNanos",1000000).put("method","example.Dao#query()V [SQL]");
        evidence().put("jsonPointer","/slowTraces/0/bottlenecks/0/selfNanos");assertFalse(verify().valid());
        evidence().put("method","example.Dao#query()V [SQL]");assertTrue(verify().valid());evidence().putNull("traceId");assertFalse(verify().valid());
    }
    @Test void requiresConsistentQualityAndSessionEvidence() throws Exception {
        ((ObjectNode)report.path("captureQuality")).put("unreceivedSpans",1);assertFalse(verify().valid());
        ((ObjectNode)response.path("quality")).put("verdict","partial").putArray("issues").add("Unreceived spans");assertTrue(verify().valid());
        Files.writeString(session.sessionDirectory().resolve("session.json"),"{\"sessionId\":\"other\"}");assertFalse(verify().valid());
    }
    @Test void emptySamplesRequireEmptyFindingsAndSchemaIsEnforced() throws Exception {
        report.put("observedRootTraces",0).put("analysisReady",false);((ObjectNode)response.path("quality")).put("verdict","insufficient").put("analysisReady",false).putArray("issues").add("No roots");
        assertFalse(verify().valid());((ArrayNode)response.path("findings")).removeAll();assertTrue(verify().valid());response.put("unexpected",true);assertFalse(verify().valid());
    }
    @Test void readsGzipAndLargeArtifactsWithoutMaterializingTheWholeSource() throws Exception {
        Path file=session.spansDirectory().resolve("large.json");
        try(var writer=Files.newBufferedWriter(file)){writer.write("{\"padding\":[");for(int i=0;i<6000;i++){if(i>0)writer.write(',');writer.write('"');writer.write("x".repeat(1024));writer.write('"');}writer.write("],\"durationNanos\":1000000,\"traceId\":\"trace-a\",\"rootMethod\":\"example.Service#work()V [BUSINESS]\"}");}
        Path gzip=file.resolveSibling("large.json.gz");try(var output=new GZIPOutputStream(Files.newOutputStream(gzip))){Files.copy(file,output);}
        evidence().put("file","spans/large.json.gz").put("jsonPointer","/durationNanos");var result=verify();assertTrue(result.valid(),result.errors().toString());
        evidence().put("file",responseFile.toString());assertFalse(verify().valid());
    }
    @Test void exactCountsAndRootPointersRemainValidButEscapedPointersAreNotConfused() throws Exception {
        report.putObject("a/b").put("~count",7);evidence().put("jsonPointer","/a~1b/~0count").putNull("traceId").putNull("method").put("value",7).put("unit","milliseconds");assertFalse(verify().valid());
        evidence().put("unit","count");assertTrue(verify().valid());
    }    @Test void floatingPointEvidenceNeverLosesDecimalPrecisionDuringStreaming() throws Exception {
        report.put("customPercent",new java.math.BigDecimal("0.1"));
        evidence().put("jsonPointer","/customPercent").putNull("traceId").putNull("method").put("unit","percent").put("value",new java.math.BigDecimal("0.10000000000000001"));
        assertFalse(verify().valid());evidence().put("value",new java.math.BigDecimal("0.1"));assertTrue(verify().valid());
    }
    @Test void diagnosticOnlyFindingsRequireRealSessionBoundSamplesAndRemainPartial() throws Exception {
        report.put("observedRootTraces",0).put("analysisReady",false);((ObjectNode)response.path("quality")).put("verdict","partial").put("analysisReady",false).putArray("issues").add("No completed trace report; platform-thread evidence only.");
        Path diagnostic=session.sessionDirectory().resolve("diagnostics");Files.createDirectories(diagnostic);
        Files.writeString(diagnostic.resolve("manifest.json"),"{\"schemaVersion\":1,\"sessionId\":\""+session.sessionId()+"\",\"samples\":1}");
        Files.writeString(diagnostic.resolve("latest.json"),"{\"threadCount\":7}");
        evidence().put("file","diagnostics/latest.json").put("jsonPointer","/threadCount").putNull("traceId").putNull("method").put("value",7).put("unit","count");
        assertTrue(verify().valid());
        Files.writeString(diagnostic.resolve("latest.json"),"{\"sessionId\":\"other\",\"threadCount\":7}");assertFalse(verify().valid());
        Files.writeString(diagnostic.resolve("latest.json"),"{\"sessionId\":\""+session.sessionId()+"\",\"threadCount\":7}");
        ((ObjectNode)response.path("quality")).put("verdict","usable");assertFalse(verify().valid());
        ((ObjectNode)response.path("quality")).put("verdict","partial");Files.writeString(diagnostic.resolve("manifest.json"),"{\"schemaVersion\":1,\"sessionId\":\""+session.sessionId()+"\",\"samples\":0}");assertFalse(verify().valid());
    }
    @Test void processCpuLoadUsesRatioAndHeapUsageUsesBytes() throws Exception {
        Path diagnostic=session.sessionDirectory().resolve("diagnostics");Files.createDirectories(diagnostic);
        Files.writeString(diagnostic.resolve("latest.json"),"{\"sessionId\":\""+session.sessionId()+"\",\"ProcessCpuLoad\":0.1,\"heap\":{\"used\":1000}}");
        evidence().put("file","diagnostics/latest.json").put("jsonPointer","/ProcessCpuLoad").putNull("traceId").putNull("method").put("value",new java.math.BigDecimal("0.1")).put("unit","ratio");assertTrue(verify().valid());
        evidence().put("jsonPointer","/heap/used").put("value",1000).put("unit","nanoseconds");assertFalse(verify().valid());evidence().put("unit","bytes");assertTrue(verify().valid());
    }
    @Test void independentRequestStatisticsSupportPartialFindingsWithZeroTraceSamples()throws Exception{
        report.put("observedRootTraces",0).put("analysisReady",false);((ObjectNode)response.path("quality")).put("verdict","partial").put("analysisReady",false).putArray("issues").add("No trace samples; independent Servlet window only");
        Path requests=session.agentLogsDirectory().resolve("request-metrics.json");Files.writeString(requests,json.writeValueAsString(Map.of("schemaVersion",1,"sessionId",session.sessionId(),"enabled",true,"status","FINAL","completedRequests",7,"endpoints",List.of(Map.of("endpoint","GET /orders","completed",7,"completedPerSecond",2.5)))));
        var store=new com.agentmonitor.core.evidence.EvidenceStore();store.index(session.sessionDirectory());JsonNode rows=json.valueToTree(store.query(session.sessionDirectory(),new com.agentmonitor.core.evidence.EvidenceStore.Query(null,null,null,null,"orders","request_metrics",10,0)));assertEquals("",rows.path("matches").get(0).path("method").asText());evidence().put("evidenceId",rows.path("matches").get(0).path("evidenceId").asText());
        evidence().put("file","agent-logs/request-metrics.json").put("jsonPointer","/endpoints/0/completed").putNull("traceId").putNull("method").put("value",7).put("unit","count");var valid=verify();assertTrue(valid.valid(),valid.errors().toString());
        evidence().put("unit","milliseconds");assertFalse(verify().valid());
        evidence().put("jsonPointer","/endpoints/0/completedPerSecond").put("value",2.5).put("unit","per_second");assertTrue(verify().valid());evidence().put("unit","ratio");assertFalse(verify().valid());
        Files.writeString(requests,Files.readString(requests).replace(session.sessionId(),"foreign"));assertFalse(verify().valid());
    }

}
