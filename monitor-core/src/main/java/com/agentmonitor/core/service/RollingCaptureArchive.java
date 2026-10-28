package com.agentmonitor.core.service;

import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.report.analysis.PerformanceReportCollector;
import com.agentmonitor.core.util.AtomicFiles;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.report.*;
import com.agentmonitor.model.trace.TraceNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** One attached Agent, independent rolling evidence windows. Window quality never fabricates a drain ACK. */
public final class RollingCaptureArchive implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final MonitoringSession master;
    private final MonitoringConfig config;
    private final long windowMillis,maxBytes;
    private final List<Map<String,Object>> windows=new ArrayList<>();
    private final List<String> errors=new ArrayList<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"rolling-capture-archive");t.setDaemon(true);return t;});
    private MonitoringSession current;
    private PerformanceReportCollector report;
    private java.io.BufferedWriter spanWriter;
    private long windowStarted,roots,crossWindowRoots,samples,threads,omittedTraceRecords,lastBudgetCheck;
    private volatile boolean budgetReached;
    private boolean closed,started,rotationPaused;
    private java.util.function.Consumer<Path> onWindowClosed;
    public void setOnWindowClosed(java.util.function.Consumer<Path> handler){onWindowClosed=handler;}
    public RollingCaptureArchive(MonitoringSession master,MonitoringConfig config,long windowMillis,long maxBytes) throws Exception {
        if(windowMillis<500||windowMillis>3_600_000||maxBytes<1024*1024)throw new IllegalArgumentException("Rolling window: 500ms–60m, budget >=1MiB");
        this.master=master;this.config=config;this.windowMillis=windowMillis;this.maxBytes=maxBytes;
    }
    public synchronized void start()throws Exception {
        if(started||closed)return;started=true;openWindow();manifest("RUNNING");
        timer.scheduleWithFixedDelay(this::tick,200,200,TimeUnit.MILLISECONDS);
    }
    public boolean budgetReached(){return budgetReached;}
    /** Keep accepting final roots/samples, but leave the last window for final master JFR. */
    public synchronized void pauseRotation(){rotationPaused=true;timer.shutdownNow();}
    public Path manifestPath(){return master.sessionDirectory().resolve("rolling-capture.json");}
    private synchronized void tick(){
        if(closed||rotationPaused)return;
        try{
            long now=System.currentTimeMillis();
            if(now-windowStarted>=windowMillis){finishWindow();openWindow();manifest("RUNNING");}
            if(now-lastBudgetCheck>=2000){lastBudgetCheck=now;long bytes=0;
                try(var paths=Files.walk(master.sessionDirectory())){for(var iterator=paths.iterator();iterator.hasNext();){Path p=iterator.next();if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))bytes+=Files.size(p);if(bytes>=maxBytes)break;}}
                if(bytes>=maxBytes||Files.getFileStore(master.sessionDirectory()).getUsableSpace()<128L*1024*1024){budgetReached=true;manifest("BUDGET_REACHED");}
            }
        }catch(Exception e){if(errors.size()<100)errors.add(e.toString());budgetReached=true;try{manifest("FAILED");}catch(Exception ignored){}}
    }
    private void openWindow() throws Exception {
        if(windows.size()>=10000){budgetReached=true;return;}
        current=MonitoringSession.create(master.sessionDirectory().resolve("windows").toString());
        AtomicFiles.write(current.sessionDirectory().resolve("config.yml"),MonitoringConfigYaml.write(config));
        var a=config.analysis();report=new PerformanceReportCollector(new AnalysisConfig(a.enabled(),a.slowTraceThresholdMs(),a.slowSpanThresholdMs(),a.slowSelfTimeThresholdMs(),a.includeErrorTrace(),a.maxTracesPerRootMethod(),a.maxBottlenecksPerTrace(),a.maxDepth()),current);
        windowStarted=System.currentTimeMillis();roots=crossWindowRoots=samples=threads=omittedTraceRecords=0;
    }
    public synchronized void accept(String traceId,TraceNode root){
        if(closed)return;
        try{
            if(!started)start();if(current==null)return;
            roots++;report.accept(traceId,root);
            long start=Long.parseLong(root.getAttributes().getOrDefault("agent.span.started_at_epoch_millis","0"));
            if(start>0&&start<windowStarted)crossWindowRoots++;
            List<Map<String,Object>> nodes=new ArrayList<>();ArrayDeque<TraceNode> pending=new ArrayDeque<>();pending.add(root);
            while(!pending.isEmpty()){TraceNode n=pending.removeFirst();Map<String,Object> row=new LinkedHashMap<>();row.put("sessionId",current.sessionId());row.put("traceId",traceId);row.put("method",n.getFullClassName()+"#"+n.getMethodName2()+n.getSignature()+" ["+n.getKind().name()+"]");row.put("threadName",n.getThread());row.put("durationNanos",n.getDurationNanos());row.put("attributes",n.getAttributes());row.put("startedAtEpochMillis",Long.parseLong(n.getAttributes().getOrDefault("agent.span.started_at_epoch_millis","0")));row.put("error",n.isError());nodes.add(row);pending.addAll(n.getChildren());}
            if(spanWriter==null)spanWriter=Files.newBufferedWriter(current.spansDirectory().resolve("rolling-spans.jsonl"),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            for(var row:nodes){String line=JSON.writeValueAsString(row);if(line.length()>1_048_576){omittedTraceRecords++;continue;}spanWriter.write(line);spanWriter.newLine();}spanWriter.flush();
        }catch(Exception e){if(errors.size()<100)errors.add(e.toString());budgetReached=true;}
    }
    public synchronized void sample(Map<String,Object> sample){
        if(closed||current==null)return;
        try{
            Files.createDirectories(current.sessionDirectory().resolve("diagnostics"));
            var copy=new LinkedHashMap<>(sample);copy.put("sessionId",current.sessionId());copy.put("sourceSessionId",master.sessionId());
            Object snapshot=copy.remove("threadSnapshot");append("metrics.jsonl",copy);AtomicFiles.write(current.sessionDirectory().resolve("diagnostics/latest.json"),JSON.writeValueAsString(copy));samples++;
            if(snapshot instanceof Map<?,?> raw){Map<String,Object> thread=new LinkedHashMap<>();raw.forEach((k,v)->thread.put(k.toString(),v));thread.put("sessionId",current.sessionId());thread.put("sourceSessionId",master.sessionId());append("threads.jsonl",thread);AtomicFiles.write(current.sessionDirectory().resolve("diagnostics/threads-latest.json"),JSON.writeValueAsString(thread));threads++;}
        }catch(Exception e){if(errors.size()<100)errors.add(e.toString());budgetReached=true;}
    }
    private void append(String name,Object value)throws Exception {Files.writeString(current.sessionDirectory().resolve("diagnostics").resolve(name),JSON.writeValueAsString(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private void finishWindow() throws Exception {
        if(current==null)return;
        if(spanWriter!=null){spanWriter.close();spanWriter=null;}
        Map<String,Object> diagnostic=new LinkedHashMap<>();diagnostic.put("schemaVersion",1);diagnostic.put("sessionId",current.sessionId());diagnostic.put("sourceSessionId",master.sessionId());diagnostic.put("status",errors.isEmpty()?(samples==0?"INSUFFICIENT":"COMPLETED"):"DEGRADED");diagnostic.put("samples",samples);diagnostic.put("threadSnapshots",threads);diagnostic.put("errors",List.copyOf(errors));diagnostic.put("intervalMillis",config.diagnostics().intervalMs());
        if(config.diagnostics().enabled())AtomicFiles.write(current.sessionDirectory().resolve("diagnostics/manifest.json"),JSON.writeValueAsString(diagnostic));
        if(Files.isRegularFile(master.resourcePath()))Files.copy(master.resourcePath(),current.resourcePath(),StandardCopyOption.REPLACE_EXISTING);
        report.finish(ReportStatus.INCOMPLETE,"Rolling window contains observed completed roots; exporter ACK is session-wide and is not fabricated per window.",CaptureQuality.unavailable());
        var metadata=new LinkedHashMap<String,Object>();metadata.put("sequence",windows.size()+1);metadata.put("sessionDirectory",current.sessionDirectory().toString());metadata.put("sessionId",current.sessionId());metadata.put("sourceSessionId",master.sessionId());metadata.put("startedAtMillis",windowStarted);metadata.put("endedAtMillis",System.currentTimeMillis());metadata.put("roots",roots);metadata.put("crossWindowRoots",crossWindowRoots);metadata.put("omittedTraceRecords",omittedTraceRecords);metadata.put("partialEvidence",true);windows.add(metadata);
        boolean checkpoint=config.diagnostics().enabled()&&(config.diagnostics().jfr()||config.diagnostics().exceptionEvents()||config.diagnostics().trigger().enabled());
        metadata.put("checkpointRequired",checkpoint);
        if(closed&&checkpoint){
            Path output=current.sessionDirectory().resolve("diagnostics");Files.createDirectories(output);boolean available=Files.isRegularFile(master.sessionDirectory().resolve("diagnostics/profile.jfr"))&&Files.isRegularFile(master.sessionDirectory().resolve("diagnostics/jfr-analysis.json"));
            if(available)for(String name:List.of("profile.jfr","jfr-analysis.json","flamegraph.html")){Path source=master.sessionDirectory().resolve("diagnostics").resolve(name);if(Files.isRegularFile(source))Files.copy(source,output.resolve(name),StandardCopyOption.REPLACE_EXISTING);}
            AtomicFiles.write(output.resolve("jfr-checkpoint.json"),JSON.writeValueAsString(Map.of("status",available?"COMPLETED":"UNAVAILABLE","sourceSessionId",master.sessionId(),"coverage","Final master JFR bounded tail; missing finalization is explicitly unavailable")));
        }
        AtomicFiles.write(current.sessionDirectory().resolve("window.json"),JSON.writeValueAsString(metadata));if(!closed&&onWindowClosed!=null)onWindowClosed.accept(current.sessionDirectory());current=null;
    }
    private void manifest(String status)throws Exception {AtomicFiles.write(manifestPath(),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("schemaVersion",1,"sessionId",master.sessionId(),"status",status,"singleAttach",true,"windowMillis",windowMillis,"maxBytes",maxBytes,"budgetReached",budgetReached,"windows",List.copyOf(windows),"errors",List.copyOf(errors),"boundary","Roots belong to the window in which the complete tree was received; cross-window roots remain intact. Per-window exporter completeness is unknown; consult the master final ACK.")));}
    @Override public synchronized void close(){if(closed)return;closed=true;timer.shutdownNow();try{finishWindow();manifest(errors.isEmpty()?(budgetReached?"BUDGET_REACHED":"COMPLETED"):"DEGRADED");}catch(Exception e){if(errors.size()<100)errors.add(e.toString());throw new IllegalStateException("Rolling archive finalization failed",e);}}
}
