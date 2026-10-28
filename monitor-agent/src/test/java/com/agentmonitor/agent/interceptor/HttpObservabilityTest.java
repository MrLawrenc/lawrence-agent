package com.agentmonitor.agent.interceptor;

import com.agentmonitor.agent.exporter.RecordingSpanExporter;
import com.agentmonitor.model.config.MonitoringConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpObservabilityTest {
    @TempDir Path directory;
    RecordingSpanExporter exporter;
    @BeforeEach void setup(){InFlightSpans.startSession();MethodSpanAdvice.ACTIVE=true;MethodSpanAdvice.SPAN_STACK.remove();MethodSpanAdvice.configureTraceSampling(0,60_000);exporter=new RecordingSpanExporter();MethodSpanAdvice.EXPORTER=exporter;DependencySpanSupport.HTTP_ENABLED=true;OverheadController.configure(MonitoringConfig.Overhead.defaults());RequestMetrics.start(directory.toString(),"test",true);}
    @AfterEach void cleanup(){RequestMetrics.stop();MethodSpanAdvice.ACTIVE=false;MethodSpanAdvice.EXPORTER=null;MethodSpanAdvice.SPAN_STACK.remove();}
    @Test void requestCountsAreIndependentOfTraceSamplingAndPreferFrameworkRoute(){
        for(int i=0;i<8;i++){var req=new Request(null);var response=new Response(200);Object[] args={req,response};var context=DependencySpanSupport.enter("test.Servlet","service",null,args);assertNotNull(context);DependencySpanSupport.exit(context,null,args,null,null);}
        assertTrue(exporter.spans.isEmpty());Map<String,Object> stats=RequestMetrics.snapshot();assertEquals(8L,stats.get("completedRequests"));
        var endpoint=(Map<?,?>)((List<?>)stats.get("endpoints")).get(0);assertEquals("GET /orders/{id}",endpoint.get("endpoint"));assertEquals(8L,endpoint.get("completed"));
    }
    @Test void remoteSampledParentOwnsTraceAndUnsampledErrorsDoNotPromote(){
        String trace="0123456789abcdef0123456789abcdef",parent="0123456789abcdef";
        var sampled=DependencySpanSupport.enter("test.Servlet","service",null,new Object[]{new Request("00-"+trace+"-"+parent+"-01"),new Response(200)});
        assertEquals(trace,sampled.span().traceId);assertEquals(parent,sampled.span().parentSpanId);assertTrue(sampled.span().traceSampling.isHeadSampled());
        DependencySpanSupport.exit(sampled,null,new Object[]{new Response(200)},null,null);assertEquals(1,exporter.spans.size());assertEquals("1",exporter.spans.get(0).attributes().get("agent.trace.completed_spans"));
        exporter.spans.clear();var req=new Request("00-"+trace+"-"+parent+"-00");var unsampled=DependencySpanSupport.enter("test.Servlet","service",null,new Object[]{req,new Response(503)});
        DependencySpanSupport.exit(unsampled,null,new Object[]{new Response(503)},null,new IllegalStateException("test"));assertTrue(exporter.spans.isEmpty());assertEquals(2L,RequestMetrics.snapshot().get("completedRequests"));
    }
    @Test void w3cRejectsMalformedIdsAndBoundsState(){
        String parent="00-0123456789abcdef0123456789abcdef-0123456789abcdef-03";
        assertEquals(1,DistributedContext.parse(parent,"vendor=value").flags());
        assertNull(DistributedContext.parse(parent+"-extra",""));assertNull(DistributedContext.parse(parent.toUpperCase(Locale.ROOT),""));assertNull(DistributedContext.parse(parent.replace("0123456789abcdef0123456789abcdef","0".repeat(32)),""));
        assertNull(DistributedContext.parse(parent+","+parent,""));assertEquals("",DistributedContext.parse(parent,"vendor=x,vendor=y").traceState());assertEquals("",DistributedContext.validState("x="+"a".repeat(513)));
    }
    @Test void immutableRequestCopyPreservesBodyAndCallerOwnedHeadersAndAsyncFutureIdentity()throws Exception{
        MethodSpanAdvice.configureTraceSampling(100,60_000);var body=HttpRequest.BodyPublishers.ofString("untouched");HttpRequest original=HttpRequest.newBuilder(URI.create("http://localhost:1234/body")).POST(body).build();Object[] args={original,HttpResponse.BodyHandlers.discarding()};
        var context=ClientSpanSupport.enter("jdk.internal.net.http.HttpClientFacade","sendAsync",null,args);assertNotNull(context);HttpRequest copy=(HttpRequest)args[0];assertTrue(original.headers().firstValue("traceparent").isEmpty());assertTrue(copy.headers().firstValue("traceparent").isPresent());assertSame(body,copy.bodyPublisher().orElseThrow());
        var response=new CompletableFuture<HttpResponse<?>>();ClientSpanSupport.exit(context,response,null);assertFalse(response.isDone());assertTrue(exporter.spans.isEmpty());response.completeExceptionally(new CancellationException("test"));assertEquals(1,exporter.spans.size());assertTrue(exporter.spans.get(0).status().isError());
        String existing="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";HttpRequest owned=HttpRequest.newBuilder(original,(k,v)->true).header("traceparent",existing).build();Object[] input={owned};var other=ClientSpanSupport.enter("jdk.internal.net.http.HttpClientFacade","send",null,input);assertSame(owned,input[0]);ClientSpanSupport.exit(other,null,null);
    }
    @Test void boundedRoutesAndPendingStopDoNotBecomeComplete()throws Exception{
        for(int i=0;i<300;i++){var ticket=RequestMetrics.begin();RequestMetrics.finish(ticket,"GET","/route"+i,200,false);}
        RequestMetrics.begin();RequestMetrics.stop();Map<String,Object> stats=RequestMetrics.snapshot();assertEquals(300L,stats.get("completedRequests"));assertEquals(1L,stats.get("pendingRequests"));assertFalse((Boolean)stats.get("captureComplete"));assertTrue(((List<?>)stats.get("endpoints")).size()<=257);assertTrue((Long)stats.get("overflowRouteRequests")>0);
        var saved=new com.fasterxml.jackson.databind.ObjectMapper().readTree(directory.resolve("request-metrics.json").toFile());assertTrue(saved.path("latencyBucketUpperBoundsNanos").isArray());
        for(var row:saved.path("endpoints")){assertTrue(row.path("latencyBucketCounts").isArray());long sum=0;for(var count:row.path("latencyBucketCounts"))sum+=count.asLong();assertEquals(row.path("completed").asLong(),sum);}
    }
    public static class Request implements javax.servlet.ServletRequest {
        final String parent;final Map<String,Object> attrs=new HashMap<>();Request(String parent){this.parent=parent;}
        public String getMethod(){return "GET";}public String getRequestURI(){return "/orders/123";}public String getHeader(String key){return key.equals("traceparent")?parent:"vendor=value";}
        public Object getAttribute(String key){return key.contains("bestMatchingPattern")?"/orders/{id}":attrs.get(key);}public void setAttribute(String key,Object value){attrs.put(key,value);}
        public Mapping getHttpServletMapping(){return new Mapping();}public boolean isAsyncStarted(){return false;}
    }
    public static class Mapping {public String getPattern(){return "/";}}
    public static class Response {final int status;Response(int status){this.status=status;}public int getStatus(){return status;}}
    @Test void apacheCleanupRemovesOnlyOwnedHeadersAndKeepsRequestIdentity(){
        var parent=new SpanContext(MethodSpanAdvice.nextTraceId(),MethodSpanAdvice.nextId(),"","example.Parent","work","test",0,System.nanoTime(),System.currentTimeMillis(),"");parent.traceSampling.traceState("agent=value");MethodSpanAdvice.SPAN_STACK.get().push(parent);
        var request=new ApacheRequest();Object[] arguments={request};var context=ClientSpanSupport.enter("org.apache.http.impl.client.InternalHttpClient","doExecute",null,arguments);assertNotNull(context);assertSame(request,arguments[0]);
        request.headers.add(new Header("tracestate","vendor=added"));request.headers.add(new Header("traceparent","caller-added"));ClientSpanSupport.exit(context,null,null);
        assertEquals(2,request.headers.size());assertEquals("vendor=added",request.getFirstHeader("tracestate").getValue());assertEquals("caller-added",request.getFirstHeader("traceparent").getValue());MethodSpanAdvice.removeContext(parent);
    }
    public static class ApacheRequest {
        final List<Header> headers=new ArrayList<>();public Line getRequestLine(){return new Line();}
        public Header getFirstHeader(String name){return headers.stream().filter(h->h.name.equals(name)).findFirst().orElse(null);}
        public void setHeader(String name,String value){headers.removeIf(h->h.name.equals(name));headers.add(new Header(name,value));}
        public void removeHeader(Header header){headers.remove(header);}
    }
    public static class Header {final String name,value;Header(String name,String value){this.name=name;this.value=value;}public String getValue(){return value;}}
    public static class Line {public String getMethod(){return "GET";}public String getUri(){return "/test";}}
}
