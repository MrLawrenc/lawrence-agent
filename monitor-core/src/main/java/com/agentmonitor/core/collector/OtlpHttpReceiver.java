package com.agentmonitor.core.collector;

import com.agentmonitor.core.model.SpanEvent;
import com.agentmonitor.model.span.SpanKind;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.*;
import io.opentelemetry.proto.common.v1.*;
import io.opentelemetry.proto.trace.v1.Span;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** OTLP/HTTP protobuf receiver, isolated from the Attach/STOP control socket. */
public final class OtlpHttpReceiver implements AutoCloseable {
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private final String sessionId, forwardEndpoint;
    private final TracePipeline pipeline;
    private final Consumer<Map<String,String>> resourceSink;
    private final NioEventLoopGroup boss = new NioEventLoopGroup(1), workers = new NioEventLoopGroup(2);
    private final java.util.concurrent.Semaphore connections = new java.util.concurrent.Semaphore(4);
    private final java.util.concurrent.ThreadPoolExecutor processing = new java.util.concurrent.ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(2), task -> { Thread thread = new Thread(task,"otlp-processing"); thread.setDaemon(true); return thread; });
    private Channel server;
    private final com.agentmonitor.core.internal.BoundedOtlpClient transport = new com.agentmonitor.core.internal.BoundedOtlpClient();
    public OtlpHttpReceiver(String sessionId, String forwardEndpoint, TracePipeline pipeline, Consumer<Map<String,String>> resourceSink) {
        this.sessionId = sessionId; this.forwardEndpoint = forwardEndpoint; this.pipeline = pipeline; this.resourceSink = resourceSink;
    }
    public String start() throws InterruptedException {
        server = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() { protected void initChannel(SocketChannel socket) {
                    socket.pipeline().addLast(new HttpServerCodec());
                    if (!connections.tryAcquire()) { response(socket.pipeline().firstContext(),503,errorBody("Collector overloaded")); return; }
                    socket.closeFuture().addListener(done -> connections.release());
                    socket.pipeline().addLast(new HttpContentDecompressor(), new HttpObjectAggregator(MAX_BODY) {
                        @Override protected void handleOversizedMessage(ChannelHandlerContext ctx,HttpMessage oversized) {
                            response(ctx,413,errorBody("OTLP request exceeds 4 MiB"));
                        }
                    }, new Handler());
                }}).bind("127.0.0.1", 0).sync().channel();
        return "http://127.0.0.1:" + ((InetSocketAddress) server.localAddress()).getPort() + "/v1/traces";
    }
    int activeConnections() { return 4 - connections.availablePermits(); }
    private final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private boolean submitted;
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (submitted) { response(ctx,503,errorBody("One request per connection")); return; }
            submitted = true; ctx.channel().config().setAutoRead(false);
            IngressJob job = new IngressJob(ctx,request.retainedDuplicate());
            try { processing.execute(job); }
            catch (java.util.concurrent.RejectedExecutionException overloaded) { job.cancel(); response(ctx,503,errorBody("Collector overloaded")); }
        }
    }
    private final class IngressJob implements Runnable {
        private final ChannelHandlerContext ctx;
        private final FullHttpRequest request;
        private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        IngressJob(ChannelHandlerContext ctx,FullHttpRequest request){this.ctx=ctx;this.request=request;}
        public void cancel(){if(released.compareAndSet(false,true))request.release();}
        public void run(){try{process(ctx,request);}finally{cancel();}}
    }
    private void process(ChannelHandlerContext ctx,FullHttpRequest request) {
            if (!request.uri().equals("/v1/traces")) { response(ctx, 404, errorBody("Unknown OTLP path")); return; }
            if (!request.method().equals(HttpMethod.POST)) { response(ctx, 405, errorBody("POST required")); return; }
            if (!request.headers().get(HttpHeaderNames.CONTENT_TYPE, "").split(";")[0].trim().equals("application/x-protobuf")) {
                response(ctx, 415, errorBody("application/x-protobuf required")); return;
            }
            try {
                byte[] bytes = new byte[request.content().readableBytes()]; request.content().getBytes(request.content().readerIndex(), bytes);
                var batch = ExportTraceServiceRequest.parseFrom(bytes); long rejected = 0, accepted = 0;
                var validatedBatch = ExportTraceServiceRequest.newBuilder();
                for (var resourceSpans : batch.getResourceSpansList()) {
                    Map<String,String> resource = attributes(resourceSpans.getResource().getAttributesList());
                    boolean matching = sessionId == null || sessionId.equals(resource.get("agent.monitor.session.id"));
                    if (matching) resourceSink.accept(resource);
                    var validatedResource = resourceSpans.toBuilder().clearScopeSpans();
                    for (var scope : resourceSpans.getScopeSpansList()) {
                        var validatedScope = scope.toBuilder().clearSpans();
                        for (var wire : scope.getSpansList()) {
                            if (!matching) { rejected++; continue; }
                            try {
                                if (pipeline.receive(convert(wire, scope.getScope().getName()))) { accepted++; validatedScope.addSpans(wire); }
                                else rejected++;
                            } catch (IllegalArgumentException invalid) { rejected++; }
                        }
                        if (validatedScope.getSpansCount()>0) validatedResource.addScopeSpans(validatedScope);
                    }
                    if (validatedResource.getScopeSpansCount()>0) validatedBatch.addResourceSpans(validatedResource);
                }
                String warning = "";
                if (accepted > 0 && !forwardEndpoint.isBlank() && !forward(validatedBatch.build().toByteArray())) {
                    rejected += accepted; warning = "Local ingestion succeeded but downstream export was not confirmed";
                }
                var result = ExportTraceServiceResponse.newBuilder();
                if (rejected > 0) result.setPartialSuccess(ExportTracePartialSuccess.newBuilder().setRejectedSpans(rejected)
                        .setErrorMessage(warning.isEmpty() ? "Invalid span, mismatched session, or rejected by processor" : warning));
                response(ctx, 200, result.build().toByteArray());
            } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
                response(ctx, 400, errorBody("Malformed OTLP protobuf"));
            } catch (Exception error) {
                response(ctx, 500, errorBody("Collector processing failed"));
            }
    }
    private boolean forward(byte[] bytes) {
        try {
            var response = transport.post(URI.create(forwardEndpoint),bytes);
            if (response.statusCode() != 200) return false;
            var result = ExportTraceServiceResponse.parseFrom(response.body());
            return !result.hasPartialSuccess() || result.getPartialSuccess().getRejectedSpans() == 0;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (Exception error) { return false; }
    }
    private static byte[] errorBody(String message) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var coded = com.google.protobuf.CodedOutputStream.newInstance(bytes);
            coded.writeString(2, message); coded.flush(); return bytes.toByteArray();
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void response(ChannelHandlerContext ctx, int status, byte[] bytes) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-protobuf");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    static SpanEvent convert(Span wire, String scopeName) {
        if (!validId(wire.getTraceId(),16) || !validId(wire.getSpanId(),8)
                || (!wire.getParentSpanId().isEmpty() && !validId(wire.getParentSpanId(),8))
                || wire.getParentSpanId().equals(wire.getSpanId()) || wire.getEventsCount()>32 || wire.getLinksCount()>32 || wire.getAttributesCount()>128
                || wire.getStartTimeUnixNano() < 0 || wire.getEndTimeUnixNano() < wire.getStartTimeUnixNano()) throw new IllegalArgumentException("Invalid OTLP span");
        Map<String,String> attrs = new LinkedHashMap<>(attributes(wire.getAttributesList()));
        attrs.put("otel.span.kind", wire.getKind().name());
        attrs.put("otel.status.description",wire.getStatus().getMessage());
        try {
            var events = new ArrayList<Map<String,Object>>();
            for (var event : wire.getEventsList()) {
                if (event.getAttributesCount()>128) throw new IllegalArgumentException("Too many event attributes");
                Map<String,String> values = attributes(event.getAttributesList());
                events.add(Map.of("name",event.getName(),"timeUnixNano",Long.toUnsignedString(event.getTimeUnixNano()),"attributes",values,"droppedAttributesCount",event.getDroppedAttributesCount()));
                if (event.getName().equals("exception")) for (String key : List.of("exception.type","exception.message","exception.stacktrace"))
                    if (values.containsKey(key)) attrs.putIfAbsent("otel."+key,values.get(key));
            }
            var links = new ArrayList<Map<String,Object>>();
            for (var link : wire.getLinksList()) {
                if (!validId(link.getTraceId(),16) || !validId(link.getSpanId(),8) || link.getAttributesCount()>128) throw new IllegalArgumentException("Invalid span link");
                links.add(Map.of("traceId",HexFormat.of().formatHex(link.getTraceId().toByteArray()),"spanId",HexFormat.of().formatHex(link.getSpanId().toByteArray()),
                        "traceState",link.getTraceState(),"flags",link.getFlags(),"attributes",attributes(link.getAttributesList()),"droppedAttributesCount",link.getDroppedAttributesCount()));
            }
            if (!events.isEmpty()) attrs.put("otel.events",JSON.writeValueAsString(events));
            if (!links.isEmpty()) attrs.put("otel.links",JSON.writeValueAsString(links));
            attrs.put("otel.dropped_attributes_count",Integer.toUnsignedString(wire.getDroppedAttributesCount()));
            attrs.put("otel.dropped_events_count",Integer.toUnsignedString(wire.getDroppedEventsCount()));
            attrs.put("otel.dropped_links_count",Integer.toUnsignedString(wire.getDroppedLinksCount()));
            if (wire.getDroppedAttributesCount()!=0 || wire.getDroppedEventsCount()!=0 || wire.getDroppedLinksCount()!=0
                    || wire.getEventsList().stream().anyMatch(e -> e.getDroppedAttributesCount()!=0) || wire.getLinksList().stream().anyMatch(l -> l.getDroppedAttributesCount()!=0))
                attrs.put("otel.evidence.truncated","true");
        } catch (java.io.IOException impossible) { throw new IllegalArgumentException(impossible); }
        String cls = attrs.getOrDefault("agent.class.name", attrs.getOrDefault("code.namespace", scopeName.isBlank() ? "OpenTelemetry" : scopeName));
        String method = attrs.getOrDefault("agent.method.name", attrs.getOrDefault("code.function.name", wire.getName()));
        if (cls.isBlank() || method.isBlank()) throw new IllegalArgumentException("Missing span name");
        long elapsed = wire.getEndTimeUnixNano() - wire.getStartTimeUnixNano(), duration = elapsed;
        try { duration = Long.parseLong(attrs.getOrDefault("agent.analysis.duration_ns", Long.toString(elapsed))); } catch (NumberFormatException invalid) { }
        if (duration < 0 || duration > elapsed) duration = elapsed;
        boolean redis = "redis".equals(attrs.get("db.system.name"));
        boolean sql = !redis && (attrs.containsKey("db.system.name") || attrs.containsKey("db.query.text"));
        SpanKind kind = redis?SpanKind.REDIS:sql ? SpanKind.SQL : wire.getKind() == Span.SpanKind.SPAN_KIND_SERVER ? SpanKind.HTTP_SERVER
                :wire.getKind()==Span.SpanKind.SPAN_KIND_PRODUCER?SpanKind.MESSAGING:wire.getKind()==Span.SpanKind.SPAN_KIND_CLIENT?SpanKind.HTTP_CLIENT:SpanKind.BUSINESS;
        int depth = 0; try { depth = Integer.parseInt(attrs.getOrDefault("agent.span.depth", "0")); } catch (NumberFormatException ignored) { }
        boolean error = wire.getStatus().getCode() == io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR;
        String signature=attrs.getOrDefault("agent.method.signature", ""), thread=attrs.getOrDefault("thread.name", "unknown");
        String arguments=attrs.getOrDefault("agent.arguments", ""), returned=attrs.getOrDefault("agent.return_value", ""), stack=attrs.getOrDefault("exception.stacktrace", "");
        // The original protobuf is forwarded intact. The analysis projection stores canonical
        // identity/payload values once rather than retaining both top-level and attribute copies.
        removeEquivalent(attrs,"agent.class.name",cls);removeEquivalent(attrs,"agent.method.name",method);
        removeEquivalent(attrs,"agent.method.signature",signature);removeEquivalent(attrs,"thread.name",thread);
        removeEquivalent(attrs,"code.namespace",cls);removeEquivalent(attrs,"code.function.name",cls+"."+method);
        removeEquivalent(attrs,"agent.span.depth",Integer.toString(Math.max(0,depth)));removeEquivalent(attrs,"agent.analysis.duration_ns",Long.toString(duration));
        removeEquivalent(attrs,"agent.arguments",arguments);removeEquivalent(attrs,"agent.return_value",returned);removeEquivalent(attrs,"exception.stacktrace",stack);
        return new SpanEvent(HexFormat.of().formatHex(wire.getTraceId().toByteArray()), HexFormat.of().formatHex(wire.getSpanId().toByteArray()),
                HexFormat.of().formatHex(wire.getParentSpanId().toByteArray()), cls, method, signature,
                thread, Math.max(0,depth), wire.getStartTimeUnixNano()/1_000_000, duration, error,
                arguments,returned,stack,kind,attrs);
    }
    private static void removeEquivalent(Map<String,String> attrs,String key,String value){if(value.equals(attrs.get(key)))attrs.remove(key);}

    private static boolean validId(ByteString id, int size) {
        if (id.size() != size) return false; for (byte value : id.toByteArray()) if (value != 0) return true; return false;
    }
    private static Map<String,String> attributes(List<KeyValue> values) {
        Map<String,String> result = new LinkedHashMap<>();
        for (var entry : values) {
            AnyValue value = entry.getValue(); String text = switch (value.getValueCase()) {
                case STRING_VALUE -> value.getStringValue(); case INT_VALUE -> Long.toString(value.getIntValue());
                case BOOL_VALUE -> Boolean.toString(value.getBoolValue()); case DOUBLE_VALUE -> Double.toString(value.getDoubleValue());
                default -> value.toString();
            }; result.put(entry.getKey(), text);
        }
        return Map.copyOf(result);
    }
    public void close() {
        if (server != null) server.close().awaitUninterruptibly();
        transport.close();
        processing.shutdown();
        try {
            if (!processing.awaitTermination(2,TimeUnit.SECONDS))
                for (Runnable task : processing.shutdownNow()) if(task instanceof IngressJob job) job.cancel();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            for (Runnable task : processing.shutdownNow()) if(task instanceof IngressJob job)job.cancel();
        }
        workers.shutdownGracefully(0,2,TimeUnit.SECONDS).awaitUninterruptibly(); boss.shutdownGracefully(0,2,TimeUnit.SECONDS).awaitUninterruptibly();
    }
}
