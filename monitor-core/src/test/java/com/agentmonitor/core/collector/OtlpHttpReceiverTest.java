package com.agentmonitor.core.collector;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.model.SpanEvent;
import io.opentelemetry.proto.collector.trace.v1.*;
import io.opentelemetry.proto.trace.v1.*;
import io.opentelemetry.proto.common.v1.*;
import io.opentelemetry.proto.resource.v1.Resource;
import com.google.protobuf.ByteString;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OtlpHttpReceiverTest {
    static Span wire(String id, String parent) {
        var span = Span.newBuilder().setTraceId(ByteString.copyFrom(HexFormat.of().parseHex("1234567890abcdef1234567890abcdef")))
                .setSpanId(ByteString.copyFrom(HexFormat.of().parseHex(id))).setName("work").setStartTimeUnixNano(1_000_000_000).setEndTimeUnixNano(1_100_000_000);
        if (!parent.isBlank()) span.setParentSpanId(ByteString.copyFrom(HexFormat.of().parseHex(parent))); return span.build();
    }
    static byte[] batch(String session, Span... spans) {
        return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
                .setResource(Resource.newBuilder().addAttributes(KeyValue.newBuilder().setKey("agent.monitor.session.id").setValue(AnyValue.newBuilder().setStringValue(session))))
                .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(List.of(spans)))).build().toByteArray();
    }
    static ExportTraceServiceResponse post(String endpoint, byte[] body) throws Exception {
        var connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection(); connection.setReadTimeout(3000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/x-protobuf");
        try (var output=connection.getOutputStream()){output.write(body);} assertEquals(200,connection.getResponseCode());
        try(var input=connection.getInputStream()){return ExportTraceServiceResponse.parseFrom(input.readAllBytes());}finally{connection.disconnect();}
    }
    @Test void validatesSessionAndPartialFailuresWhileAcceptingStandardBinaryIds() throws Exception {
        List<SpanEvent> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        var pipeline = new TracePipeline(List.of(span -> span),List.of(span -> {received.add(span);return true;}));
        try(var receiver = new OtlpHttpReceiver("current","",pipeline,resource -> {})) {
            String endpoint=receiver.start();
            var result=post(endpoint,batch("current",wire("1234567890abcdef",""),Span.getDefaultInstance()));
            assertEquals(1,result.getPartialSuccess().getRejectedSpans()); assertEquals(1,received.size());
            assertEquals("1234567890abcdef1234567890abcdef",received.get(0).traceId());
            assertEquals(1,post(endpoint,batch("old",wire("1234567890abcdef",""))).getPartialSuccess().getRejectedSpans());
            assertEquals(1,received.size()); assertFalse(post(endpoint,new byte[0]).hasPartialSuccess());
        }
    }    @Test void forwardsOnlyValidatedSessionSpansAndPreservesExceptionEvidence() throws Exception {
        var received=new java.util.concurrent.CopyOnWriteArrayList<SpanEvent>();
        var sink=new TracePipeline(List.of(),List.of(span -> {received.add(span);return true;}));
        try(var backend=new OtlpHttpReceiver(null,"",sink,r -> {})) {
            String target=backend.start();
            try(var receiver=new OtlpHttpReceiver("current",target,new TracePipeline(List.of(),List.of(span -> true)),r -> {})) {
                var exception=Span.Event.newBuilder().setName("exception").setTimeUnixNano(1_000_000_000)
                    .addAttributes(KeyValue.newBuilder().setKey("exception.message").setValue(AnyValue.newBuilder().setStringValue("query failed")));
                var valid=wire("1234567890abcdef","").toBuilder().addEvents(exception).setDroppedEventsCount(1).build();
                var request=ExportTraceServiceRequest.newBuilder().mergeFrom(batch("current",valid,Span.getDefaultInstance()))
                    .mergeFrom(batch("old",wire("2234567890abcdef",""))).build();
                var result=post(receiver.start(),request.toByteArray());
                assertEquals(2,result.getPartialSuccess().getRejectedSpans()); assertEquals(1,received.size());
                assertEquals("query failed",received.get(0).attributes().get("otel.exception.message"));
                assertEquals("true",received.get(0).attributes().get("otel.evidence.truncated"));
                assertTrue(received.get(0).attributes().get("otel.events").contains("query failed"));
            }
        }
    }
    @Test void overloadReturnsEncoded503AndReleasesCapacity() throws Exception {
        try(var receiver=new OtlpHttpReceiver(null,"",new TracePipeline(List.of(),List.of(s -> true)),r -> {})) {
            var uri=URI.create(receiver.start()); var sockets=new ArrayList<java.net.Socket>();
            try {
                for(int i=0;i<4;i++) sockets.add(new java.net.Socket(uri.getHost(),uri.getPort()));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                while(receiver.activeConnections()<4 && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(4,receiver.activeConnections());
                var connection=(HttpURLConnection)uri.toURL().openConnection(); connection.setReadTimeout(2000);connection.setRequestMethod("POST");connection.setDoOutput(true);
                try(var body=connection.getOutputStream()){body.write(new byte[0]);}
                assertEquals(503,connection.getResponseCode()); connection.disconnect();
            } finally {for(var socket:sockets)socket.close();}
        }
    }
    @Test void oversizedIdentityCannotConsumeAssemblyBudget() {
        var large=wire("1234567890abcdef","").toBuilder().setName("x".repeat(9000)).build();
        assertThrows(IllegalArgumentException.class,() -> OtlpHttpReceiver.convert(large,"scope"));
    }
    @Test void oversizedRequestReturnsOtlpProtobufErrorRatherThanBareAggregatorResponse() throws Exception {
        try(var receiver=new OtlpHttpReceiver(null,"",new TracePipeline(List.of(),List.of(span -> true)),r -> {})) {
            var uri=URI.create(receiver.start());
            try(var socket=new java.net.Socket(uri.getHost(),uri.getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(("POST /v1/traces HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-protobuf\r\nContent-Length: 4194305\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                byte[] response=socket.getInputStream().readAllBytes();
                String text=new String(response,java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(text.startsWith("HTTP/1.1 413"),text);
                assertTrue(text.toLowerCase().contains("content-type: application/x-protobuf"),text);
                assertTrue(text.contains("OTLP request exceeds 4 MiB"),"Response contains protobuf Status diagnostic");
            }
        }
    }
    @Test void analysisProjectionStoresCanonicalIdentityAndPayloadOnce() {
        var input=wire("1234567890abcdef","").toBuilder();
        Map.of("agent.class.name","example.Service","agent.method.name","work","code.namespace","example.Service","agent.arguments","[42]","agent.return_value","done").forEach((key,value) ->
                input.addAttributes(KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value))));
        var event=OtlpHttpReceiver.convert(input.build(),"scope");
        assertEquals("example.Service",event.className());assertEquals("[42]",event.arguments());assertEquals("done",event.returnValue());
        assertFalse(event.attributes().containsKey("agent.class.name"));assertFalse(event.attributes().containsKey("agent.arguments"));
        assertEquals("[42]",input.getAttributesList().stream().filter(k -> k.getKey().equals("agent.arguments")).findFirst().orElseThrow().getValue().getStringValue(),"Forwarded source remains unchanged");
    }
    @Test void nonCanonicalDepthIsRetainedAsEvidenceRatherThanRemoved() {
        var input=wire("1234567890abcdef","").toBuilder().addAttributes(KeyValue.newBuilder().setKey("agent.span.depth").setValue(AnyValue.newBuilder().setStringValue("-1"))).build();
        var projected=OtlpHttpReceiver.convert(input,"scope");assertEquals(0,projected.depth());assertEquals("-1",projected.attributes().get("agent.span.depth"));
    }

}
