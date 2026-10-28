package com.agentmonitor.core.diagnostics;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Read-only comparisons aligned by stable method / SQL keys, with explicit sampling caveats. */
public final class SessionComparison {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public Map<String,Object> compare(Path before,Path after) throws Exception {return compare(before,after,true);}
    public Map<String,Object> compare(Path before,Path after,boolean strict) throws Exception {
        JsonNode a=read(before),b=read(after); List<String> warnings=new ArrayList<>();
        if(!a.path("captureComplete").asBoolean()||!b.path("captureComplete").asBoolean())warnings.add("至少一个会话采集不完整；差异只是观测结果。");
        for(String field:List.of("scope","sampling","dependencies","diagnostics"))
            if(!a.path("monitoringConfig").path(field).equals(b.path("monitoringConfig").path(field)))warnings.add("采集配置不同: "+field);
        warnings.add("未验证相同负载、请求构成、硬件和预热；采样差异不能直接证明优化收益。缺失条目表示未观测到。");
        var eligibility=eligibility(before,after,a,b);boolean derive=!strict||eligibility.isEmpty();
        Map<String,Object> result=new LinkedHashMap<>(Map.of("schemaVersion",1,"beforeSessionId",a.path("sessionId").asText(),"afterSessionId",b.path("sessionId").asText(),
                "warnings",warnings,"methods",delta(a.path("hotspots"),b.path("hotspots"),"method",derive),
                "sql",delta(a.path("diagnosticAggregates").path("sql"),b.path("diagnosticAggregates").path("sql"),"key",derive),
                "beforeConfig",a.path("monitoringConfig"),"afterConfig",b.path("monitoringConfig")));
        result.put("comparisonMode",strict?"strict":"observed");result.put("comparability",Map.of("eligible",eligibility.isEmpty(),"reasons",eligibility,"basis","Declared workload context, capture quality, environment and configuration; does not verify actual traffic or prove optimization."));result.put("optimizationProven",false);return result;
    }
    private static List<String> eligibility(Path before,Path after,JsonNode a,JsonNode b)throws Exception {
        List<String> reasons=new ArrayList<>();
        for(JsonNode report:List.of(a,b))if(!completeQuality(report))reasons.add("capture_complete_and_analysis_ready_required:"+report.path("sessionId").asText());
        for(String field:List.of("scope","sampling","dependencies","diagnostics","analysis","overhead")){JsonNode x=a.path("monitoringConfig").path(field),y=b.path("monitoringConfig").path(field);if(!x.isObject()||!y.isObject()||!x.equals(y))reasons.add("configuration_missing_or_different:"+field);}
        JsonNode captureA=a.path("monitoringConfig").path("output").path("capture"),captureB=b.path("monitoringConfig").path("output").path("capture");
        if(!captureA.isObject()||!captureB.isObject()||!captureA.equals(captureB))reasons.add("payload_capture_missing_or_different");
        for(String key:List.of("arguments","returnValue","sqlParameters"))if(!captureA.path(key).isBoolean()||!captureB.path(key).isBoolean())reasons.add("payload_capture_unknown:"+key);
        for(String field:List.of("workloadId","datasetId","loadProfile","warmupId","buildId")){JsonNode x=a.path("monitoringConfig").path("experiment").path(field),y=b.path("monitoringConfig").path("experiment").path(field);if(x.asText().isBlank()||y.asText().isBlank()||(!field.equals("buildId")&&!x.equals(y)))reasons.add("experiment_missing_or_different:"+field);}
        for(String field:List.of("service.name","process.runtime.name","process.runtime.version","host.name","host.arch","host.available_processors","os.name","os.version","process.runtime.arguments.sha256")){JsonNode x=a.path("resource").path("attributes").path(field),y=b.path("resource").path("attributes").path(field);if(x.asText().isBlank()||y.asText().isBlank()||!x.equals(y))reasons.add("environment_missing_or_different:"+field);}
        int n=0;for(Path path:List.of(before,after)) {JsonNode report=n++==0?a:b;Path absolute=path.toAbsolutePath().normalize();Path parent=absolute.getParent();Path root=Files.isDirectory(absolute)?absolute:parent.getFileName().toString().equals("reports")?parent.getParent():parent;
            Path marker=root.resolve("session.json"),manifest=root.resolve("spans/manifest.json");
            if(!Files.isRegularFile(marker)||!Files.isRegularFile(manifest))reasons.add("missing_session_or_raw_manifest:"+path);
            else{if(Files.size(marker)>65536||Files.size(manifest)>1024*1024)throw new IllegalArgumentException("Comparison metadata too large");JsonNode identity=JSON.readTree(marker.toFile()),raw=JSON.readTree(manifest.toFile());if(!identity.path("sessionId").asText().equals(report.path("sessionId").asText())||!raw.path("captureComplete").isBoolean()||!raw.path("captureComplete").asBoolean()||!raw.path("writtenSpans").isIntegralNumber()||!raw.path("writtenSpans").canConvertToLong()||raw.path("writtenSpans").asLong(-1)!=report.path("captureQuality").path("acceptedSpans").asLong(-2))reasons.add("raw_identity_or_completeness_mismatch:"+path);}
            Path overhead=root.resolve("agent-logs/overhead.json");
            if(!Files.isRegularFile(overhead)){reasons.add("missing_overhead_policy_evidence:"+path);continue;}
            if(Files.size(overhead)>1024*1024)throw new IllegalArgumentException("Overhead evidence exceeds 1MiB");JsonNode budget=JSON.readTree(overhead.toFile());if(!budget.path("status").asText().equals("FINAL")||!budget.path("sessionId").asText().equals(report.path("sessionId").asText()))reasons.add("overhead_session_mismatch:"+path);JsonNode policy=budget.path("policy");if(!zero(policy.path("omittedTransitions"))||!policy.path("mode").asText().equals("NORMAL")||!policy.path("transitions").isArray()||!policy.path("transitions").isEmpty())reasons.add("adaptive_policy_changed_or_unknown:"+path);
        }
        return List.copyOf(reasons);
    }
    private static boolean completeQuality(JsonNode report){
        if(!report.path("schemaVersion").isIntegralNumber()||report.path("schemaVersion").intValue()!=1||!report.path("status").asText().equals("COMPLETED")||!report.path("captureComplete").isBoolean()||!report.path("captureComplete").asBoolean()||!report.path("analysisReady").isBoolean()||!report.path("analysisReady").asBoolean())return false;
        JsonNode quality=report.path("captureQuality"),exporter=quality.path("agentExporter");
        if(!exporter.path("reported").isBoolean()||!exporter.path("reported").asBoolean())return false;
        for(String field:List.of("queueDroppedSpans","deliveryDroppedSpans","rejectedDestinations","pendingSpans"))if(!zero(exporter.path(field)))return false;
        for(String field:List.of("discardedSpans","unreceivedSpans"))if(!zero(quality.path(field)))return false;
        return quality.path("acceptedSpans").isIntegralNumber()&&quality.path("acceptedSpans").canConvertToLong()&&exporter.path("enqueuedSpans").isIntegralNumber()&&exporter.path("enqueuedSpans").canConvertToLong()&&quality.path("acceptedSpans").asLong(-1)>0&&quality.path("acceptedSpans").equals(exporter.path("enqueuedSpans"));
    }
    private static boolean zero(JsonNode value){return value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()==0;}
    private static JsonNode read(Path path) throws Exception {
        Path file=Files.isDirectory(path)?path.resolve("reports/performance-report.json"):path;
        if(Files.size(file)>64L*1024*1024)throw new IllegalArgumentException("报告超过 64MiB");
        JsonNode result=JSON.readTree(file.toFile());
        if(result==null||!result.isObject()||!result.has("sessionId"))throw new IllegalArgumentException("不是会话报告: "+file);
        return result;
    }
    private static List<Map<String,Object>> delta(JsonNode a,JsonNode b,String key,boolean derive) {
        Map<String,JsonNode> left=new LinkedHashMap<>(),right=new LinkedHashMap<>(); a.forEach(n->left.put(n.path(key).asText(),n)); b.forEach(n->right.put(n.path(key).asText(),n));
        Set<String> keys=new TreeSet<>(left.keySet());keys.addAll(right.keySet());List<Map<String,Object>> output=new ArrayList<>();
        for(String k:keys) {
            JsonNode x=left.get(k),y=right.get(k);Map<String,Object> row=new LinkedHashMap<>();row.put("key",k);
            row.put("before",x);row.put("after",y);
            if(derive&&x!=null&&y!=null&&x.path("observedCalls").asLong()>0&&y.path("observedCalls").asLong()>0) {
                double xm=x.path("inclusiveNanos").asDouble()/x.path("observedCalls").asLong(),ym=y.path("inclusiveNanos").asDouble()/y.path("observedCalls").asLong();
                row.put("observedMeanBeforeNanos",xm);row.put("observedMeanAfterNanos",ym);row.put("observedMeanDeltaNanos",ym-xm);
                if(xm>0)row.put("observedMeanChangePercent",100*(ym-xm)/xm);
            }
            output.add(row);
        }
        return output;
    }
    public Map<String,Object> compareHistograms(Path before,Path after) throws Exception {
        if(Files.size(before)>64L*1024*1024 || Files.size(after)>64L*1024*1024)throw new IllegalArgumentException("直方图超过 64MiB");
        JsonNode a=JSON.readTree(before.toFile()),b=JSON.readTree(after.toFile());
        if(a==null || b==null || !a.path("classes").isArray() || !b.path("classes").isArray())throw new IllegalArgumentException("需要对象直方图 JSON");
        Map<String,JsonNode> left=new LinkedHashMap<>(),right=new LinkedHashMap<>();
        a.path("classes").forEach(n->left.put(n.path("class").asText(),n));b.path("classes").forEach(n->right.put(n.path("class").asText(),n));
        Set<String> keys=new TreeSet<>(left.keySet());keys.addAll(right.keySet());List<Map<String,Object>> rows=new ArrayList<>();
        for(String k:keys) { JsonNode x=left.getOrDefault(k,JSON.createObjectNode()),y=right.getOrDefault(k,JSON.createObjectNode());
            Map<String,Object> row=new LinkedHashMap<>();row.put("class",k);row.put("beforeObserved",left.containsKey(k));row.put("afterObserved",right.containsKey(k));
            if(left.containsKey(k)&&right.containsKey(k)){row.put("instancesDelta",y.path("instances").asLong()-x.path("instances").asLong());row.put("bytesDelta",y.path("bytes").asLong()-x.path("bytes").asLong());}rows.add(row); }
        rows.sort(Comparator.<Map<String,Object>>comparingLong(m->((Number)m.getOrDefault("bytesDelta",0L)).longValue()).reversed());
        return Map.of("classes",rows,"limitations",List.of("增长不等于泄漏；结合 GC 后堆基线、分配速率和 GC roots 验证。", "直方图截断或 liveOnly 口径不一致会影响差异。"),"beforeLiveOnly",a.path("liveOnly"),"afterLiveOnly",b.path("liveOnly"));
    }
}
