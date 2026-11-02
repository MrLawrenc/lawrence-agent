package com.agentmonitor.core.service;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OfflineTraceReplayLoaderTest {
    @TempDir Path directory;
    private static String span(String trace, String id, String parent) {
        return "{\"type\":\"span\",\"schemaVersion\":1,\"traceId\":\"" + trace + "\",\"spanId\":\"" + id + "\",\"parentSpanId\":\"" + parent
                + "\",\"className\":\"example.Service\",\"methodName\":\"work\",\"durationNanos\":42}";
    }
    @Test void reconstructsOutOfOrderParentsAcrossGzipAndJsonFilesAndDeduplicates() throws Exception {
        Files.writeString(directory.resolve("spans-00001.json"), "[" + span("t", "child", "root") + "]");
        try (var out = new GZIPOutputStream(Files.newOutputStream(directory.resolve("spans-00002.json.gz")))) {
            out.write(("[" + span("t", "root", "") + "," + span("t", "root", "") + "]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var replay = new OfflineTraceReplayLoader().load(directory);
        assertEquals(2, replay.acceptedSpans()); assertEquals(1, replay.skippedSpans());
        assertEquals(1, replay.roots().size()); assertEquals(1, replay.roots().get(0).getChildren().size());
        assertEquals(0, replay.orphanSpans()); assertEquals(0, replay.cyclicSpans());
    }
    @Test void separatesTraceIdsAndReportsOrphansAndCyclesWithoutAttachingThemToRoots() throws Exception {
        Files.writeString(directory.resolve("spans-1.json"), "[" + span("a", "root", "") + "," + span("b", "child", "root")
                + "," + span("c", "x", "y") + "," + span("c", "y", "x") + "]");
        var replay = new OfflineTraceReplayLoader().load(directory);
        assertEquals(1, replay.roots().size()); assertEquals(1, replay.orphanSpans()); assertEquals(2, replay.cyclicSpans());
        assertTrue(replay.roots().get(0).getChildren().isEmpty()); assertTrue(replay.hasDegradation());
    }
    @Test void preservesValidPrefixButWarnsAboutAnUnclosedActiveFile() throws Exception {
        Files.writeString(directory.resolve("spans-1.json"), "[" + span("t", "root", "") + ",");
        var replay = new OfflineTraceReplayLoader().load(directory);
        assertEquals(1, replay.roots().size()); assertEquals(1, replay.warnings().size()); assertTrue(replay.hasDegradation());
    }    @Test void exceptionEventsRemainAvailableToOfflineAiEvidence() throws Exception {
        String event=span("t","root","");
        event=event.substring(0,event.length()-1)+",\"events\":[{\"name\":\"exception\",\"timestampEpochMillis\":1,\"attributes\":{\"exception.type\":\"java.lang.IllegalStateException\",\"exception.message\":\"query failed\"}}]}";
        Files.writeString(directory.resolve("spans-1.json"),"["+event+"]");
        var replay=new OfflineTraceReplayLoader().load(directory);
        assertEquals(1,replay.acceptedSpans());
        assertEquals("query failed",replay.roots().get(0).getAttributes().get("otel.exception.message"));
        assertTrue(replay.roots().get(0).getAttributes().get("otel.events").contains("IllegalStateException"));
    }

    @Test void rollingJsonlReplaysOutOfOrderChildrenAndRejectsForeignRecords()throws Exception{
        Files.writeString(directory.resolve("session.json"),"{\"sessionId\":\"window\"}");Path spans=Files.createDirectory(directory.resolve("spans"));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var rows=new java.util.ArrayList<String>();
        for(String[] values:java.util.List.of(new String[]{"window","child","root","BUSINESS"},new String[]{"window","root","","HTTP_SERVER"},new String[]{"foreign","bad","","BUSINESS"})){
            var row=json.createObjectNode();row.put("sessionId",values[0]);row.put("traceId","trace");row.put("method","example.Service#work()V ["+values[3]+"]");row.put("threadName","request");row.put("durationNanos",1000000);row.put("startedAtEpochMillis",123);row.putObject("attributes").put("agent.span.id",values[1]).put("agent.span.parent_id",values[2]);rows.add(row.toString());
        }
        Files.writeString(spans.resolve("rolling-spans.jsonl"),String.join("\n",rows)+"\n");assertTrue(OfflineTraceReplayLoader.hasReplayFiles(directory));var result=new OfflineTraceReplayLoader().load(directory);assertEquals(2,result.acceptedSpans());assertEquals(1,result.roots().size());assertEquals(1,result.roots().get(0).getChildren().size());assertTrue(result.warnings().stream().anyMatch(w->w.contains("会话身份")));
    }

    @Test void rollingRemoteParentRemainsALocalRoot()throws Exception{
        Files.writeString(directory.resolve("session.json"),"{\"sessionId\":\"window\"}");Files.writeString(directory.resolve("rolling-spans.jsonl"),"{\"sessionId\":\"window\",\"traceId\":\"trace\",\"method\":\"example.Controller#request()V [HTTP_SERVER]\",\"durationNanos\":10,\"attributes\":{\"agent.span.id\":\"server\",\"agent.span.parent_id\":\"remote\",\"agent.remote_parent\":\"true\"}}\n");var result=new OfflineTraceReplayLoader().load(directory);assertEquals(1,result.roots().size());assertEquals(0,result.orphanSpans());assertEquals("remote",result.roots().get(0).getAttributes().get("agent.span.parent_id"));
    }

    @Test void rollingOversizedLineStopsThatFileWithAnExplicitWarning()throws Exception{
        Files.writeString(directory.resolve("session.json"),"{\"sessionId\":\"window\"}");Files.writeString(directory.resolve("rolling-spans.jsonl"),"x".repeat(4*1024*1024+10)+"\n");var result=new OfflineTraceReplayLoader().load(directory);assertEquals(0,result.acceptedSpans());assertTrue(result.warnings().stream().anyMatch(w->w.contains("4Mi 字符")&&w.contains("余下记录")));
    }

}
