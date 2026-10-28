package com.agentmonitor.core.diagnostics;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Deterministic triage, with source pointers and explicit hypotheses rather than an invented root cause. */
public final class SymptomDiagnosis {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static final Set<String> SYMPTOMS=Set.of("cpu","latency","memory","errors");
    public static Map<String,Object> plan(String symptom){
        if(!SYMPTOMS.contains(symptom))throw new IllegalArgumentException("--symptom: cpu|latency|memory|errors");
        List<String> steps=switch(symptom){
            case "cpu"->List.of("Collect JVM CPU ratios, thread CPU deltas and JFR samples","Correlate hot stacks with workload and GC; do not treat RUNNABLE as proof of CPU consumption");
            case "latency"->List.of("Collect independent Servlet request metrics and dependency traces using --config","Inspect connection acquisition, SQL, outbound HTTP, async queueing, locks and GC on a common timeline");
            case "memory"->List.of("Collect heap/non-heap/direct-buffer trends and JFR allocation/GC samples","Compare post-GC occupancy; histogram/heap dump is a separate explicit operation, not automatic");
            default->List.of("Collect Servlet status, exception spans and JFR throw events using --config","Separate propagated exception spans, thrown/caught exceptions and failed requests");
        };
        return Map.of("symptom",symptom,"steps",steps,"traceConfigRecommended",Set.of("latency","errors").contains(symptom),"automaticHeapDump",false);
    }
    public Map<String,Object> analyze(Path selected,String symptom)throws Exception{
        Map<String,Object> plan=plan(symptom);Path root=selected.toRealPath();JsonNode marker=read(root,"session.json",null),metrics=read(root,"diagnostics/latest.json",marker),manifest=read(root,"diagnostics/manifest.json",marker);
        JsonNode threads=read(root,"diagnostics/threads-latest.json",marker),report=read(root,"reports/performance-report.json",marker),requests=read(root,"agent-logs/request-metrics.json",marker);
        List<Map<String,Object>> findings=new ArrayList<>();List<String> missing=new ArrayList<>();
        if(metrics.isMissingNode())missing.add("JVM samples unavailable; run diagnose --pid or diag record");
        if(Set.of("cpu","latency").contains(symptom)&&metrics.path("ProcessCpuLoad").isNumber()&&metrics.path("ProcessCpuLoad").asDouble()>=.8)
            add(findings,"high_process_cpu","JVM process CPU ratio is high in the latest sample","medium","CPU pressure candidate; inspect hot JFR stacks and thread CPU deltas", "diagnostics/latest.json","/ProcessCpuLoad",metrics.path("ProcessCpuLoad"),"ratio");
        if(!threads.path("deadlockedThreadIds").isEmpty())add(findings,"deadlock","JVM reported deadlocked platform threads","high","Inspect lock owners and the corresponding code before changing concurrency", "diagnostics/threads-latest.json","/deadlockedThreadIds",threads.path("deadlockedThreadIds"),"thread_ids");
        if(symptom.equals("memory")&&manifest.path("heapTrend").path("lastUsedBytes").asLong(-1)>manifest.path("heapTrend").path("firstUsedBytes").asLong(Long.MAX_VALUE))
            add(findings,"heap_growth","Sampled heap occupancy increased during capture","low","May be allocation or normal GC cycling; retained-heap growth and a leak are not established", "diagnostics/manifest.json","/heapTrend",manifest.path("heapTrend"),"bytes");
        if(Set.of("latency","errors").contains(symptom)){
            if(requests.isMissingNode())missing.add("Independent Servlet request statistics missing; use capture/diagnose with --config and trigger real requests");
            if(!requests.isMissingNode()&&!requests.path("captureComplete").asBoolean())missing.add("Request metric window is running, disabled or has requests still pending at STOP");
            int i=0;for(JsonNode row:requests.path("endpoints")){
                if(row.path("errors").asLong()>0&&findings.size()<10)add(findings,"http_errors","Observed failed requests at "+row.path("endpoint").asText(),"high","Inspect corresponding status and exception evidence; unknown status is not a successful response", "agent-logs/request-metrics.json","/endpoints/"+i,row,"request_counts_and_histogram_ms");i++;
            }
            JsonNode deps=report.path("diagnosticAggregates");for(String kind:List.of("connectionAcquisition","sql","dependencies")){
                int index=0;for(JsonNode row:deps.path(kind)){if(findings.size()<10&&row.path("maxNanos").asLong()>100_000_000)
                    add(findings,"slow_dependency_candidate","Observed dependency operation exceeded 100ms","medium","Compare against the business latency budget; inclusive/parallel dependency durations must not be summed into request time", "reports/performance-report.json","/diagnosticAggregates/"+kind+"/"+index,row,"nanoseconds_and_observed_counts");index++;}
            }
        }
        if(!manifest.path("errors").isEmpty())missing.add("Diagnostic recorder reported errors; inspect diagnostics/manifest.json");
        return Map.of("schemaVersion",1,"sessionId",marker.path("sessionId").asText(),"sessionDirectory",root.toString(),"plan",plan,"findings",findings,"missingEvidence",missing,
                "rootCauseProven",false,"nextMeasurements",List.of("Use evidence index/query/get and timeline to verify candidates","Reproduce using the same workload and compare a single change"));
    }
    static JsonNode read(Path root,String relative,JsonNode marker)throws Exception{
        Path file=root.resolve(relative);if(!Files.isRegularFile(file)){if(relative.equals("session.json"))throw new IllegalArgumentException("Missing session.json");return JSON.missingNode();}Path actual=file.toRealPath();if(!actual.startsWith(root)||Files.size(actual)>16L*1024*1024)throw new IllegalArgumentException("Unsafe or oversized diagnostic source: "+relative);
        JsonNode node=JSON.readTree(actual.toFile());if(node==null||!node.isObject())throw new IllegalArgumentException("Invalid diagnostic source: "+relative);
        if(marker!=null&&node.has("sessionId")&&!marker.path("sessionId").asText().equals(node.path("sessionId").asText()))throw new IllegalArgumentException("Session identity mismatch: "+relative);
        if(marker==null&&(!node.path("sessionId").isTextual()||node.path("sessionId").asText().isBlank()))throw new IllegalArgumentException("Missing session identity");
        if(marker!=null&&relative.startsWith("agent-logs/request-metrics")&&!node.hasNonNull("sessionId"))throw new IllegalArgumentException("Missing request metric identity");
        String resourceId=node.path("attributes").path("agent.monitor.session.id").asText();if(marker!=null&&!resourceId.isBlank()&&!resourceId.equals(marker.path("sessionId").asText()))throw new IllegalArgumentException("Resource/session mismatch");return node;
    }
    private static void add(List<Map<String,Object>> list,String id,String observation,String confidence,String hypothesis,String file,String pointer,JsonNode value,String unit){
        list.add(Map.of("rule",id,"observation",observation,"confidence",confidence,"hypothesis",hypothesis,"evidence",Map.of("file",file,"jsonPointer",pointer,"value",value,"unit",unit)));
    }
}
