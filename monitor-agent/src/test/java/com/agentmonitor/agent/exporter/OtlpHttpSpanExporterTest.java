package com.agentmonitor.agent.exporter;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.agent.model.*;
import com.agentmonitor.model.span.SpanKind;
import io.opentelemetry.proto.collector.trace.v1.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OtlpHttpSpanExporterTest {
    private static SpanData span() {
        return new SpanData("1234567890abcdef1234567890abcdef", "1234567890abcdef", "", "example.Service", "work", "()V", "request", 0,
                1000, 100, SpanStatus.ERROR, "", "", "", SpanKind.BUSINESS, Map.of("http.response.status_code", "500"));
    }
    @Test void exportsOfficialProtobufWithBinaryIdsTypedAttributesAndInstrumentationScope() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var received = new java.util.concurrent.CompletableFuture<ExportTraceServiceRequest>();
        server.createContext("/v1/traces", exchange -> {
            received.complete(ExportTraceServiceRequest.parseFrom(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf"); exchange.sendResponseHeaders(200,-1); exchange.close();
        }); server.start();
        try {
            var exporter = new OtlpHttpSpanExporter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces", Map.of("service.name","test"));
            assertTrue(exporter.export(span()).delivered());
            var resource = received.get().getResourceSpans(0); var scope = resource.getScopeSpans(0); var wire = scope.getSpans(0);
            assertEquals(16, wire.getTraceId().size()); assertEquals(8, wire.getSpanId().size()); assertEquals(1_000_000_100L,wire.getEndTimeUnixNano());
            assertEquals("com.agentmonitor.java-agent",scope.getScope().getName());
            assertEquals(500,wire.getAttributesList().stream().filter(a->a.getKey().equals("http.response.status_code")).findFirst().orElseThrow().getValue().getIntValue());
        } finally { server.stop(0); }
    }
    @Test void partialRejectionIsNeverRetriedAndWarningOnlyResponsesAreSuccessful() throws Exception {
        for (int rejected : List.of(0,1)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var attempts = new AtomicInteger();
            server.createContext("/v1/traces", exchange -> {
                attempts.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                byte[] body = ExportTraceServiceResponse.newBuilder().setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                        .setRejectedSpans(rejected).setErrorMessage("test warning")).build().toByteArray();
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
            });server.start();
            try {
                var exporter = new OtlpHttpSpanExporter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces",Map.of());
                assertEquals(rejected==0, exporter.export(span()).delivered()); assertEquals(1,attempts.get());
            } finally { server.stop(0); }
        }
    }
    @Test @org.junit.jupiter.api.Timeout(10)
    void retriesAnUnconfirmedTimedOutRequestWithTheIdenticalPayload()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);server.setExecutor(workers);
        var attempts=new AtomicInteger();List<byte[]> bodies=new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/v1/traces",exchange->{
            try{bodies.add(exchange.getRequestBody().readAllBytes());if(attempts.incrementAndGet()==1)Thread.sleep(2500);
                exchange.sendResponseHeaders(200,-1);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(java.io.IOException disconnected){}finally{exchange.close();}
        });server.start();
        var exporter=new OtlpHttpSpanExporter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces",Map.of());
        try{assertTrue(exporter.export(span()).delivered());assertEquals(2,attempts.get());assertArrayEquals(bodies.get(0),bodies.get(1));}
        finally{exporter.close();server.stop(0);workers.shutdownNow();}
    }
    @Test @org.junit.jupiter.api.Timeout(10)
    void repeatedTimeoutsRemainBoundedAndDoNotClaimDelivery()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);server.setExecutor(workers);var attempts=new AtomicInteger();
        server.createContext("/v1/traces",exchange->{
            try{exchange.getRequestBody().readAllBytes();attempts.incrementAndGet();Thread.sleep(4000);exchange.sendResponseHeaders(200,-1);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(java.io.IOException disconnected){}finally{exchange.close();}
        });server.start();
        var exporter=new OtlpHttpSpanExporter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces",Map.of());long started=System.nanoTime();
        try{assertFalse(exporter.export(span()).delivered());assertEquals(2,attempts.get());assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<7000);}
        finally{exporter.close();server.stop(0);workers.shutdownNow();}
    }
    @Test void malformedSuccessResponseIsNotRetried()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var attempts=new AtomicInteger();
        server.createContext("/v1/traces",exchange->{attempts.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,1);exchange.getResponseBody().write(255);exchange.close();});server.start();
        var exporter=new OtlpHttpSpanExporter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces",Map.of());
        try{assertFalse(exporter.export(span()).delivered());assertEquals(1,attempts.get());}finally{exporter.close();server.stop(0);}
    }
    @Test @org.junit.jupiter.api.Timeout(12)
    void stopAllowsAnAlreadyAcceptedBatchToFinishItsBoundedRetry()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);server.setExecutor(workers);
        var entered=new java.util.concurrent.CountDownLatch(1);var attempts=new AtomicInteger();
        server.createContext("/v1/traces",exchange->{
            try{exchange.getRequestBody().readAllBytes();int attempt=attempts.incrementAndGet();entered.countDown();Thread.sleep(attempt==1?4000:1500);exchange.sendResponseHeaders(200,-1);}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(java.io.IOException disconnected){}finally{exchange.close();}
        });server.start();
        var transport=new OtlpHttpSpanExporter("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces",Map.of());
        var async=new AsyncSpanExporter(new CompositeSpanExporter(List.of(transport)));
        var previous=com.agentmonitor.agent.interceptor.MethodSpanAdvice.EXPORTER;
        try{
            assertTrue(async.start());assertTrue(async.export(span()).delivered());assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            com.agentmonitor.agent.interceptor.MethodSpanAdvice.EXPORTER=async;
            var stop=com.agentmonitor.agent.AgentMain.class.getDeclaredMethod("drainQueuedOutput");stop.setAccessible(true);
            ExporterDrainResult result=(ExporterDrainResult)stop.invoke(null);assertTrue(result.drained(),result.summary());assertEquals(0,result.pendingSpans());assertEquals(0,result.rejectedDestinations());assertEquals(2,attempts.get());
        }finally{com.agentmonitor.agent.interceptor.MethodSpanAdvice.EXPORTER=previous;async.close();server.stop(0);workers.shutdownNow();}
    }
}
