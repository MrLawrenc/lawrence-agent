package com.agentmonitor.core.diagnostics;

import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.util.AtomicFiles;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** A bounded evidence lifecycle, independent of bytecode restore success. */
public final class DiagnosticRecorder implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final String sessionId; private final String startedAt; private final String pid; private final MonitoringConfig.Diagnostics config; private final Path directory,livePath;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"monitor-diagnostics");t.setDaemon(true);return t;});
    private final Callable<TargetJvm> connector;
    private volatile TargetJvm target; private JfrProfiler profiler; private final List<String> errors=new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> firstSample=new CompletableFuture<>();
    private final Object manifestLock=new Object();
    private final java.util.concurrent.atomic.AtomicBoolean closeRequested=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean finalizationTimedOut;
    private final ArrayDeque<Map<String,Object>> recentMetrics=new ArrayDeque<>();
    private volatile java.util.function.Consumer<Map<String,Object>> onSample;
    public void setOnSample(java.util.function.Consumer<Map<String,Object>> handler){onSample=handler;}
    private volatile Map<String,Object> latest=Map.of("status","waiting");
    private final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<Path,java.util.concurrent.atomic.AtomicBoolean> checkpoints=new ConcurrentHashMap<>();
    private final ScheduledExecutorService checkpointTimer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"diagnostic-checkpoint-deadline");t.setDaemon(true);return t;});
    private boolean rolling;private long chunkBytes,jfrStartedMillis;private int chunk=1,chunkSamples;
    public void enableRolling(){if(started.get())throw new IllegalStateException("Configure rolling before start");rolling=true;}
    private long failedAttempts,samplingGaps,lastSampleMillis,writtenBytes; private boolean byteLimitReached;
    private final Map<String,Integer> triggerStreaks=new HashMap<>();
    private Map<String,Object> lastThreadSnapshot=Map.of();
    private volatile boolean closing; private boolean triggered; private int samples,threadSamples; private long lastThreads;
    private Map<String,String> previousStacks=Map.of(); private long firstHeap=-1,lastHeap=-1,minHeap=Long.MAX_VALUE,maxHeap;
    public DiagnosticRecorder(String pid,MonitoringConfig.Diagnostics config,MonitoringSession session) {
        this(pid,config,session,()->new TargetJvm(pid));
    }
    DiagnosticRecorder(String pid,MonitoringConfig.Diagnostics config,MonitoringSession session,Callable<TargetJvm> connector) {
        this.connector=connector;
        this.pid=pid;this.config=config;this.sessionId=session.sessionId();this.startedAt=session.startedAt().toString();directory=session.sessionDirectory().resolve("diagnostics");livePath=session.agentLogsDirectory().resolve("live-invocations.json");
    }
    public void start() { if(config.enabled()&&started.compareAndSet(false,true))worker.scheduleWithFixedDelay(this::tick,0,config.intervalMs(),TimeUnit.MILLISECONDS); }
    public Map<String,Object> latest() { return latest; }
    /** Standalone timed captures start their duration only after usable evidence exists. */
    public boolean awaitFirstSample(long timeoutMillis) {
        try { firstSample.get(timeoutMillis,TimeUnit.MILLISECONDS);return true; }
        catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();error("First diagnostic sample unavailable: "+failure.getClass().getSimpleName());return false;}
    }
    private void initialize() throws Exception {
        Files.createDirectories(directory);
        AtomicFiles.write(directory.resolve("README.md"),"# JVM diagnostic evidence\n\nmetrics.jsonl and threads.jsonl are timestamped host samples, not request CPU measurements.\n"
                +"manifest.json records errors, retention limits and recording coverage. profile.jfr is an optional final 120s/32MiB ring.\n"
                +"trigger.json contains the first condition with its preceding metric window. ThreadMXBean coverage may exclude virtual threads.\n"
                +"agent-logs/live-invocations.json retains in-flight roots at stop; rejectedTasks counts observed rejection handler invocations and throwing ForkJoin submissions.\n");
        target=connector.call();
        if(closing)return;
        Map<String,Object> environment=new LinkedHashMap<>(target.environment());environment.put("sessionId",sessionId);
        AtomicFiles.write(directory.resolve("environment.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(environment));
        if(!closing&&(config.jfr()||config.exceptionEvents()||config.trigger().enabled())) {
            try { profiler=new JfrProfiler(target,config.exceptionEvents());jfrStartedMillis=System.currentTimeMillis(); }
            catch(Exception e){error("JFR unavailable: "+e);}
        }
    }
    private synchronized void tick() {
        if(closing)return;
        try {
            if(target==null)initialize();
            if(closing)return;
            if(rolling&&(chunkSamples>=config.maxSamples()||chunkBytes>=127L*1024*1024)){chunk++;chunkSamples=0;chunkBytes=0;}
            if(!rolling&&(samples>=config.maxSamples()||byteLimitReached)){latest=Map.of("status","limit_reached","sampleLimit",config.maxSamples(),"byteLimitReached",byteLimitReached);return;}
            if(rolling&&profiler!=null&&System.currentTimeMillis()-jfrStartedMillis>55L*60*1000){profiler.dump(directory.resolve("profile-chunk-"+System.currentTimeMillis()+".jfr"));profiler.close();profiler=new JfrProfiler(target,config.exceptionEvents());jfrStartedMillis=System.currentTimeMillis();}
            Map<String,Object> sample=new LinkedHashMap<>(target.sample());
            if(closing)return;
            long timestamp=((Number)sample.get("timestampMillis")).longValue();
            long gap=lastSampleMillis==0?0:timestamp-lastSampleMillis; if(gap>2L*config.intervalMs())samplingGaps++;lastSampleMillis=timestamp;
            sample.put("sessionId",sessionId);sample.put("sampleGapMillis",gap);
            JsonNode live=readLive(); sample.put("inFlightSummary",Map.of("status",live.path("status").asText("unavailable"),"timestampMillis",live.path("timestampMillis").asLong(),"activeRoots",live.path("activeRoots").asLong(),"omittedRoots",live.path("omittedRoots").asLong(),"rejectedTasks",live.path("rejectedTasks").asLong()));
            JsonNode heap=JSON.valueToTree(sample.get("heap"));long used=heap.path("used").asLong();
            if(firstHeap<0)firstHeap=used;lastHeap=used;minHeap=Math.min(minHeap,used);maxHeap=Math.max(maxHeap,used);
            if (!append("metrics.jsonl",sample)) { manifest("LIMIT_REACHED");return; } samples++;chunkSamples++; recentMetrics.addLast(new LinkedHashMap<>(sample));while(recentMetrics.size()>120)recentMetrics.removeFirst();
            if(System.currentTimeMillis()-lastThreads>=config.threadIntervalMs()) {
                Map<String,Object> threads=new LinkedHashMap<>(target.threads());Map<String,String> now=new HashMap<>();List<Long> unchanged=new ArrayList<>();
                JsonNode entries=JSON.valueToTree(threads.get("threads"));
                for(JsonNode t:entries){String id=t.path("threadId").asText(),stack=t.path("state")+"\n"+t.path("stack");now.put(id,stack);if(stack.equals(previousStacks.get(id)))unchanged.add(t.path("threadId").asLong());}
                threads.put("unchangedStackThreadIds",unchanged);previousStacks=now;
                threads.put("sessionId",sessionId);if(append("threads.jsonl",threads))threadSamples++;AtomicFiles.write(directory.resolve("threads-latest.json"),JSON.writeValueAsString(threads));lastThreads=System.currentTimeMillis();sample.put("threadSnapshot",threads);lastThreadSnapshot=threads;
            }
            latest=Collections.unmodifiableMap(sample);var callback=onSample;if(callback!=null)callback.accept(latest); evaluate(sample,live);
            AtomicFiles.write(directory.resolve("latest.json"),JSON.writeValueAsString(sample));
            manifest("RUNNING");firstSample.complete(null);
        }catch(Exception e){firstSample.completeExceptionally(e);failedAttempts++;error(e.toString());try{manifest("DEGRADED");}catch(Exception ignored){}}
    }
    private JsonNode readLive() throws Exception {
        if(!Files.isRegularFile(livePath)||Files.size(livePath)>4L*1024*1024)return JSON.createObjectNode().put("status","unavailable");
        JsonNode node=JSON.readTree(livePath.toFile());return node==null?JSON.createObjectNode().put("status","unavailable"):node;
    }
    private void evaluate(Map<String,Object> sample,JsonNode live) throws Exception {
        if(!config.trigger().enabled()||triggered)return;var t=config.trigger();List<String> reasons=new ArrayList<>();
        Object cpu=sample.get("ProcessCpuLoad");if(cpu instanceof Number n&&n.doubleValue()>=t.cpuPercent()/100.0)reasons.add("process_cpu");
        JsonNode heap=JSON.valueToTree(sample.get("heap"));long max=heap.path("max").asLong();
        if(max>0&&heap.path("used").asDouble()/max>=t.heapPercent()/100.0)reasons.add("heap_usage");
        JsonNode snapshot=JSON.valueToTree(lastThreadSnapshot);if(snapshot.path("states").path("BLOCKED").asInt()>=t.blockedThreads())reasons.add("blocked_threads");
        for(JsonNode call:System.currentTimeMillis()-live.path("timestampMillis").asLong()<5000?live.path("roots"):JSON.createArrayNode())if(call.path("elapsedMillis").asLong()>=t.slowCallMs()){reasons.add("unfinished_slow_call");break;}
        if(live.path("rejectedTasks").asLong()>previousRejected)reasons.add("executor_rejection");previousRejected=live.path("rejectedTasks").asLong();
        for(String reason:new ArrayList<>(triggerStreaks.keySet()))if(!reasons.contains(reason))triggerStreaks.remove(reason);
        boolean sustained=false;for(String reason:reasons){int n=triggerStreaks.merge(reason,1,Integer::sum);sustained|=n>=t.consecutiveSamples()||reason.equals("executor_rejection");}
        if(!sustained)return;
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("sessionId",sessionId);evidence.put("timestampMillis",System.currentTimeMillis());evidence.put("reasons",reasons);
        evidence.put("precedingMetricSamples",List.copyOf(recentMetrics));evidence.put("inFlight",live);evidence.put("threadSnapshot",target.threads());
        AtomicFiles.write(directory.resolve("trigger.json"),JSON.writeValueAsString(evidence));triggered=true;
        if(profiler!=null){try{profiler.dump(directory.resolve("trigger.jfr"));}catch(Exception e){error("trigger JFR: "+e);}}
    }
    private long previousRejected;
    private boolean append(String name,Object value)throws Exception {byte[] bytes=(JSON.writeValueAsString(value)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long used=rolling?chunkBytes:writtenBytes;if(bytes.length>128L*1024*1024-used){if(!rolling){byteLimitReached=true;return false;}chunk++;chunkSamples=0;chunkBytes=0;}
        String filename=rolling&&chunk>1?name.replace(".jsonl",String.format("-%06d.jsonl",chunk)):name;
        Files.write(directory.resolve(filename),bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);writtenBytes+=bytes.length;chunkBytes+=bytes.length;return true;}
    public void checkpointToWindow(Path window){
        if(!rolling||!config.enabled()||!(config.jfr()||config.exceptionEvents()||config.trigger().enabled())||closing)return;
        var terminal=new java.util.concurrent.atomic.AtomicBoolean();
        if(checkpoints.size()>=8){try{writeCheckpoint(window,"SKIPPED_CAPACITY","At most eight pending checkpoints");}catch(Exception ignored){}return;}
        checkpoints.put(window,terminal);
        try{writeCheckpoint(window,"PENDING","");
            checkpointTimer.schedule(()->finishCheckpoint(window,terminal,"TIMED_OUT","Checkpoint exceeded 10s deadline"),10,TimeUnit.SECONDS);
            worker.execute(()->{
                if(terminal.get())return;
                Path staging=directory.resolve("checkpoint-tmp-"+UUID.randomUUID());
                try{
                    if(profiler==null){finishCheckpoint(window,terminal,"UNAVAILABLE","JFR recording unavailable");return;}
                    profiler.dump(staging.resolve("profile.jfr"));new JfrAnalysis().export(staging.resolve("profile.jfr"),staging);
                    synchronized(terminal){if(!terminal.get()){
                        Path output=window.resolve("diagnostics");Files.createDirectories(output);
                        for(String name:List.of("profile.jfr","jfr-analysis.json","flamegraph.html"))Files.move(staging.resolve(name),output.resolve(name),StandardCopyOption.REPLACE_EXISTING);
                        writeCheckpoint(window,"COMPLETED","");
                        terminal.set(true);checkpoints.remove(window,terminal);
                    }}
                }catch(Exception e){error("JFR checkpoint: "+e);finishCheckpoint(window,terminal,"FAILED",e.toString());}
                finally{try{if(Files.isDirectory(staging))try(var files=Files.walk(staging)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}catch(Exception ignored){}}
            });
        }catch(Exception failure){finishCheckpoint(window,terminal,"FAILED",failure.toString());}
    }
    private void writeCheckpoint(Path window,String status,String error)throws Exception {
        AtomicFiles.write(window.resolve("diagnostics/jfr-checkpoint.json"),JSON.writeValueAsString(Map.of("status",status,"timestampMillis",System.currentTimeMillis(),"sourceSessionId",sessionId,"error",error,"coverage","Bounded JFR tail at checkpoint, not complete window coverage")));
    }
    private void finishCheckpoint(Path window,java.util.concurrent.atomic.AtomicBoolean terminal,String status,String error){
        synchronized(terminal){if(terminal.compareAndSet(false,true))try{writeCheckpoint(window,status,error);}catch(Exception failure){com.agentmonitor.core.util.AppLog.warn("JFR checkpoint status write failed: "+failure);}finally{checkpoints.remove(window,terminal);}}
    }
    private void error(String message){message=message.substring(0,Math.min(4000,message.length()));if(errors.size()<100)errors.add(message);latest=Map.of("status","degraded","error",message);}
    private void manifest(String status)throws Exception {
        synchronized(manifestLock){if(finalizationTimedOut)status="DEGRADED";
        Map<String,Object> result=new LinkedHashMap<>();result.put("schemaVersion",1);result.put("sessionId",sessionId);result.put("startedAt",startedAt);result.put("status",status);result.put("timestampMillis",System.currentTimeMillis());
        result.put("writtenJsonlBytes",writtenBytes);result.put("maxJsonlBytes",128L*1024*1024);result.put("byteLimitReached",byteLimitReached);result.put("failedSampleAttempts",failedAttempts);result.put("samplingGaps",samplingGaps);result.put("intervalMillis",config.intervalMs());result.put("samples",samples);result.put("threadSnapshots",threadSamples);result.put("sampleLimitReached",!rolling&&samples>=config.maxSamples());result.put("rollingChunks",chunk);result.put("byteLimitScope",rolling?"per_chunk":"session");result.put("errors",List.copyOf(errors));
        result.put("finalizationTimedOut",finalizationTimedOut);result.put("triggered",triggered);result.put("jfrEnabled",profiler!=null);result.put("jfrWindowSeconds",120);result.put("jfrMaximumDurationSeconds",3600);
        result.put("heapTrend",Map.of("firstUsedBytes",firstHeap,"lastUsedBytes",lastHeap,"minUsedBytes",minHeap==Long.MAX_VALUE?-1:minHeap,"maxUsedBytes",maxHeap,
                "interpretation","Sampled heap occupancy is not retained/live heap. Growth alone does not prove a leak; inspect GCHeapSummary and allocations."));
        AtomicFiles.write(directory.resolve("manifest.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
    }
    public void attachSummary(Path report) {
        try {
            if (!Files.isRegularFile(report)) return;
            var document=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(report.toFile());
            if (!sessionId.equals(document.path("sessionId").asText())) return;
            document.set("diagnosticSummary",Files.isRegularFile(directory.resolve("manifest.json")) ? JSON.readTree(directory.resolve("manifest.json").toFile()) : JSON.createObjectNode().put("status",config.enabled()?"unavailable":"disabled"));
            Path requestMetrics=directory.getParent().resolve("agent-logs/request-metrics.json");
            if(Files.isRegularFile(requestMetrics)&&Files.size(requestMetrics)<4L*1024*1024){JsonNode metrics=JSON.readTree(requestMetrics.toFile());if(sessionId.equals(metrics.path("sessionId").asText()))document.set("requestMetrics",metrics);}
            AtomicFiles.write(report,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document));
        } catch (Exception failure) { com.agentmonitor.core.util.AppLog.warn("诊断摘要未能写入报告: "+failure.getMessage()); }
    }
    /** Diagnostic I/O must never hold bytecode restoration or CLI exit hostage. */
    @Override public void close() {
        if(!closeRequested.compareAndSet(false,true))return;
        closing=true;
        firstSample.completeExceptionally(new IllegalStateException("Diagnostic capture closed"));
        if(!config.enabled()){worker.shutdownNow();checkpointTimer.shutdownNow();return;}
        Future<?> finishing=worker.submit(this::finishCapture);
        worker.shutdown();
        try { finishing.get(5,TimeUnit.SECONDS);checkpointTimer.shutdownNow(); }
        catch(Exception failure){
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            finalizationTimedOut=true;
            checkpoints.forEach((window,terminal)->finishCheckpoint(window,terminal,"TIMED_OUT","Diagnostic finalization exceeded its budget"));checkpointTimer.shutdownNow();
            error("Diagnostic finalization did not complete within the 5s budget: "+failure.getClass().getSimpleName());
            try {
                synchronized(manifestLock){
                    Files.createDirectories(directory);
                    Path file=directory.resolve("manifest.json");
                    var terminal=Files.isRegularFile(file)?(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(file.toFile()):JSON.createObjectNode();
                    terminal.put("schemaVersion",1).put("sessionId",sessionId).put("startedAt",startedAt).put("timestampMillis",System.currentTimeMillis())
                            .put("status","DEGRADED").put("finalizationTimedOut",true);
                    terminal.set("errors",JSON.valueToTree(List.copyOf(errors)));
                    AtomicFiles.write(file,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(terminal));
                }
            } catch(Exception writeFailure){com.agentmonitor.core.util.AppLog.warn("诊断收尾超时记录失败: "+writeFailure.getMessage());}
            // Closing a remote connector can itself block, so it stays off the control path.
            Thread abort=new Thread(()->{TargetJvm connection=target;if(connection!=null)try{connection.close();}catch(Exception ignored){}},"diagnostic-jmx-abort");
            abort.setDaemon(true);abort.start();
        }
    }
    private void finishCapture(){
        try {
            if(profiler!=null){
                try{profiler.dump(directory.resolve("profile.jfr"));new JfrAnalysis().export(directory.resolve("profile.jfr"),directory);}
                catch(Exception e){error("final JFR: "+e);}finally{try{profiler.close();}catch(Exception e){error("close JFR: "+e);}}
            }
            manifest(errors.isEmpty()?(samples==0?"INSUFFICIENT":byteLimitReached||!rolling&&samples>=config.maxSamples()?"COMPLETED_WITH_LIMITS":"COMPLETED"):"DEGRADED");
        }catch(Exception e){error(e.toString());}
        finally{if(target!=null)try{target.close();}catch(Exception ignored){}}
    }
}
