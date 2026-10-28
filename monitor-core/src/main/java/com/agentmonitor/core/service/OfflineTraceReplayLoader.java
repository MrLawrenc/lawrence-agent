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
                    && p.getFileName().toString().matches("spans-[0-9]+\\.json(?:\\.gz)?")).sorted().toList();
        }
        if (files.isEmpty()) throw new IOException("目录中没有 spans-*.json 或 .json.gz 文件");
        Map<Key, SpanEvent> spans = new LinkedHashMap<>();
        long skipped = 0;
        List<String> warnings = new ArrayList<>();
        for (Path file : files) {
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
