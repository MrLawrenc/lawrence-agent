package com.agentmonitor.core.diagnostics;

import com.agentmonitor.core.service.OfflineTraceReplayLoader;
import com.agentmonitor.model.trace.TraceNode;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Joins only exact W3C trace/span parent identities; never infers a remote call from timing. */
public final class DistributedTrace {
    private static final ObjectMapper JSON=new ObjectMapper();
    public Map<String,Object> correlate(List<Path> sessions,String traceId)throws Exception{
        if(sessions.isEmpty()||sessions.size()>32||!traceId.matches("[0-9a-f]{32}")||traceId.equals("0".repeat(32)))throw new IllegalArgumentException("1–32 sessions and a valid --trace are required");
        Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();List<Map<String,Object>> sources=new ArrayList<>();List<String> warnings=new ArrayList<>();
        Set<Path> visited=new HashSet<>();
        for(Path selected:sessions){
            Path root=selected.toRealPath();if(!visited.add(root))continue;
            JsonNode marker=SymptomDiagnosis.read(root,"session.json",null),resource=SymptomDiagnosis.read(root,"resource.json",marker),report=SymptomDiagnosis.read(root,"reports/performance-report.json",marker);
            JsonNode raw=SymptomDiagnosis.read(root,"spans/manifest.json",marker);String rawId=raw.path("resource").path("agent.monitor.session.id").asText();
            if(!rawId.isBlank()&&!rawId.equals(marker.path("sessionId").asText()))throw new IllegalArgumentException("Raw spans belong to another session");
            if(rawId.isBlank())warnings.add("Raw manifest lacks capture identity: "+root);
            var replay=new OfflineTraceReplayLoader().load(root);warnings.addAll(replay.warnings());if(replay.hasDegradation())warnings.add("Incomplete replay: "+root);
            sources.add(Map.of("sessionId",marker.path("sessionId").asText(),"sessionDirectory",root.toString(),"localCaptureComplete",report.path("captureComplete").asBoolean(false),"orphanSpans",replay.orphanSpans(),"skippedSpans",replay.skippedSpans()));
            String service=resource.path("service.name").asText(resource.path("attributes").path("service.name").asText("unknown"));
            for(TraceNode localRoot:replay.roots()){
                if(!traceId.equals(localRoot.getTraceId()))continue;ArrayDeque<TraceNode> pending=new ArrayDeque<>();pending.add(localRoot);
                while(!pending.isEmpty()){
                    if(nodes.size()>=10000)throw new IllegalArgumentException("Trace exceeds 10000 spans; narrow the supplied sessions");
                    TraceNode node=pending.removeFirst();Map<String,String> attrs=node.getAttributes();String span=attrs.getOrDefault("agent.span.id","");
                    if(!span.matches("[0-9a-f]{16}"))throw new IllegalArgumentException("Invalid span identity in distributed evidence");
                    Map<String,Object> row=new LinkedHashMap<>();row.put("spanId",span);row.put("parentSpanId",attrs.getOrDefault("agent.span.parent_id",""));row.put("service",service);row.put("sessionId",marker.path("sessionId").asText());
                    row.put("method",node.getFullClassName()+"#"+node.getMethodName2());row.put("kind",node.getKind().name());row.put("durationNanos",node.getDurationNanos());row.put("startedAtEpochMillis",attrs.getOrDefault("agent.span.started_at_epoch_millis",""));row.put("error",node.isError());row.put("localRootSpanId",attrs.getOrDefault("agent.local.root_span_id",""));row.put("serviceInstance",resource.path("attributes").path("service.instance.id").asText("unknown"));row.put("propagationStatus",attrs.getOrDefault("agent.propagation.status","not_applicable"));row.put("durationScope",attrs.getOrDefault("agent.http.duration_scope",attrs.getOrDefault("agent.dependency.phase","instrumented_method")));
                    if(nodes.putIfAbsent(span,row)!=null)throw new IllegalArgumentException("Duplicate span identity across sessions: "+span);pending.addAll(node.getChildren());
                }
            }
        }
        List<Map<String,Object>> edges=new ArrayList<>(),missing=new ArrayList<>();
        for(var entry:nodes.entrySet()){
            String parent=entry.getValue().get("parentSpanId").toString();if(parent.isBlank())continue;
            Map<String,Object> upstream=nodes.get(parent);
            if(upstream==null)missing.add(Map.of("spanId",entry.getKey(),"missingParentSpanId",parent));
            else edges.add(Map.of("parentSpanId",parent,"spanId",entry.getKey(),"fromService",upstream.get("service"),"toService",entry.getValue().get("service"),"crossSession",!upstream.get("sessionId").equals(entry.getValue().get("sessionId"))));
        }
        return Map.of("schemaVersion",1,"traceId",traceId,"sources",sources,"spans",List.copyOf(nodes.values()),"edges",edges,"missingParents",missing,"warnings",warnings,"crossServiceComplete",false,
                "coverage","Supplied sessions only. Exact parent links, no timing-based inference. Unknown downstream calls, sampling and clock skew prevent a full-path completeness or critical-path claim.");
    }
}
