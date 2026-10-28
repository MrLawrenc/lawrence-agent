package com.agentmonitor.core.evidence;

import java.nio.file.Path;
import java.util.*;

/** Verified source provenance with explicit temporal associations; never a root-cause classifier. */
public final class TimelineService {
    public Map<String,Object> query(Path session,Long fromMillis,Long toMillis,String traceId,String threadId,int limit)throws Exception {
        if(limit<1||limit>200)throw new IllegalArgumentException("Timeline limit: 1–200");
        EvidenceStore store=new EvidenceStore();List<Map<String,Object>> focus=List.of();
        if(traceId!=null&&!traceId.isBlank()) {
            focus=store.timelineRows(session,new EvidenceStore.Query(null,null,traceId,null,null,null,200,0));
            if(focus.isEmpty())throw new IllegalArgumentException("Trace is not present in indexed evidence");
            if(fromMillis==null)fromMillis=focus.stream().mapToLong(n->((Number)n.get("startedAtMillis")).longValue()).min().orElse(0)-5000;
            if(toMillis==null)toMillis=focus.stream().mapToLong(n->((Number)n.get("endedAtMillis")).longValue()).max().orElse(0)+5000;
        }
        if(fromMillis==null||toMillis==null||fromMillis>toMillis||toMillis-fromMillis>24L*3600*1000)throw new IllegalArgumentException("Supply a time range <=24h or a traceId");
        List<Map<String,Object>> rows=store.timelineRows(session,new EvidenceStore.Query(fromMillis,toMillis,null,null,null,null,200,0));
        Set<String> sources=new HashSet<>();rows.forEach(n->sources.add(n.get("file").toString()));focus.forEach(n->sources.add(n.get("file").toString()));store.verifySources(session,sources);
        Set<String> focusThreads=new HashSet<>();if(threadId!=null&&!threadId.isBlank())focusThreads.add(threadId);for(var n:focus)if(!n.get("threadId").toString().isBlank())focusThreads.add(n.get("threadId").toString());
        List<Map<String,Object>> events=new ArrayList<>();
        for(var original:rows){String tid=original.get("threadId").toString();boolean sameThread=!tid.isBlank()&&focusThreads.contains(tid);
            if(threadId!=null&&!threadId.isBlank()&&!tid.isBlank()&&!sameThread)continue;
            Map<String,Object> row=new LinkedHashMap<>(original);row.put("association",sameThread?"same_thread_time_window":"process_time_window_only");row.put("causalityEstablished",false);events.add(row);
        }
        events.sort(Comparator.comparingLong(n->((Number)n.get("startedAtMillis")).longValue()));var coverage=store.query(session,new EvidenceStore.Query(fromMillis,toMillis,null,null,null,null,1,0));
        boolean truncated=events.size()>limit||rows.size()>=5000||Boolean.TRUE.equals(coverage.get("truncatedIndex"));
        Map<String,Object> result=new LinkedHashMap<>();result.put("schemaVersion",1);result.put("fromMillis",fromMillis);result.put("toMillis",toMillis);result.put("traceId",traceId);result.put("threadId",threadId);result.put("events",List.copyOf(events.subList(0,Math.min(limit,events.size()))));result.put("truncated",truncated);result.put("sourceHashesVerified",true);result.put("indexTruncated",coverage.get("truncatedIndex"));result.put("indexErrors",coverage.get("indexErrors"));result.put("limitations",List.of("Time-window overlap and even matching thread IDs establish association, not causation or exact per-request CPU.","GC events may overlap/nest; do not sum GC phases and whole collections.","Index truncation and omitted JFR events can hide evidence. Each index is a snapshot; rebuild after source changes."));return result;
    }
}
