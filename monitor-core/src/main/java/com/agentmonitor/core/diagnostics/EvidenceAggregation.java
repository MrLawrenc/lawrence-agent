package com.agentmonitor.core.diagnostics;

import com.agentmonitor.model.trace.TraceNode;
import java.util.*;

/** Aggregates sampled evidence, not service-wide rates or population percentiles. */
public final class EvidenceAggregation {
    private static final int CAPACITY=8192, TRACE_LIMIT=256;
    private final Map<String,Stats> sql = new LinkedHashMap<>(), errors=new LinkedHashMap<>(), connections=new LinkedHashMap<>();
    private final Map<String,Stats> dependencies=new LinkedHashMap<>();
    private final List<Map<String,Object>> repeated=new ArrayList<>(), links=new ArrayList<>();
    private long omittedSql, omittedErrors, omittedLinks, omittedRepeats;
    public void accept(String traceId, TraceNode root) {
        Map<String,Integer> counts=new LinkedHashMap<>(); ArrayDeque<TraceNode> pending=new ArrayDeque<>(); pending.add(root);
        while (!pending.isEmpty()) {
            TraceNode node=pending.removeFirst(); Map<String,String> attrs=node.getAttributes();
            if(Set.of("HTTP_CLIENT","REDIS","MESSAGING").contains(node.getKind().name())){
                String key=node.getKind()+" "+limit(attrs.getOrDefault("server.address","unknown"),256)+" "+limit(node.getMethodName2(),64)+" ["+attrs.getOrDefault("agent.dependency.phase","unknown")+"]";
                if(dependencies.size()<CAPACITY||dependencies.containsKey(key))dependencies.computeIfAbsent(key,k->new Stats()).add(node.getDurationNanos(),node.isError());
            }
            String fingerprint=attrs.getOrDefault("sqlFingerprint", attrs.getOrDefault("db.query.summary",""));
            if (!fingerprint.isBlank()) {
                fingerprint=limit(fingerprint,2048);
                if (!sql.containsKey(fingerprint) && sql.size()>=CAPACITY) omittedSql++;
                else { sql.computeIfAbsent(fingerprint,k->new Stats()).add(node.getDurationNanos(), node.isError()); counts.merge(fingerprint,1,Integer::sum); }
            }
            if (node.isError()) {
                String type=attrs.getOrDefault("otel.exception.type",attrs.getOrDefault("error.type","unknown"));
                String stack=attrs.getOrDefault("otel.exception.stacktrace",node.getStackTrace());
                String key=limit(type,256)+"\n"+limit(stack,4096);
                if (!errors.containsKey(key) && errors.size()>=CAPACITY) omittedErrors++;
                else errors.computeIfAbsent(key,k->new Stats()).add(node.getDurationNanos(),true);
            }
            if (attrs.containsKey("agent.pool.connection_wait_nanos")) {
                String pool=node.getFullClassName();
                if (connections.size()<128 || connections.containsKey(pool)) connections.computeIfAbsent(pool,k->new Stats()).add(node.getDurationNanos(),node.isError());
            }
            if (attrs.containsKey("agent.async.parent.trace_id")) {
                if (links.size()<TRACE_LIMIT) links.add(Map.of("traceId",traceId,"parentTraceId",attrs.get("agent.async.parent.trace_id"),
                        "parentSpanId",attrs.getOrDefault("agent.async.parent.span_id",""),"queueNanos",attrs.getOrDefault("agent.async.queue_nanos","0"),
                        "thread", node.getThread(), "executionNanos",node.getDurationNanos())); else omittedLinks++;
            }
            pending.addAll(node.getChildren());
        }
        for (var entry:counts.entrySet()) if(entry.getValue()>=5) {
            if(repeated.size()<TRACE_LIMIT) repeated.add(Map.of("traceId",traceId,"sqlFingerprint",entry.getKey(),"observedExecutions",entry.getValue(),
                    "classification","repeated_query_candidate","verification","Verify loop and bound parameters; repeated fingerprints alone do not prove N+1."));
            else omittedRepeats++;
        }
    }
    public Map<String,Object> document() {
        return Map.of("scope","observed_sampled_completed_traces", "sql",documents(sql), "exceptions",documents(errors),
                "connectionAcquisition",documents(connections),"repeatedQueries",List.copyOf(repeated),"asyncLinks",List.copyOf(links),"dependencies",documents(dependencies),
                "limits",Map.of("omittedSqlOccurrences",omittedSql,"omittedErrorOccurrences",omittedErrors,"omittedAsyncLinks",omittedLinks,"omittedRepeatedQueries",omittedRepeats),
                "limitations",List.of("Exception counts are error spans and may count a propagated exception multiple times. JFR exceptionThrows measures observed throw events separately.",
                        "Async tasks use separate root traces linked to submission context. Parallel durations must not be summed as request critical-path time.",
                        "SQL fingerprints can be truncated. No global P95/P99, failure rate, QPS or causal claim can be inferred from tail-biased samples."));
    }
    private static List<Map<String,Object>> documents(Map<String,Stats> source) {
        return source.entrySet().stream().sorted(Comparator.<Map.Entry<String,Stats>>comparingLong(e->e.getValue().total).reversed())
                .map(e->e.getValue().document(e.getKey())).toList();
    }
    private static String limit(String s,int n) { return s.substring(0,Math.min(s.length(),n)); }
    private static final class Stats {
        long count,errors,total,max;
        void add(long nanos,boolean error) { count++; if(error)errors++; total=Long.MAX_VALUE-total<nanos?Long.MAX_VALUE:total+nanos; max=Math.max(max,nanos); }
        Map<String,Object> document(String key) { return Map.of("key",key,"observedCalls",count,"observedErrors",errors,"inclusiveNanos",total,"maxNanos",max,"meanNanos",count==0?0:total/count); }
    }
}
