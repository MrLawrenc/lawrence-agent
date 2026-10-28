package com.agentmonitor.core.performance;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.service.MonitoringRuntime;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.ToolProvider;

/** Controlled synthetic A/B/A probe. Does not attach to any user process. */
@Tag("performance")
class AgentScaleBenchmarkTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void measures2250LoadedBusinessClassesWithARealAgentAndRestoredControl() throws Exception {
        Path output=Path.of("build/performance/agent-scale").toAbsolutePath();Files.createDirectories(output);
        Path generated=output.resolve("generated");Files.createDirectories(generated);
        var source=new StringBuilder("package agentfixtures.performance.generated; public class Catalog {");
        for(int i=0;i<2250;i++) source.append("public static class C").append(i).append(" { public static long work(long x) { for(int n=0;n<2048;n++){x^=x>>>13;x*=0x5DEECE66DL;x+=n;}return x;} }");
        source.append('}');Path javaSource=generated.resolve("Catalog.java");Files.writeString(javaSource,source);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-g:none","-d",generated.toString(),javaSource.toString()));
        Process target=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xms128m","-Xmx768m","-Xbatch","-cp",
                Path.of("build/classes/java/test").toAbsolutePath().toString(),"agentfixtures.performance.ScaleWorkload",generated.toString())
                .redirectError(output.resolve("target-stderr.log").toFile()).start();
        var input=new BufferedReader(new InputStreamReader(target.getInputStream()));var commands=new PrintWriter(target.getOutputStream(),true);
        var result=new LinkedHashMap<String,Object>();MonitoringRuntime runtime=null;
        try {
            assertEquals("READY",line(input));
            result.put("measuredAt",java.time.Instant.now().toString());result.put("java",System.getProperty("java.version"));result.put("os",System.getProperty("os.name")+" / "+System.getProperty("os.arch"));
            result.put("jvmArguments",List.of("-Xms128m","-Xmx768m","-Xbatch"));result.put("businessClasses",2250);result.put("extraRootClasses",1);result.put("iterationsPerBusinessCall",2048);
            result.put("warmupRoundsPerPhase",Map.of("baseline",10,"instrumented",3,"restored",10));result.put("measuredRoundsPerPhase",3);
            for(int i=0;i<10;i++)round(input,commands);
            result.put("baseline",samples(input,commands,3));result.put("baselineRssKiB",rss(target.pid()));
            var defaults=MonitoringConfig.defaults();
            var config=new MonitoringConfig(2,new MonitoringConfig.Scope(List.of("agentfixtures.performance.generated"),List.of("agentfixtures.performance.BatchRoot"),List.of("cls:agentfixtures.performance.generated.Catalog"),List.of()),
                new MonitoringConfig.Sampling(100,50,512,1,MonitoringConfig.TailOverflowPolicy.PROMOTE),
                new MonitoringConfig.Output(output.resolve("captures").toString(),defaults.output().exporters(),new MonitoringConfig.Capture(true,true,false),defaults.output().spanFiles(),defaults.output().retention()),
                defaults.resource(),new MonitoringConfig.Dependencies(false,false),defaults.analysis());
            runtime=new MonitoringRuntime(Long.toString(target.pid()),config);
            long start=System.nanoTime();runtime.start();result.put("attachToReadyMillis",(System.nanoTime()-start)/1e6);
            var actual=JSON.readTree(runtime.session().agentLogsDirectory().resolve("enhancement-result.json").toFile());
            result.put("actualEnhancement",actual.path("summary"));assertEquals(2251,actual.path("summary").path("transformedClasses").asInt());
            long expected=0;
            for(int i=0;i<3;i++){round(input,commands);expected+=2251;awaitReceived(runtime,expected);}
            var active=new ArrayList<JsonNode>();
            for(int i=0;i<3;i++){active.add(round(input,commands));expected+=2251;awaitReceived(runtime,expected);}
            result.put("instrumented",active);result.put("instrumentedRssKiB",rss(target.pid()));
            long stopStarted=System.nanoTime();var stopped=runtime.stopAndAwaitRestore();
            result.put("stopAndRestoreMillis",(System.nanoTime()-stopStarted)/1e6);result.put("restored",stopped.restored());result.put("captureQuality",stopped.captureQuality());
            result.put("sessionDirectory",runtime.session().sessionDirectory().toString());assertTrue(stopped.restored(),stopped.message());assertTrue(stopped.outputDrained(),stopped.message());
            assertEquals(expected,stopped.captureQuality().acceptedSpans());
            for(int i=0;i<10;i++)round(input,commands);
            result.put("restoredControl",samples(input,commands,3));result.put("restoredRssKiB",rss(target.pid()));
            result.put("configuration",config);
            result.put("limitations",List.of("Synthetic compute workload, 2250 distinct loaded business classes plus one root. Not the user's application.",
                "Three measured rounds per phase are preliminary observations, not stable P95 estimates or production overhead guarantees.",
                "A/B/A sequential phases, -Xbatch to reduce background JIT noise; recompilation, GC and scheduling remain. Raw values retained.",
                "RSS includes loaded Agent, retained telemetry and JVM state; heap/RSS differences are not isolated allocations or memory leaks.",
                "Waiting for local ingestion between rounds is excluded from workload timing. This is not an exporter saturation/throughput test.",
                "Capture arguments/returns enabled, sampling 100%, OTLP+file. Business call rate is 2250/round elapsed, not population service throughput."));
        } finally {
            if(runtime!=null)runtime.close();commands.println("EXIT");if(!target.waitFor(5,TimeUnit.SECONDS))target.destroyForcibly();
            Files.writeString(output.resolve("result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
    private static List<JsonNode> samples(BufferedReader input,PrintWriter commands,int count) throws Exception {var values=new ArrayList<JsonNode>();for(int i=0;i<count;i++)values.add(round(input,commands));return values;}
    private static JsonNode round(BufferedReader input,PrintWriter commands) throws Exception {commands.println("RUN");return JSON.readTree(line(input));}
    private static String line(BufferedReader input) throws Exception {return CompletableFuture.supplyAsync(() -> {try {return input.readLine();}catch(IOException error){throw new UncheckedIOException(error);}}).get(90,TimeUnit.SECONDS);}
    private static void awaitReceived(MonitoringRuntime runtime,long expected) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(runtime.getEventCount()<expected && System.nanoTime()<deadline)Thread.sleep(10);assertEquals(expected,runtime.getEventCount());}
    private static long rss(long pid) throws Exception {
        if(!System.getProperty("os.name").contains("Mac") && !System.getProperty("os.name").contains("Linux"))return -1;
        Process p=new ProcessBuilder("ps","-o","rss=","-p",Long.toString(pid)).start();String text=new String(p.getInputStream().readAllBytes()).trim();return p.waitFor()==0 ? Long.parseLong(text) : -1;
    }
}
