package com.agentmonitor.core.service;

import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RollingIntegrationTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON=new ObjectMapper();
    private MonitoringConfig config(){
        var d=MonitoringConfig.defaults();
        return new MonitoringConfig(2,new MonitoringConfig.Scope(List.of(),List.of("agentfixtures.rolling.TargetLoop"),List.of(),List.of("main")),
            new MonitoringConfig.Sampling(100,50,512,1,MonitoringConfig.TailOverflowPolicy.PROMOTE),
            new MonitoringConfig.Output(directory.resolve("captures").toString(),d.output().exporters(),new MonitoringConfig.Capture(false,false,false),d.output().spanFiles(),d.output().retention()),
            d.resource(),new MonitoringConfig.Dependencies(false,false),d.analysis(),new MonitoringConfig.Diagnostics(true,500,1000,2,false,false,d.diagnostics().trigger()),
            new MonitoringConfig.Overhead(false,16L*1024*1024,75,35,1,1_000_000_000,1000),d.experiment());
    }
    private Process target()throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.rmi.server.hostname=127.0.0.1","-cp",Path.of("build/classes/java/test").toAbsolutePath().toString(),"agentfixtures.rolling.TargetLoop").redirectError(directory.resolve("target-stderr.log").toFile()).start();
    }
    @Test @Timeout(60) void oneAttachmentArchivesWholeCrossWindowRootsAndKeepsMetricsBeyondChunkLimit()throws Exception {
        Process child=target();var input=child.inputReader();var output=new PrintWriter(child.getOutputStream(),true);assertEquals("READY",input.readLine());
        try(var runtime=new MonitoringRuntime(Long.toString(child.pid()),config())) {
            runtime.enableRolling(500,64L*1024*1024);runtime.start();output.println("RUN");assertEquals("WORK_DONE",input.readLine());
            Path diagnosticManifest=runtime.session().sessionDirectory().resolve("diagnostics/manifest.json");long sampleDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<sampleDeadline){if(Files.isRegularFile(diagnosticManifest)&&JSON.readTree(diagnosticManifest.toFile()).path("samples").asInt()>=4)break;Thread.sleep(100);}
            var stop=runtime.stopAndAwaitRestore();assertTrue(stop.restored(),stop.message());assertEquals(3,runtime.getEventCount());assertTrue(runtime.rollingFailure().isBlank(),runtime.rollingFailure());
            JsonNode manifest=JSON.readTree(runtime.rollingManifestPath().toFile());assertTrue(manifest.path("singleAttach").asBoolean());assertTrue(manifest.path("windows").size()>=3);
            int roots=0,crossing=0;for(JsonNode window:manifest.path("windows")){roots+=window.path("roots").asInt();crossing+=window.path("crossWindowRoots").asInt();if(window.path("roots").asInt()>0){JsonNode report=JSON.readTree(Path.of(window.path("sessionDirectory").asText()).resolve("reports/performance-report.json").toFile());assertFalse(report.path("captureComplete").asBoolean());assertEquals(3,report.path("analyzedSpans").asInt());}}
            assertEquals(1,roots);assertEquals(1,crossing);
            JsonNode diagnostics=JSON.readTree(runtime.session().sessionDirectory().resolve("diagnostics/manifest.json").toFile());assertTrue(diagnostics.path("samples").asInt()>2,diagnostics.toString());assertTrue(diagnostics.path("rollingChunks").asInt()>1);assertFalse(diagnostics.path("sampleLimitReached").asBoolean());
            Path evidence=directory.resolve("verification");Files.createDirectories(evidence);Files.writeString(evidence.resolve("session.txt"),runtime.session().sessionDirectory().toString());assertTrue(child.isAlive());
        }finally{output.println("EXIT");if(!child.waitFor(5,TimeUnit.SECONDS))child.destroyForcibly();}
    }
    @Test @Timeout(60) void archiveIoFailureCannotPreventRestoredLeaseRelease()throws Exception {
        Process child=target();var input=child.inputReader();var output=new PrintWriter(child.getOutputStream(),true);assertEquals("READY",input.readLine());
        try(var runtime=new MonitoringRuntime(Long.toString(child.pid()),config())) {
            runtime.enableRolling(60_000,64L*1024*1024);runtime.start();
            Path windows=runtime.session().sessionDirectory().resolve("windows");Path marker;try(var paths=Files.walk(windows)){marker=paths.filter(p->p.getFileName().toString().equals("session.json")).findFirst().orElseThrow();}
            Path obstruction=marker.getParent().resolve("reports/performance-report.json");Files.createDirectory(obstruction);Files.writeString(obstruction.resolve("cannot-replace"),"fixture");
            var stop=runtime.stopAndAwaitRestore();assertTrue(stop.restored(),stop.message());assertFalse(runtime.rollingFailure().isBlank());
            try(var lease=TargetJvmLease.acquire(Long.toString(child.pid()))){lease.state("IDLE");}assertTrue(child.isAlive());
        }finally{output.println("EXIT");if(!child.waitFor(5,TimeUnit.SECONDS))child.destroyForcibly();}
    }
}
