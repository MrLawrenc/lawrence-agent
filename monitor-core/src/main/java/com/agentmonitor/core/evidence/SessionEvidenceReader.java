package com.agentmonitor.core.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Stateful, bounded reader. One instance belongs to one sequential consumer, never to a JVM. */
public final class SessionEvidenceReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_JSON = 64L * 1024 * 1024;
    private static final int MAX_LINE = 4 * 1024 * 1024, READ_BUDGET = 8 * 1024 * 1024;
    private static final Map<String,String> FILES = Map.ofEntries(
            Map.entry("identity", "session.json"), Map.entry("live", "agent-logs/live-invocations.json"),
            Map.entry("threads", "diagnostics/threads-latest.json"), Map.entry("classes", "agent-logs/class-origins.json"),
            Map.entry("latest", "diagnostics/latest.json"), Map.entry("manifest", "diagnostics/manifest.json"),
            Map.entry("environment", "diagnostics/environment.json"), Map.entry("report", "reports/performance-report.json"),
            Map.entry("jfr", "diagnostics/jfr-analysis.json"), Map.entry("trigger", "diagnostics/trigger.json"));
    private record Cached(Object key, long size, java.nio.file.attribute.FileTime modified, JsonNode value, java.time.Instant readAt) { }
    private final Map<String,Cached> cache = new HashMap<>();
    private final ArrayDeque<JsonNode> metrics = new ArrayDeque<>();
    private Path directory;
    private Object metricsKey;
    private long offset;
    private byte[] boundary = new byte[0];
    private long badLines;
    private boolean skippingLine;
    private String sessionId="";

    public Map<String,Object> read(Path selected) throws IOException {
        Path root = selected.toRealPath();
        if (!root.equals(directory)) { directory=root; cache.clear(); metrics.clear(); offset=0; metricsKey=null; boundary=new byte[0]; badLines=0;skippingLine=false; }
        Map<String,Object> result = new LinkedHashMap<>(); List<String> warnings = new ArrayList<>();
        Path marker=root.resolve("session.json");
        if(!Files.isRegularFile(marker)||Files.isSymbolicLink(marker)||Files.size(marker)>65536)throw new IOException("缺少有效 session.json，不能核验会话身份");
        JsonNode identity=JSON.readTree(marker.toFile());
        String actualId=identity==null?"":identity.path("sessionId").asText();
        if(actualId.isBlank())throw new IOException("session.json 缺少 sessionId");
        if(!actualId.equals(sessionId)){sessionId=actualId;cache.clear();metrics.clear();offset=0;metricsKey=null;boundary=new byte[0];skippingLine=false;badLines=0;}
        for (var entry : FILES.entrySet()) {
            String key=entry.getKey(); Path file=root.resolve(entry.getValue());
            try {
                if (!Files.exists(file)) { cache.remove(key); warnings.add(entry.getValue()+"：未采集或尚未生成"); result.put(key, Map.of()); continue; }
                if (!file.toRealPath().startsWith(root)) throw new IOException("路径超出会话目录");
                var attrs=Files.readAttributes(file, BasicFileAttributes.class);
                if (attrs.size()>MAX_JSON) throw new IOException("超过 64MiB 读取上限");
                Cached previous=cache.get(key);
                if (previous==null || !Objects.equals(previous.key(),attrs.fileKey()) || previous.size()!=attrs.size() || !previous.modified().equals(attrs.lastModifiedTime())) {
                    JsonNode value=JSON.readTree(file.toFile());
                    if (value==null || !value.isObject()) throw new IOException("需要 JSON 对象");
                    verifyIdentity(key,value);
                    cache.put(key,new Cached(attrs.fileKey(),attrs.size(),attrs.lastModifiedTime(),value,java.time.Instant.now()));
                }
            } catch (IdentityMismatch error){cache.remove(key);warnings.add(entry.getValue()+"："+error.getMessage()+"（已拒绝显示）");
            } catch (Exception error) { warnings.add(entry.getValue()+"："+error.getMessage()+(cache.containsKey(key)?"（保留上次成功数据："+cache.get(key).readAt()+"）":"")); }
            result.put(key,cache.containsKey(key)?cache.get(key).value():Map.of());
        }
        readMetrics(root,warnings);
        result.put("metrics",List.copyOf(metrics)); result.put("warnings",warnings);
        result.put("metricsReadOffset",offset); result.put("invalidMetricLines",badLines);
        // Public boundary uses JDK types; JSON libraries remain an implementation detail.
        return JSON.convertValue(result, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { });
    }

    private static final class IdentityMismatch extends IOException { IdentityMismatch(String message){super(message);} }
    private void verifyIdentity(String key,JsonNode value)throws IdentityMismatch {
        String supplied=value.path("sessionId").asText();
        if((!supplied.isBlank()&&!sessionId.equals(supplied))||(java.util.Set.of("report","manifest").contains(key)&&supplied.isBlank()))throw new IdentityMismatch("会话身份不匹配或缺失：期望 "+sessionId+"，实际 "+(supplied.isBlank()?"未知":supplied));
        String resource=value.path("attributes").path("agent.monitor.session.id").asText();if(!resource.isBlank()&&!sessionId.equals(resource))throw new IdentityMismatch("资源会话身份不匹配");
    }
    private void readMetrics(Path root,List<String> warnings) {
        Path file=root.resolve("diagnostics/metrics.jsonl");
        if (!Files.exists(file)) { metrics.clear();offset=0;metricsKey=null;boundary=new byte[0];warnings.add("diagnostics/metrics.jsonl：未采集或尚未生成");return; }
        try {
            if (!file.toRealPath().startsWith(root)) throw new IOException("路径超出会话目录");
            var attrs=Files.readAttributes(file,BasicFileAttributes.class);
            try (var input=new RandomAccessFile(file.toFile(),"r")) {
                boolean reset=!Objects.equals(metricsKey,attrs.fileKey()) || attrs.size()<offset;
                if (!reset && boundary.length>0) { byte[] actual=new byte[boundary.length];input.seek(offset-boundary.length);input.readFully(actual);reset=!Arrays.equals(actual,boundary); }
                if (reset) { offset=0;metrics.clear();badLines=0;skippingLine=false; }
                metricsKey=attrs.fileKey(); input.seek(offset);
                byte[] chunk=new byte[(int)Math.min(READ_BUDGET,Math.max(0,input.length()-offset))];input.readFully(chunk);
                ByteArrayOutputStream line=new ByteArrayOutputStream();long position=offset;
                for(byte next:chunk){
                    position++;
                    if(next=='\n') {
                        if(!skippingLine&&line.size()>0)try { JsonNode row=JSON.readTree(line.toString(StandardCharsets.UTF_8));if(row==null||!row.isObject())throw new IOException();verifyIdentity("metrics",row);metrics.add(row);if(metrics.size()>240)metrics.removeFirst(); }catch(Exception invalid){badLines++;}
                        line.reset();skippingLine=false;offset=position;
                    }else if(!skippingLine){if(line.size()<MAX_LINE)line.write(next);else{badLines++;line.reset();skippingLine=true;}}
                    if(skippingLine)offset=position;
                }
                boundary=new byte[(int)Math.min(64,offset)];input.seek(offset-boundary.length);input.readFully(boundary);
                if(offset<input.length())warnings.add("指标仍在读取或末行尚未完整；下次刷新继续");
            }
            if(badLines>0)warnings.add("指标损坏、身份不符或超限行："+badLines+"，已跳过，保留其他样本");
        } catch(Exception error){warnings.add("diagnostics/metrics.jsonl："+error.getMessage()+"（保留上次成功样本）");}
    }
}
