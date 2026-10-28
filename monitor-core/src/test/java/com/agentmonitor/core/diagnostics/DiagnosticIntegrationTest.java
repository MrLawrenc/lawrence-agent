package com.agentmonitor.core.diagnostics;
import com.agentmonitor.core.service.MonitoringRuntime;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DiagnosticIntegrationTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path directory;
    @Test @org.junit.jupiter.api.Timeout(60) void recordsRealJvmThreadsAsyncPoolsInflightJfrAndEnvironment()throws Exception{
        Path signal=directory.resolve("go");Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.rmi.server.hostname=127.0.0.1","-cp",System.getProperty("java.class.path"),"agentfixtures.diagnostics.DiagnosticTarget",signal.toString()).redirectError(directory.resolve("target.err").toFile()).start();
        var reader=child.inputReader();assertEquals("READY",reader.readLine());
        try{
            try(var initial=new TargetJvm(Long.toString(child.pid()))){assertNotNull(initial.sample().get("heap"));}
            var defaults=MonitoringConfig.defaults();
            var diagnostics=new MonitoringConfig.Diagnostics(true,500,1000,100,true,true,new MonitoringConfig.Trigger(true,100,100,999,1,500));
            var config=new MonitoringConfig(2,new MonitoringConfig.Scope(List.of("agentfixtures.diagnostics"),List.of(),List.of(),List.of()),
                    new MonitoringConfig.Sampling(100,0,512,1,MonitoringConfig.TailOverflowPolicy.PROMOTE),
                    new MonitoringConfig.Output(directory.resolve("captures").toString(),defaults.output().exporters(),defaults.output().capture(),defaults.output().spanFiles(),defaults.output().retention()),
                    defaults.resource(),defaults.dependencies(),defaults.analysis(),diagnostics);
            Path session;
            try(var runtime=new MonitoringRuntime(Long.toString(child.pid()),config)){
                runtime.start();session=runtime.session().sessionDirectory();
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(!runtime.diagnosticSnapshot().containsKey("timestampMillis")&&System.nanoTime()<deadline)Thread.sleep(100);
                Files.writeString(signal,"go");assertEquals("WORK_DONE",reader.readLine());
                Thread.sleep(2600);
                var stop=runtime.stopAndAwaitRestore();assertTrue(stop.restored(),stop.message());
                assertFalse(stop.outputDrained(),"unfinished root must mark collection incomplete");
            }
            var json=new ObjectMapper();Path diag=session.resolve("diagnostics");
            JsonNode manifest=json.readTree(diag.resolve("manifest.json").toFile());
            assertTrue(manifest.path("samples").asInt()>1,manifest.toPrettyString());assertTrue(manifest.path("errors").isEmpty(),manifest.toPrettyString());
            assertTrue(Files.size(diag.resolve("profile.jfr"))>0);assertTrue(Files.isRegularFile(diag.resolve("flamegraph.html")));
            assertTrue(Files.isRegularFile(diag.resolve("trigger.json")),manifest.toPrettyString());
            JsonNode live=json.readTree(session.resolve("agent-logs/live-invocations.json").toFile());
            assertFalse(live.path("roots").isEmpty(),live.toPrettyString());assertTrue(live.path("threadPools").size()>0,live.toPrettyString());assertTrue(live.path("rejectedTasks").asLong()>=8,live.toPrettyString());
            JsonNode report=json.readTree(session.resolve("reports/performance-report.json").toFile());
            assertTrue(report.path("diagnosticAggregates").path("asyncLinks").size()>0,report.toPrettyString());
            assertTrue(report.path("diagnosticAggregates").path("connectionAcquisition").size()>0,report.toPrettyString());
            assertTrue(report.path("diagnosticAggregates").path("exceptions").size()>0,report.toPrettyString());
            assertTrue(Files.isRegularFile(session.resolve("agent-logs/class-origins.json")));
            JsonNode jfr=json.readTree(diag.resolve("jfr-analysis.json").toFile());
            assertTrue(jfr.path("exceptionStormCandidates").size()>0,jfr.toPrettyString());
            assertTrue(jfr.path("eventCounts").path("jdk.GCHeapSummary").asLong()>0,jfr.toPrettyString());
            assertTrue(report.path("diagnosticAggregates").path("asyncLinks").size()>=16,"Expected ThreadPoolExecutor and CompletableFuture links");
            DiagnosticCommands.execute(new String[]{"diag","classes","--session",session.toString(),"--query","DiagnosticTarget"});
            Path verification=Path.of("build/diagnostic-verification").toAbsolutePath();Path saved=verification.resolve(session.getFileName());
            try(var files=Files.walk(session)){for(Path file:files.toList()){Path output=saved.resolve(session.relativize(file));if(Files.isDirectory(file))Files.createDirectories(output);else Files.copy(file,output,StandardCopyOption.REPLACE_EXISTING);}}
            Files.writeString(verification.resolve("latest.txt"),saved.toString());
            try(var target=new TargetJvm(Long.toString(child.pid()))){
                var threads=target.threads();assertTrue(((List<?>)threads.get("threads")).size()>0);assertTrue(((long[])threads.get("deadlockedThreadIds")).length>=2);
                assertFalse(((List<?>)target.histogram(false).get("classes")).isEmpty());
                Path heap=directory.resolve("heap.hprof");target.heapDump(heap);assertTrue(Files.size(heap)>1000);
            }
        }finally{child.destroyForcibly();child.waitFor();}
    }
}
