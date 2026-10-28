package com.agentmonitor.agent.exporter;

import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.log.AgentLog;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.*;
import io.opentelemetry.proto.common.v1.*;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** OTLP/HTTP binary protobuf. Runs on the bounded exporter worker, never a business thread. */
final class OtlpHttpSpanExporter implements SpanExporter {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final URI endpoint;
    private final BoundedOtlpClient transport = new BoundedOtlpClient();
    private final Resource resource;
    OtlpHttpSpanExporter(String endpoint, Map<String, String> attributes) {
        this.endpoint = URI.create(endpoint);
        if (!("http".equals(this.endpoint.getScheme()) || "https".equals(this.endpoint.getScheme())) || this.endpoint.getHost() == null)
            throw new IllegalArgumentException("OTLP traces endpoint must use HTTP(S)");
        var r = Resource.newBuilder(); attributes.forEach((k,v) -> r.addAttributes(attribute(k,v))); resource = r.build();
    }
    public String name() { return "otlp/http"; }
    public boolean start() { return true; }
    public ExportResult export(SpanData span) { return exportBatch(List.of(span)).get(0); }
    public List<ExportResult> exportBatch(List<SpanData> spans) {
        List<ExportResult> results = new ArrayList<>(); List<Span> chunk = new ArrayList<>(); int bytes = 0;
        for (SpanData span : spans) {
            Span wire;
            try { wire = convert(span); } catch (RuntimeException invalid) { flush(chunk, results); chunk.clear(); bytes = 0; results.add(ExportResult.rejected()); continue; }
            int size = wire.getSerializedSize();
            if (!chunk.isEmpty() && bytes + size > MAX_BYTES / 2) { flush(chunk, results); chunk.clear(); bytes = 0; }
            chunk.add(wire); bytes += size;
        }
        flush(chunk, results); return results;
    }
    private void flush(List<Span> spans, List<ExportResult> results) {
        if (spans.isEmpty()) return;
        var batch = ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder().setResource(resource)
                .addScopeSpans(ScopeSpans.newBuilder().setScope(InstrumentationScope.newBuilder().setName("com.agentmonitor.java-agent").setVersion("1"))
                        .addAllSpans(spans))).build();
        boolean accepted = send(batch.toByteArray());
        for (int i = 0; i < spans.size(); i++) results.add(accepted ? ExportResult.accepted() : ExportResult.rejected());
    }
    private boolean send(byte[] body) {
        if (body.length > MAX_BYTES) return false;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var response = transport.post(endpoint,body);
                int status = response.statusCode();
                if (attempt == 0 && (status == 429 || status == 502 || status == 503 || status == 504)) {
                    String delay = response.headers().firstValue("Retry-After").orElse(null);
                    if (delay != null && !delay.equals("0")) return false;
                    Thread.sleep(50); continue;
                }
                if (status != 200) return false;
                var result = ExportTraceServiceResponse.parseFrom(response.body());
                if (result.hasPartialSuccess()) {
                    AgentLog.warn("OTLP partial_success: rejected=" + result.getPartialSuccess().getRejectedSpans() + " " + result.getPartialSuccess().getErrorMessage());
                    return result.getPartialSuccess().getRejectedSpans() == 0; // Never retry a partial response.
                }
                return true;
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
            catch (Exception error) {
                Throwable cause=transportCause(error);
                boolean retry=attempt==0 && (cause instanceof java.io.IOException || cause instanceof java.util.concurrent.TimeoutException)
                        && !(cause instanceof com.google.protobuf.InvalidProtocolBufferException);
                AgentLog.warn("OTLP export attempt="+(attempt+1)+" failed: "+cause.getClass().getSimpleName()+(retry?"; bounded retry":"; delivery unconfirmed"));
                if(!retry)return false;
                try{Thread.sleep(50);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
            }
        }
        return false;
    }
    private static Throwable transportCause(Throwable error){
        for(int i=0;i<8&&(error instanceof java.util.concurrent.ExecutionException||error instanceof java.util.concurrent.CompletionException)&&error.getCause()!=null;i++)error=error.getCause();
        return error;
    }
    static Span convert(SpanData source) {
        if (!source.traceId().matches("[0-9a-f]{32}") || source.traceId().matches("0{32}")
                || !source.spanId().matches("[0-9a-f]{16}") || source.spanId().matches("0{16}")) throw new IllegalArgumentException("Invalid trace/span IDs");
        long start = Math.multiplyExact(source.startedAtEpochMillis(), 1_000_000L);
        long elapsed = source.durationNanos();
        try { elapsed = Long.parseLong(source.attributes().getOrDefault("agent.span.elapsed_nanos", Long.toString(elapsed))); } catch (NumberFormatException ignored) { }
        var span = Span.newBuilder().setTraceId(ByteString.copyFrom(HexFormat.of().parseHex(source.traceId())))
                .setSpanId(ByteString.copyFrom(HexFormat.of().parseHex(source.spanId()))).setFlags(1)
                .setName(source.className() + "." + source.methodName()).setStartTimeUnixNano(start).setEndTimeUnixNano(Math.addExact(start, elapsed))
                .setKind(switch (source.kind()) { case BUSINESS -> Span.SpanKind.SPAN_KIND_INTERNAL; case SQL,HTTP_CLIENT,REDIS -> Span.SpanKind.SPAN_KIND_CLIENT; case MESSAGING -> Span.SpanKind.SPAN_KIND_PRODUCER; default -> Span.SpanKind.SPAN_KIND_SERVER; })
                .setStatus(Status.newBuilder().setCode(source.status().isError() ? Status.StatusCode.STATUS_CODE_ERROR : Status.StatusCode.STATUS_CODE_UNSET)
                        .setMessage(source.statusDescription()));
        if (!source.parentSpanId().isBlank()) span.setParentSpanId(ByteString.copyFrom(HexFormat.of().parseHex(source.parentSpanId())));
        span.setTraceState(source.attributes().getOrDefault("w3c.tracestate",""));
        source.attributes().forEach((key, value) -> span.addAttributes(attribute(key, value)));
        String linkedTrace = source.attributes().getOrDefault("agent.async.parent.trace_id", "");
        String linkedSpan = source.attributes().getOrDefault("agent.async.parent.span_id", "");
        if (linkedTrace.matches("[0-9a-f]{32}") && linkedSpan.matches("[0-9a-f]{16}") && !linkedTrace.matches("0{32}") && !linkedSpan.matches("0{16}"))
            span.addLinks(Span.Link.newBuilder().setTraceId(ByteString.copyFrom(HexFormat.of().parseHex(linkedTrace)))
                    .setSpanId(ByteString.copyFrom(HexFormat.of().parseHex(linkedSpan))));
        Map.of("agent.class.name", source.className(), "agent.method.name", source.methodName(), "code.namespace", source.className(), "code.function.name", source.className() + "." + source.methodName(), "agent.method.signature", source.signature(),
                "thread.name", source.threadName(), "agent.span.depth", Integer.toString(source.depth()),
                "agent.analysis.duration_ns", Long.toString(source.durationNanos())).forEach((key,value) -> span.addAttributes(attribute(key,value)));
        if (!source.arguments().isBlank()) span.addAttributes(attribute("agent.arguments", source.arguments()));
        if (!source.returnValue().isBlank()) span.addAttributes(attribute("agent.return_value", source.returnValue()));
        if (!source.stackTrace().isBlank()) span.addAttributes(attribute("exception.stacktrace", source.stackTrace()));
        for (var event : source.events()) {
            var e = Span.Event.newBuilder().setName(event.name()).setTimeUnixNano(event.timestampEpochMillis() * 1_000_000L);
            event.attributes().forEach((key,value) -> e.addAttributes(attribute(key,value))); span.addEvents(e);
        }
        return span.build();
    }
    private static KeyValue attribute(String key, String value) {
        var any = AnyValue.newBuilder();
        boolean numeric = Set.of("http.response.status_code", "server.port", "db.operation.batch.size", "process.pid", "agent.span.depth", "agent.analysis.duration_ns", "agent.observer.excluded.nanos", "agent.span.elapsed_nanos").contains(key);
        if (numeric) { try { any.setIntValue(Long.parseLong(value)); } catch (NumberFormatException invalid) { any.setStringValue(value); } }
        else any.setStringValue(value);
        return KeyValue.newBuilder().setKey(key).setValue(any).build();
    }
    public void close() { transport.close(); }
}
