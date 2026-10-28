package com.agentmonitor.agent.interceptor;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent ingress counters: completed requests are recorded even when their trace is discarded. */
public final class RequestMetrics {
    private static final int ROUTES=256;
    private static final long[] BOUNDS=new long[200];
    static { long next=100_000;for(int i=0;i<BOUNDS.length-1;i++){BOUNDS[i]=next;next=(long)Math.ceil(next*1.1);}BOUNDS[BOUNDS.length-1]=Long.MAX_VALUE; }
    private static volatile Session current=new Session("",false);
    private static ScheduledExecutorService worker;
    private RequestMetrics() { }
    public static synchronized void start(String directory,String id,boolean enabled) {
        if(worker!=null)worker.shutdownNow(); current=new Session(id,enabled);
        if(directory==null||directory.isBlank())return;
        Session owner=current;Path file=Path.of(directory).resolve("request-metrics.json");owner.file=file;
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"agent-request-metrics");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(()->write(owner,false),500,1000,TimeUnit.MILLISECONDS);
    }
    public static Ticket begin(){Session s=current;synchronized(s){if(!s.enabled||s.stopped)return null;s.started++;s.active++;return new Ticket(s);}}
    public static void finish(Ticket ticket,String method,String route,int status,boolean failure) {
        if(ticket==null||!ticket.done.compareAndSet(false,true))return;
        Session s=ticket.owner;synchronized(s){if(s.stopped)return;s.active--;s.completed++;
            String key=bounded(method,24)+" "+(route==null||route.isBlank()?"<unmapped>":bounded(route,256));
            if(!s.rows.containsKey(key)&&s.rows.size()>=ROUTES){s.overflow++;key="<other>";}
            Stats row=s.rows.computeIfAbsent(key,k->new Stats());long nanos=Math.max(0,System.nanoTime()-ticket.started);
            row.count++;if(failure||status>=500)row.errors++;if(status<100||status>599)row.unknownStatus++;
            int bucket=Arrays.binarySearch(BOUNDS,nanos);if(bucket<0)bucket=-bucket-1;row.buckets[bucket]++;
            row.total+=nanos;row.max=Math.max(row.max,nanos);
        }
    }
    public static synchronized void stop(){if(worker!=null){worker.shutdownNow();worker=null;}Session s=current;synchronized(s){if(s.stopped)return;s.stopped=true;s.ended=System.currentTimeMillis();}write(s,true);}
    public static Map<String,Object> snapshot(){return document(current,false);}
    private static void write(Session s,boolean terminal){synchronized(s.io){synchronized(s){if(s.stopped&&!terminal)return;}if(s.file==null)return;try{AgentDiagnostics.write(s.file,document(s,terminal));}catch(Exception ignored){}}}
    private static Map<String,Object> document(Session s,boolean terminal){synchronized(s){
        long elapsed=Math.max(1,(s.stopped?s.ended:System.currentTimeMillis())-s.epoch);List<Map<String,Object>> rows=new ArrayList<>();
        s.rows.forEach((key,v)->{Map<String,Object> row=new LinkedHashMap<>();row.put("endpoint",key);row.put("completed",v.count);row.put("errors",v.errors);row.put("unknownStatus",v.unknownStatus);
            row.put("errorRate",v.unknownStatus>0?null:v.count==0?0:(double)v.errors/v.count);row.put("observedErrorFraction",v.count==0?0:(double)v.errors/v.count);row.put("completedPerSecond",v.count*1000.0/elapsed);
            row.put("p95UpperBoundMillis",percentile(v,.95));row.put("p99UpperBoundMillis",percentile(v,.99));row.put("maxMillis",v.max/1_000_000.0);row.put("totalNanos",v.total);row.put("latencyBucketCounts",v.buckets.clone());rows.add(row);});
        Map<String,Object> doc=new LinkedHashMap<>();doc.put("schemaVersion",1);doc.put("sessionId",s.id);doc.put("timestampMillis",System.currentTimeMillis());doc.put("startedAtMillis",s.epoch);doc.put("elapsedMillis",elapsed);
        doc.put("status",s.stopped?"FINAL":"RUNNING");doc.put("enabled",s.enabled);doc.put("startedRequests",s.started);doc.put("completedRequests",s.completed);doc.put("pendingRequests",s.active);doc.put("overflowRouteRequests",s.overflow);doc.put("endpoints",rows);
        doc.put("latencyBucketUpperBoundsNanos",BOUNDS.clone());doc.put("samplingIndependent",true);doc.put("captureComplete",s.enabled&&s.stopped&&s.active==0);
        doc.put("coverage","Instrumented Servlet requests starting during this capture only; NOT all host/service traffic. Async requests finish via AsyncListener where available. Unresolved routes are grouped as <unmapped>; no raw URL labels.");
        doc.put("percentileMethod","Histogram upper bound, approximately 10% bucket width above 0.1ms; last bucket is open-ended. Unknown status and pending requests must be checked separately.");return doc;
    }}
    private static double percentile(Stats s,double quantile){long rank=(long)Math.ceil(s.count*quantile),seen=0;for(int i=0;i<BOUNDS.length;i++){seen+=s.buckets[i];if(seen>=rank)return BOUNDS[i]==Long.MAX_VALUE?Double.MAX_VALUE:BOUNDS[i]/1_000_000.0;}return 0;}
    private static String bounded(String text,int max){return text==null?"UNKNOWN":text.substring(0,Math.min(max,text.length()));}
    private static final class Stats {long count,errors,unknownStatus,total,max;final long[] buckets=new long[BOUNDS.length];}
    private static final class Session {final Object io=new Object();final String id;final boolean enabled;final long epoch=System.currentTimeMillis();final Map<String,Stats> rows=new LinkedHashMap<>();long started,completed,active,overflow,ended;boolean stopped;Path file;Session(String id,boolean enabled){this.id=id;this.enabled=enabled;}}
    public static final class Ticket {final Session owner;final long started=System.nanoTime();final AtomicBoolean done=new AtomicBoolean();Ticket(Session owner){this.owner=owner;}}
}
