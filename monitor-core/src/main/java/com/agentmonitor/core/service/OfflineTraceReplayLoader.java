package com.agentmonitor.core.service;

import com.agentmonitor.model.trace.TraceNode;
import com.agentmonitor.core.model.SpanEvent;
import com.agentmonitor.model.span.SpanKind;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Read-only, two-pass replay. Completion order, cross-file parents, and gzip share one assembler. */
public final class OfflineTraceReplayLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_SPANS = 1_000_000;
    private record Key(String trace, String span) { }
    private enum State { ROOTED, ORPHAN, CYCLE }
    public record ReplayResult(List<TraceNode> roots, int spanFileCount, long acceptedSpans, long skippedSpans,
            long orphanSpans, long cyclicSpans, List<String> warnings) {
        public ReplayResult { roots = List.copyOf(roots); warnings = List.copyOf(warnings); }
        public boolean hasDegradation() { return skippedSpans > 0 || orphanSpans > 0 || cyclicSpans > 0 || !warnings.isEmpty(); }
    }
    public ReplayResult load(Path selectedDirectory) throws IOException {
        Path directory = selectedDirectory.toAbsolutePath().normalize();
        if (Files.isDirectory(directory.resolve("spans"))) directory = directory.resolve("spans");
        Path selectedRoot=selectedDirectory.toRealPath();
        if(!directory.toRealPath().startsWith(selectedRoot))throw new IOException("Span directory escapes selected session");
        if (!Files.isDirectory(directory)) throw new IOException("不是会话目录或 spans 目录: " + directory);
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(p -> !Files.isSymbolicLink(p) && Files.isRegularFile(p)
                    && p.getFileName().toString().matches("(?:spans-[0-9]+\\.json(?:\\.gz)?|rolling-spans\\.jsonl(?:\\.gz)?)")).sorted().toList();
        }
        if (files.isEmpty()) throw new IOException("目录中没有可回放的 spans-*.json / .json.gz 或 rolling-spans.jsonl 文件");
        Map<Key, SpanEvent> spans = new LinkedHashMap<>();
        long skipped = 0;
        List<String> warnings = new ArrayList<>();
        Path sessionRoot=directory.getFileName().toString().equals("spans")?directory.getParent():directory;
        String expectedId=Files.isRegularFile(sessionRoot.resolve("session.json"))?JSON.readTree(sessionRoot.resolve("session.json").toFile()).path("sessionId").asText():"";
        for (Path file : files) {
            if(file.getFileName().toString().contains(".jsonl")){
                if(expectedId.isBlank())throw new IOException("滚动窗口缺少 session.json 身份");
                try(InputStream raw=Files.newInputStream(file);InputStream data=file.toString().endsWith(".gz")?new GZIPInputStream(raw):raw;var input=new BufferedReader(new InputStreamReader(data,java.nio.charset.StandardCharsets.UTF_8))){
                    int line=0;for(String text;(text=readBoundedLine(input))!=null;){line++;try{
                        if(text.length()>4*1024*1024)throw new IOException("记录超过 4Mi 字符");
                        var value=JSON.readTree(text);if(!expectedId.equals(value.path("sessionId").asText()))throw new IOException("窗口记录会话身份不匹配");
                        SpanEvent span=rollingSpan(value);if(span==null){skipped++;continue;}
                        if(spans.putIfAbsent(new Key(span.traceId(),span.spanId()),span)!=null)skipped++;
                        if(spans.size()>MAX_SPANS)throw new IllegalStateException("会话超过 "+MAX_SPANS+" 个 Span");
                    }catch(IOException invalid){warnings.add(file.getFileName()+":"+line+": "+invalid.getMessage());}}
                }catch(IOException error){warnings.add(file.getFileName()+"："+error.getMessage()+"；该文件余下记录未读取");}
                warnings.add(file.getFileName()+"：滚动证据不含独立导出 ACK，详情以窗口实际保留字段为准");
                continue;
            }
            try (InputStream raw = Files.newInputStream(file);
                 InputStream data = file.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                 var parser = JSON.getFactory().createParser(data)) {
                if (parser.nextToken() != JsonToken.START_ARRAY) throw new IOException("Span 文件必须是 JSON 数组");
                for (JsonToken token; (token = parser.nextToken()) != JsonToken.END_ARRAY;) {
                    if (token == null) throw new EOFException("Span 文件尚未完整关闭");
                    com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(parser);
                    SpanEvent span = com.agentmonitor.core.internal.SpanEventCodec.parseNode(node);
                    if (span == null || (span.kind() == SpanKind.SQL && span.parentSpanId().isBlank())) { skipped++; continue; }
                    Key key = new Key(span.traceId(), span.spanId());
                    if (spans.putIfAbsent(key, span) != null) skipped++;
                    if (spans.size() > MAX_SPANS) throw new IllegalStateException("会话超过 " + MAX_SPANS + " 个 Span，请拆分后回放");
                }
                if (parser.nextToken() != null) throw new IOException("数组后存在额外内容");
            } catch (IOException error) { warnings.add(file.getFileName() + ": " + error.getMessage()); }
        }
        Map<Key, State> states = new HashMap<>();
        for (Key key : spans.keySet()) classify(key, spans, states);
        Map<Key, TraceNode> nodes = new LinkedHashMap<>();
        long orphans = 0, cycles = 0;
        for (var entry : spans.entrySet()) {
            State state = states.get(entry.getKey());
            if (state == State.ORPHAN) orphans++;
            else if (state == State.CYCLE) cycles++;
            else nodes.put(entry.getKey(), entry.getValue().toTraceNode());
        }
        List<TraceNode> roots = new ArrayList<>();
        for (var entry : nodes.entrySet()) {
            SpanEvent span = spans.get(entry.getKey());
            if (span.localRoot()) roots.add(entry.getValue());
            else nodes.get(new Key(span.traceId(), span.parentSpanId())).addChild(entry.getValue());
        }
        roots.forEach(TraceNode::freeze);
        return new ReplayResult(roots, files.size(), spans.size(), skipped, orphans, cycles, warnings);
    }
    private static String readBoundedLine(BufferedReader input)throws IOException {
        StringBuilder text=new StringBuilder();int value;
        while((value=input.read())!=-1){if(value=='\n')break;if(text.length()>=4*1024*1024)throw new IOException("记录超过 4Mi 字符");text.append((char)value);}
        return value==-1&&text.isEmpty()?null:text.toString();
    }
    public static boolean hasReplayFiles(Path selected)throws IOException {
        Path spans=Files.isDirectory(selected.resolve("spans"))?selected.resolve("spans"):selected;
        if(!Files.isDirectory(spans))return false;
        try(var files=Files.list(spans)){return files.anyMatch(file->Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&file.getFileName().toString().matches("(?:spans-[0-9]+\\.json(?:\\.gz)?|rolling-spans\\.jsonl(?:\\.gz)?)"));}
    }
    private static SpanEvent rollingSpan(com.fasterxml.jackson.databind.JsonNode row)throws IOException {
        try{
            String method=row.path("method").asText();int hash=method.indexOf('#'),kindStart=method.lastIndexOf(" ["),open=method.indexOf('(',hash);
            if(hash<1||kindStart<hash)throw new IllegalArgumentException("方法标识无效");
            String name=method.substring(hash+1,open<0?kindStart:open),signature=open<0?"":method.substring(open,kindStart);
            Map<String,String> attrs=new LinkedHashMap<>();row.path("attributes").fields().forEachRemaining(e->attrs.put(e.getKey(),e.getValue().asText()));
            String id=attrs.getOrDefault("agent.span.id",""),parent=attrs.getOrDefault("agent.span.parent_id","");
            int depth=parent.isBlank()||id.equals(attrs.get("agent.local.root_span_id"))||"true".equals(attrs.get("agent.remote_parent"))||"true".equals(attrs.get("agent.local.detached"))?0:1;
            if(id.isBlank()||row.path("traceId").asText().isBlank()||!row.path("durationNanos").isIntegralNumber()||row.path("durationNanos").asLong()<0)throw new IllegalArgumentException("缺少有效 Span 身份或耗时");
            return new SpanEvent(row.path("traceId").asText(),id,parent,method.substring(0,hash),name,signature,row.path("threadName").asText("unknown"),depth,row.path("startedAtEpochMillis").asLong(),row.path("durationNanos").asLong(),row.path("error").asBoolean(),"","","",SpanKind.valueOf(method.substring(kindStart+2,method.length()-1)),attrs);
        }catch(IllegalArgumentException invalid){throw new IOException("无效滚动记录："+invalid.getMessage(),invalid);}
    }
    private static void classify(Key first, Map<Key, SpanEvent> spans, Map<Key, State> states) {
        if (states.containsKey(first)) return;
        Set<Key> walking = new LinkedHashSet<>();
        Key current = first;
        State outcome;
        while (true) {
            if (states.containsKey(current)) { outcome = states.get(current); break; }
            if (!walking.add(current)) { outcome = State.CYCLE; break; }
            SpanEvent span = spans.get(current);
            if (span == null) { outcome = State.ORPHAN; break; }
            if (span.localRoot()) { outcome = State.ROOTED; break; }
            current = new Key(span.traceId(), span.parentSpanId());
        }
        for (Key key : walking) if (spans.containsKey(key)) states.put(key, outcome);
    }
}
