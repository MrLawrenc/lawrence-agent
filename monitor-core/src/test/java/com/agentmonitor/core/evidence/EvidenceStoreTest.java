package com.agentmonitor.core.evidence;

import com.agentmonitor.core.model.MonitoringSession;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {
    @TempDir Path directory;
    ObjectMapper json=new ObjectMapper();
    @Test void indexesHistoricalJsonlThreadsAndExactDecimalsAndRejectsChangedSource()throws Exception {
        var session=MonitoringSession.create(directory.toString());Path diag=session.sessionDirectory().resolve("diagnostics");Files.createDirectories(diag);
        Files.writeString(diag.resolve("metrics.jsonl"),"{\"sessionId\":\""+session.sessionId()+"\",\"timestampMillis\":1000,\"ProcessCpuLoad\":0.10000000000000001}\n{\"sessionId\":\""+session.sessionId()+"\",\"timestampMillis\":2000,\"ProcessCpuLoad\":0.2}\n");
        Files.writeString(diag.resolve("threads.jsonl"),"{\"sessionId\":\""+session.sessionId()+"\",\"timestampMillis\":1000,\"threads\":[{\"threadId\":42,\"state\":\"BLOCKED\"}]}\n");
        EvidenceStore store=new EvidenceStore();assertEquals(4,((Number)store.index(session.sessionDirectory()).get("records")).intValue());
        var rows=json.valueToTree(store.query(session.sessionDirectory(),new EvidenceStore.Query(900L,1100L,null,null,null,"metrics",10,0))).path("matches");assertEquals(1,rows.size());
        String id=rows.get(0).path("evidenceId").asText();var value=json.valueToTree(store.get(session.sessionDirectory(),id));assertTrue(value.path("verified").asBoolean());
        assertEquals("0.10000000000000001",value.path("value").path("ProcessCpuLoad").asText());assertEquals(1,value.path("line").asInt());
        store.verifyReference(session.sessionDirectory(),id,"diagnostics/metrics.jsonl",1,"/ProcessCpuLoad");
        assertThrows(IllegalArgumentException.class,()->store.verifyReference(session.sessionDirectory(),id,"diagnostics/metrics.jsonl",2,"/ProcessCpuLoad"));
        var threads=json.valueToTree(store.query(session.sessionDirectory(),new EvidenceStore.Query(900L,1100L,null,"42",null,"threads",10,0))).path("matches");assertEquals(1,threads.size());assertEquals("/threads/0",threads.get(0).path("jsonPointer").asText());
        Files.writeString(diag.resolve("metrics.jsonl"),"{\"timestampMillis\":1000,\"ProcessCpuLoad\":0.9}\n");assertThrows(IllegalArgumentException.class,()->store.get(session.sessionDirectory(),id));
    }
    @Test void rawSpanArrayIsIndexedWithoutMaterializingTheWholeFile()throws Exception {
        var s=MonitoringSession.create(directory.toString());Path file=s.spansDirectory().resolve("spans-0001.json");
        try(var writer=Files.newBufferedWriter(file)){writer.write("{\"spans\":[");for(int i=0;i<12000;i++){if(i>0)writer.write(',');writer.write("{\"traceId\":\"t"+i+"\",\"className\":\"example.Service\",\"methodName\":\"call\",\"signature\":\"()V\",\"startedAtEpochMillis\":1000,\"durationNanos\":1000000,\"padding\":\""+"x".repeat(512)+"\"}");}writer.write("]}");}
        EvidenceStore store=new EvidenceStore();store.index(s.sessionDirectory());var result=json.valueToTree(store.query(s.sessionDirectory(),new EvidenceStore.Query(null,null,"t11999",null,null,null,1,0)));
        String id=result.path("matches").get(0).path("evidenceId").asText();var value=json.valueToTree(store.get(s.sessionDirectory(),id));assertEquals("t11999",value.path("value").path("traceId").asText());
    }
    @Test void temporalJoinVerifiesSourcesAndDoesNotClaimCausality()throws Exception {
        var s=MonitoringSession.create(directory.toString());Path diag=s.sessionDirectory().resolve("diagnostics");Files.createDirectories(diag);
        Files.writeString(s.spansDirectory().resolve("rolling-spans.jsonl"),"{\"sessionId\":\""+s.sessionId()+"\",\"traceId\":\"t\",\"method\":\"example#call()V [BUSINESS]\",\"startedAtEpochMillis\":1000,\"durationNanos\":500000000,\"attributes\":{\"thread.id\":\"42\"}}\n");
        Files.writeString(diag.resolve("jfr-analysis.json"),"{\"timelineEvents\":[{\"eventType\":\"jdk.ThreadPark\",\"startedAtEpochMillis\":1100,\"durationNanos\":100000000,\"javaThreadId\":42}]}");
        var result=json.valueToTree(new TimelineService().query(s.sessionDirectory(),900L,1600L,"t",null,100));assertTrue(result.path("sourceHashesVerified").asBoolean());
        assertEquals(2,result.path("events").size());assertEquals("same_thread_time_window",result.path("events").get(1).path("association").asText());assertFalse(result.path("events").get(1).path("causalityEstablished").asBoolean());
    }
    @Test void indexAndSourcePathTamperingCannotEscapeSession()throws Exception {
        var s=MonitoringSession.create(directory.toString());Path outside=directory.resolve("outside.jsonl");Files.writeString(outside,"{}\n");Files.createSymbolicLink(s.spansDirectory().resolve("evil.jsonl"),outside);
        EvidenceStore store=new EvidenceStore();assertEquals(0,((Number)store.index(s.sessionDirectory()).get("records")).intValue());
        var catalog=json.readTree(s.sessionDirectory().resolve("evidence/catalog.json").toFile());Path index=s.sessionDirectory().resolve("evidence").resolve(catalog.path("index").asText());Files.writeString(index,"{}\n");
        assertThrows(Exception.class,()->store.query(s.sessionDirectory(),new EvidenceStore.Query(null,null,null,null,null,null,10,0)));
    }
    @Test void foreignSessionRecordsAndContainerIdentityAreRejectedBeforePublication()throws Exception {
        var s=MonitoringSession.create(directory.toString());Path diag=s.sessionDirectory().resolve("diagnostics");Files.createDirectories(diag);Path source=diag.resolve("metrics.jsonl");
        Files.writeString(source,"{\"sessionId\":\"different\",\"timestampMillis\":1000}\n");var store=new EvidenceStore();assertThrows(IllegalArgumentException.class,()->store.index(s.sessionDirectory()));assertFalse(Files.exists(s.sessionDirectory().resolve("evidence/catalog.json")));
        Files.delete(source);Files.writeString(diag.resolve("jfr-analysis.json"),"{\"timelineEvents\":[{\"startedAtEpochMillis\":1000}],\"sessionId\":\"different\"}");assertThrows(IllegalArgumentException.class,()->store.index(s.sessionDirectory()));
    }
    @Test void capacityTruncationIsDisclosedEvenInsideOneLargeArray()throws Exception {
        var s=MonitoringSession.create(directory.toString());try(var writer=Files.newBufferedWriter(s.spansDirectory().resolve("spans-00001.json"))){writer.write('[');for(int i=0;i<200001;i++){if(i>0)writer.write(',');writer.write("{\"traceId\":\"t\"}");}writer.write(']');}
        var store=new EvidenceStore();var catalog=store.index(s.sessionDirectory());assertTrue((Boolean)catalog.get("truncated"));assertTrue(((Number)catalog.get("records")).intValue()<200001);
        var query=store.query(s.sessionDirectory(),new EvidenceStore.Query(null,null,"t",null,null,null,1,0));assertTrue((Boolean)query.get("truncatedIndex"));
    }
    @Test void focusSourceIsVerifiedEvenWhenOutsideTheReturnedTimeWindow()throws Exception {
        var s=MonitoringSession.create(directory.toString());Path span=s.spansDirectory().resolve("rolling-spans.jsonl");Files.writeString(span,"{\"traceId\":\"t\",\"startedAtEpochMillis\":1000,\"durationNanos\":1000000,\"attributes\":{\"thread.id\":\"42\"}}\n");Path diag=s.sessionDirectory().resolve("diagnostics");Files.createDirectories(diag);Files.writeString(diag.resolve("threads.jsonl"),"{\"timestampMillis\":6000,\"threads\":[{\"threadId\":42}]}\n");
        new EvidenceStore().index(s.sessionDirectory());Files.writeString(span,"{\"traceId\":\"t\",\"startedAtEpochMillis\":1000,\"attributes\":{\"thread.id\":\"7\"}}\n");
        assertThrows(IllegalArgumentException.class,()->new TimelineService().query(s.sessionDirectory(),5000L,7000L,"t",null,10));
    }
}
