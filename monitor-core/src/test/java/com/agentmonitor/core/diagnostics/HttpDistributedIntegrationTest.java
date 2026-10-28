package com.agentmonitor.core.diagnostics;

import com.agentmonitor.core.service.*;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class HttpDistributedIntegrationTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
    private final List<Process> children=new ArrayList<>();
    private int redisPort;
    private Process launch(String role,String downstream)throws Exception{
        Path base=Files.createDirectories(directory.resolve(role));Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.rmi.server.hostname=127.0.0.1","-Dagent.test.redis.port="+redisPort,"-cp",System.getProperty("java.class.path"),"agentfixtures.http.HttpServiceTarget",role,downstream,base.toString()).redirectError(base.resolve("target.err").toFile()).start();children.add(child);return child;
    }
    private String address(Process child)throws Exception{String line=child.inputReader().readLine();assertNotNull(line,"Target failed: "+directory);assertTrue(line.startsWith("READY "),line);return "http://127.0.0.1:"+line.substring(6);}
    private MonitoringConfig config(String role,int sampling){
        var d=MonitoringConfig.defaults();return new MonitoringConfig(2,new MonitoringConfig.Scope(List.of("agentfixtures.http"),List.of(),List.of(),List.of()),
                new MonitoringConfig.Sampling(sampling,60_000,512,1,MonitoringConfig.TailOverflowPolicy.PROMOTE),
                new MonitoringConfig.Output(directory.resolve("captures").toString(),d.output().exporters(),new MonitoringConfig.Capture(false,false,false),d.output().spanFiles(),d.output().retention()),
                new MonitoringConfig.Resource(role,"test","test"),d.dependencies(),d.analysis(),new MonitoringConfig.Diagnostics(false,1000,1000,100,false,false,d.diagnostics().trigger()));
    }
    @Test @Timeout(100) void realThreeServiceChainRepeatedCallsAsyncAndIndependentMetrics()throws Exception{
        try(var redis=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            redisPort=redis.getLocalPort();var redisReply=java.util.concurrent.CompletableFuture.runAsync(()->{
                try(var socket=redis.accept()){socket.setSoTimeout(5000);var input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));assertEquals("*1",input.readLine());assertEquals("$4",input.readLine());assertEquals("PING",input.readLine());Thread.sleep(40);socket.getOutputStream().write("+PONG\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
                catch(Exception e){throw new RuntimeException(e);}
            });
            Process c=launch("C","-");String cAddress=address(c);Process b=launch("B",cAddress);String bAddress=address(b);Process a=launch("A",bAddress);String aAddress=address(a);
            var http=HttpClient.newHttpClient();List<Path> sessions=new ArrayList<>();
            try(var cr=new MonitoringRuntime(""+c.pid(),config("C",0));var br=new MonitoringRuntime(""+b.pid(),config("B",0));var ar=new MonitoringRuntime(""+a.pid(),config("A",100))){
                cr.start();br.start();ar.start();sessions.add(ar.session().sessionDirectory());sessions.add(br.session().sessionDirectory());sessions.add(cr.session().sessionDirectory());
                var result=http.send(HttpRequest.newBuilder(URI.create(aAddress+"/work")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,result.statusCode(),result.body());assertTrue(result.body().contains("B:00-"),result.body());assertTrue(result.body().contains("C:00-"),result.body());
                assertEquals("payload-once",http.send(HttpRequest.newBuilder(URI.create(aAddress+"/body")).POST(HttpRequest.BodyPublishers.ofString("payload-once")).build(),HttpResponse.BodyHandlers.ofString()).body());
                assertEquals("ASYNC",http.send(HttpRequest.newBuilder(URI.create(aAddress+"/async")).build(),HttpResponse.BodyHandlers.ofString()).body());
                assertEquals(202,http.send(HttpRequest.newBuilder(URI.create(aAddress+"/clientAsync")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(503,http.send(HttpRequest.newBuilder(URI.create(aAddress+"/fail")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals("APACHE_OK",http.send(HttpRequest.newBuilder(URI.create(aAddress+"/apache")).build(),HttpResponse.BodyHandlers.ofString()).body());
                assertEquals("TIMEOUT_OK",http.send(HttpRequest.newBuilder(URI.create(aAddress+"/apacheTimeout")).build(),HttpResponse.BodyHandlers.ofString()).body());
                assertEquals("CANCEL_OK",http.send(HttpRequest.newBuilder(URI.create(aAddress+"/apacheCancel")).build(),HttpResponse.BodyHandlers.ofString()).body());
                assertEquals("PONG",http.send(HttpRequest.newBuilder(URI.create(aAddress+"/redis")).build(),HttpResponse.BodyHandlers.ofString()).body());redisReply.get(5,TimeUnit.SECONDS);
                assertEquals("PUBLISH_OK",http.send(HttpRequest.newBuilder(URI.create(aAddress+"/rabbit")).build(),HttpResponse.BodyHandlers.ofString()).body());
                for(int i=0;i<2;i++)assertEquals(200,http.send(HttpRequest.newBuilder(URI.create(cAddress+"/orders/"+i)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                Thread.sleep(900);
                for(var runtime:List.of(ar,br,cr)){var stop=runtime.stopAndAwaitRestore();assertTrue(stop.restored(),stop.message());assertTrue(stop.outputDrained(),stop.message());}
            }
            var json=new ObjectMapper();JsonNode am=json.readTree(sessions.get(0).resolve("agent-logs/request-metrics.json").toFile());assertEquals(10,am.path("completedRequests").asInt(),am.toPrettyString());assertEquals(0,am.path("pendingRequests").asInt());
            JsonNode async=null;for(JsonNode endpoint:am.path("endpoints"))if(endpoint.path("endpoint").asText().equals("GET /async"))async=endpoint;assertNotNull(async);assertTrue(async.path("maxMillis").asDouble()>=250,async.toPrettyString());
            assertTrue(am.path("latencyBucketUpperBoundsNanos").isArray());for(JsonNode endpoint:am.path("endpoints")){long total=0;assertTrue(endpoint.path("latencyBucketCounts").isArray());for(JsonNode count:endpoint.path("latencyBucketCounts"))total+=count.asLong();assertEquals(endpoint.path("completed").asLong(),total);}
            JsonNode cm=json.readTree(sessions.get(2).resolve("agent-logs/request-metrics.json").toFile());boolean grouped=false;for(JsonNode row:cm.path("endpoints"))if(row.path("endpoint").asText().equals("GET /orders/{id}")){grouped=true;assertEquals(2,row.path("completed").asInt());}assertTrue(grouped,cm.toPrettyString());
            var replay=new OfflineTraceReplayLoader().load(sessions.get(0));var root=replay.roots().stream().filter(n->n.getAttributes().getOrDefault("url.path","").equals("/work")).findFirst().orElseThrow();
            assertTrue(replay.roots().stream().noneMatch(n->n.getAttributes().getOrDefault("url.path","").equals("/v1/traces")),"Telemetry transport must not instrument itself");
            JsonNode joined=json.valueToTree(new DistributedTrace().correlate(sessions,root.getTraceId()));assertTrue(joined.path("spans").size()>=9,joined.toPrettyString());assertTrue(joined.path("missingParents").isEmpty(),joined.toPrettyString());
            long cross=0;for(JsonNode edge:joined.path("edges"))if(edge.path("crossSession").asBoolean())cross++;assertEquals(4,cross,joined.toPrettyString());
            assertEquals(0,new OfflineTraceReplayLoader().load(sessions.get(1)).orphanSpans());
            JsonNode diagnosis=json.valueToTree(new SymptomDiagnosis().analyze(sessions.get(0),"errors"));assertFalse(diagnosis.path("findings").isEmpty());
            JsonNode report=json.readTree(sessions.get(0).resolve("reports/performance-report.json").toFile());assertTrue(report.path("diagnosticAggregates").path("dependencies").toString().contains("REDIS"),report.toPrettyString());
            assertTrue(report.path("diagnosticAggregates").path("dependencies").toString().contains("MESSAGING"),report.toPrettyString());
            JsonNode rebuilt=json.valueToTree(new SessionAnalyzer().analyze(sessions.get(0)));assertEquals(10,rebuilt.path("report").path("requestMetrics").path("completedRequests").asInt());
            Path artifact=Path.of("build/http-distributed-verification").toAbsolutePath();Files.createDirectories(artifact);
            Path saved=Files.createDirectories(artifact.resolve("run-"+System.currentTimeMillis()));List<Path> durable=new ArrayList<>();
            for(int i=0;i<sessions.size();i++){Path source=sessions.get(i),destination=saved.resolve(List.of("A","B","C").get(i));
                try(var files=Files.walk(source)){for(Path file:files.toList()){Path output=destination.resolve(source.relativize(file));if(Files.isDirectory(file))Files.createDirectories(output);else Files.copy(file,output);}}durable.add(destination);
            }
            var result=Map.of("joinedTrace",new DistributedTrace().correlate(durable,root.getTraceId()),"requests",am,"diagnosis",new SymptomDiagnosis().analyze(durable.get(0),"errors"),"sessions",durable.stream().map(Path::toString).toList(),"realJvms",3);
            String encoded=json.writerWithDefaultPrettyPrinter().writeValueAsString(result);Files.writeString(saved.resolve("result.json"),encoded);Files.writeString(artifact.resolve("result.json"),encoded);Files.writeString(artifact.resolve("latest.txt"),saved.toString());
        }finally{for(Process child:children){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
}
