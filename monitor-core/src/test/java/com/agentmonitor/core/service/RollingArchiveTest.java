package com.agentmonitor.core.service;

import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.trace.TraceNode;
import com.agentmonitor.model.span.SpanKind;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RollingArchiveTest {
    @TempDir Path directory;
    @Test void stoppingPausesRotationButKeepsFinalRootsAndDiagnosticSamples()throws Exception {
        var master=MonitoringSession.create(directory.toString());
        try(var archive=new RollingCaptureArchive(master,MonitoringConfig.defaults(),500,16L*1024*1024)){
            archive.start();archive.pauseRotation();Thread.sleep(750);
            archive.sample(Map.of("timestampMillis",System.currentTimeMillis()));
            archive.accept("during-stop",new TraceNode("example.Service","call","worker",10_000_000,false,0).freeze());
            var before=new ObjectMapper().readTree(archive.manifestPath().toFile());
            assertEquals(0,before.path("windows").size(),"The timer must not close a window while diagnostics is finalizing");
        }
        var manifest=new ObjectMapper().readTree(master.sessionDirectory().resolve("rolling-capture.json").toFile());
        assertEquals(1,manifest.path("windows").size());
        var window=manifest.path("windows").get(0);assertEquals(1,window.path("roots").asInt());
        Path path=Path.of(window.path("sessionDirectory").asText());
        var metrics=new ObjectMapper().readTree(path.resolve("diagnostics/manifest.json").toFile());
        assertEquals(1,metrics.path("samples").asInt());
    }
    @Test void rootCrossingAWindowRemainsWholeAndDoesNotInventExporterAcknowledgement()throws Exception {
        var master=MonitoringSession.create(directory.toString());var defaults=MonitoringConfig.defaults();
        var config=defaults.withScope(new MonitoringConfig.Scope(List.of(),List.of("example.Service"),List.of(),List.of()));
        try(var archive=new RollingCaptureArchive(master,config,500,16L*1024*1024)){
            archive.start();Thread.sleep(700);TraceNode root=new TraceNode("example.Service","call","worker",900000000,false,0,"()V","","","",SpanKind.BUSINESS,Map.of("agent.span.started_at_epoch_millis",Long.toString(System.currentTimeMillis()-900)));
            root.addChild(new TraceNode("example.Dao","query","worker",10000000,false,1));archive.accept("crossing",root.freeze());
        }
        var json=new ObjectMapper();JsonNode manifest=json.readTree(master.sessionDirectory().resolve("rolling-capture.json").toFile());assertTrue(manifest.path("singleAttach").asBoolean());assertTrue(manifest.path("windows").size()>=2);
        JsonNode found=null;for(JsonNode window:manifest.path("windows"))if(window.path("roots").asInt()>0)found=window;assertNotNull(found);assertEquals(1,found.path("crossWindowRoots").asInt());
        Path session=Path.of(found.path("sessionDirectory").asText());JsonNode report=json.readTree(session.resolve("reports/performance-report.json").toFile());assertFalse(report.path("captureComplete").asBoolean());assertEquals(2,report.path("analyzedSpans").asInt());assertEquals(2,Files.readAllLines(session.resolve("spans/rolling-spans.jsonl")).size());
    }
}
