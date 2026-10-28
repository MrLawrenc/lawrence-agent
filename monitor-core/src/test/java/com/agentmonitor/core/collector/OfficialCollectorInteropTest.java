package com.agentmonitor.core.collector;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.service.MonitoringRuntime;
import com.agentmonitor.core.model.SpanEvent;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("officialCollector")
class OfficialCollectorInteropTest {
    @TempDir Path directory;
    @Test void realAgentToLocalPipelineToOfficialCollectorBatchToOtlpBackend() throws Exception {
        List<SpanEvent> backendSpans = new CopyOnWriteArrayList<>();
        var pipeline = new TracePipeline(List.of(span -> span), List.of(span -> { backendSpans.add(span); return true; }));
        try (var backend = new OtlpHttpReceiver(null,"",pipeline,resource -> {})) {
            String backendEndpoint = backend.start();
            int port; try (var socket = new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
            Path configFile = directory.resolve("collector.yml"), logs = directory.resolve("official-collector.log");
            Files.writeString(configFile, """
                    receivers:
                      otlp:
                        protocols:
                          http:
                            endpoint: 127.0.0.1:%d
                    processors:
                      batch:
                        timeout: 50ms
                        send_batch_size: 16
                    exporters:
                      otlphttp/backend:
                        endpoint: %s
                        compression: gzip
                      debug:
                        verbosity: detailed
                    service:
                      pipelines:
                        traces:
                          receivers: [otlp]
                          processors: [batch]
                          exporters: [otlphttp/backend, debug]
                    """.formatted(port, backendEndpoint.substring(0,backendEndpoint.length()-"/v1/traces".length())));
            Process collector = new ProcessBuilder(System.getProperty("agent.monitor.otelcol"),"--config",configFile.toString())
                    .redirectErrorStream(true).redirectOutput(logs.toFile()).start();
            Process target = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                    Path.of("build/classes/java/test").toAbsolutePath().toString(),"agentfixtures.apppipeline.TargetLoop").redirectErrorStream(true).start();
            var targetOut = new PrintWriter(target.getOutputStream(),true);
            var targetIn = new BufferedReader(new InputStreamReader(target.getInputStream()));
            try {
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                boolean listening=false;
                while (collector.isAlive() && System.nanoTime()<deadline) {
                    try(var connection=new java.net.Socket("127.0.0.1",port)){listening=true;break;}
                    catch(IOException notReady){Thread.sleep(25);}
                }
                assertTrue(listening,()->read(logs)); assertEquals("READY",targetIn.readLine());
                var d=MonitoringConfig.defaults();
                var configuration = new MonitoringConfig(2,new MonitoringConfig.Scope(List.of(),List.of("agentfixtures.apppipeline.TargetLoop"),List.of(),List.of("main")),
                        new MonitoringConfig.Sampling(100,50,512,1,MonitoringConfig.TailOverflowPolicy.PROMOTE),
                        new MonitoringConfig.Output(directory.resolve("captures").toString(),d.output().exporters(),new MonitoringConfig.Capture(false,false,false),d.output().spanFiles(),d.output().retention(),"http://127.0.0.1:"+port+"/v1/traces"),
                        d.resource(),new MonitoringConfig.Dependencies(false,false),d.analysis());
                try(var runtime=new MonitoringRuntime(Long.toString(target.pid()),configuration)){
                    runtime.start(); targetOut.println("RUN");
                    CompletableFuture.runAsync(()->{try{for(String line;(line=targetIn.readLine())!=null;)if(line.equals("WORK_DONE"))return;throw new AssertionError("Target exited");}catch(IOException error){throw new RuntimeException(error);}}).get(5,TimeUnit.SECONDS);
                    deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                    while(backendSpans.size()<3&&System.nanoTime()<deadline)Thread.sleep(20);
                    var stop=runtime.stopAndAwaitRestore(); assertTrue(stop.restored(),stop::message);assertTrue(stop.outputDrained(),stop::message);
                    assertEquals(3,backendSpans.size(),()->read(logs));assertEquals(3,runtime.getEventCount());
                    assertTrue(backendSpans.stream().allMatch(s->s.traceId().matches("[0-9a-f]{32}")&&s.spanId().matches("[0-9a-f]{16}")));
                    assertTrue(new ObjectMapper().readTree(runtime.getPerformanceReportPath().toFile()).path("captureComplete").asBoolean());
                    Path evidence=Path.of("build/official-collector-interop");Files.createDirectories(evidence);
                    Files.copy(logs,evidence.resolve("collector.log"),StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(runtime.getPerformanceReportPath(),evidence.resolve("performance-report.json"),StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(evidence.resolve("result.json"),"{\"verified\":true,\"receivedByOfficialCollectorBackend\":3,\"restored\":true}");
                }
            } finally {
                targetOut.println("EXIT");if(!target.waitFor(3,TimeUnit.SECONDS))target.destroyForcibly();
                collector.destroy();if(!collector.waitFor(5,TimeUnit.SECONDS))collector.destroyForcibly();
            }
        }
    }
    private static String read(Path path){try{return Files.readString(path);}catch(IOException error){return error.toString();}}
}
