package com.agentmonitor.agent.interceptor;

import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** JDK-only bounded snapshots, never performs file I/O on application threads. */
public final class AgentDiagnostics {
    public static volatile boolean ENABLED=true;
    private static final Map<SpanContext,WeakReference<Thread>> ROOTS=new ConcurrentHashMap<>();
    private static final List<WeakReference<ThreadPoolExecutor>> POOLS=new ArrayList<>();
    private static final AtomicLong REJECTED=new AtomicLong(),OMITTED=new AtomicLong();
    private static String sessionId="";
    private static volatile boolean stopping;
    private static ScheduledExecutorService worker; private static Path directory;
    public static void enter(SpanContext context) {
        if(!ENABLED||context.invocation==null||context.depth!=0)return;
        if(ROOTS.size()>=4096){OMITTED.incrementAndGet();return;}
        ROOTS.put(context,new WeakReference<>(Thread.currentThread()));
    }
    public static void exit(SpanContext context){ROOTS.remove(context);}
    public static void rejected(){REJECTED.incrementAndGet();}
    public static synchronized void pool(Object executor){
        if(!(executor instanceof ThreadPoolExecutor pool))return;
        POOLS.removeIf(r->r.get()==null);
        if(POOLS.size()<128 && POOLS.stream().noneMatch(r->r.get()==pool))POOLS.add(new WeakReference<>(pool));
    }
    public static synchronized void start(String path,String captureSessionId,Instrumentation inst){
        if(path==null||path.isBlank())return;directory=Path.of(path);sessionId=captureSessionId;stopping=false;ROOTS.clear();POOLS.clear();REJECTED.set(0);OMITTED.set(0);
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"agent-diagnostic-snapshot");t.setDaemon(true);return t;});
        worker.execute(()->inventory(inst));worker.scheduleWithFixedDelay(()->{snapshot(false);try{if(Files.deleteIfExists(directory.resolve("class-origin-refresh.request")))inventory(inst);}catch(Exception ignored){}},500,500,TimeUnit.MILLISECONDS);
    }
    public static synchronized void stop(){stopping=true;if(worker!=null){worker.shutdownNow();worker=null;}snapshot(true);ROOTS.clear();POOLS.clear();AsyncSpanSupport.clear();}
    private static synchronized void snapshot(boolean terminal){
        if(stopping&&!terminal)return;
        try{
            List<Map<String,Object>> roots=new ArrayList<>();long now=System.nanoTime();
            for(var e:ROOTS.entrySet()){
                if(roots.size()>=128)break;SpanContext c=e.getKey();Thread t=e.getValue().get();List<String> stack=new ArrayList<>();
                if(t!=null){StackTraceElement[] frames=t.getStackTrace();for(int i=0;i<Math.min(32,frames.length);i++)stack.add(frames[i].toString());}
                roots.add(Map.of("traceId",c.traceId,"spanId",c.spanId,"method",c.className+"#"+c.methodName,"threadName",c.threadName,
                        "threadId",t==null?-1:t.getId(),"state",t==null?"unavailable":t.getState().name(),"startedAtEpochMillis",c.startedAtEpochMillis,
                        "elapsedMillis",Math.max(0,now-c.startNanos)/1_000_000,"stack",stack));
            }
            List<Map<String,Object>> pools=new ArrayList<>();
            synchronized(AgentDiagnostics.class){for(var ref:POOLS){var p=ref.get();if(p!=null)pools.add(Map.of("identity",Integer.toHexString(System.identityHashCode(p)),"class",p.getClass().getName(),
                    "active",p.getActiveCount(),"size",p.getPoolSize(),"core",p.getCorePoolSize(),"max",p.getMaximumPoolSize(),"queued",p.getQueue().size(),"completed",p.getCompletedTaskCount()));}}
            if(directory!=null){Map<String,Object> document=new LinkedHashMap<>(Map.of("schemaVersion",1,"status",stopping?"STOP_BOUNDARY":"RUNNING","timestampMillis",System.currentTimeMillis(),"roots",roots,"activeRoots",ROOTS.size(),
                    "omittedRoots",Math.max(0,ROOTS.size()-roots.size()),"threadPools",pools,"rejectedTasks",REJECTED.get(),"async",AsyncSpanSupport.statistics(),
                    "coverage","Instrumented roots and observed executors; rejectedTasks counts ThreadPoolExecutor rejection-handler invocations (including caller-runs/discard), and throwing ForkJoin submissions."));document.put("sessionId",sessionId);document.put("trackingCapacityDroppedRoots",OMITTED.get());write(directory.resolve("live-invocations.json"),document);}
        }catch(Throwable ignored){}
    }
    private static void inventory(Instrumentation inst){
        try{
            List<Map<String,Object>> entries=new ArrayList<>();int omitted=0;
            for(Class<?> type:inst.getAllLoadedClasses()){
                if(type.isArray()||type.isPrimitive())continue;if(entries.size()>=50000){omitted++;continue;}
                String source="";try{var domain=type.getProtectionDomain();if(domain!=null&&domain.getCodeSource()!=null)source=String.valueOf(domain.getCodeSource().getLocation());}catch(Throwable ignored){}
                ClassLoader loader=type.getClassLoader();String loaderId=loader==null?"bootstrap":loader.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(loader));
                entries.add(Map.of("className",type.getName(),"loader",loaderId,"source",source,"module",Objects.toString(type.getModule().getName(),"unnamed"),"modifiable",inst.isModifiableClass(type)));
            }
            write(directory.resolve("class-origins.json"),Map.of("schemaVersion",1,"sessionId",sessionId,"timestampMillis",System.currentTimeMillis(),"classes",entries,"omittedClasses",omitted,"coverage","Attach-time loaded classes; duplicate class names under different loaders are preserved."));
        }catch(Throwable ignored){}
    }
    public static void writeBudget(String path,String id,Map<String,Object> budget){
        if(path==null||path.isBlank())return;
        try{Map<String,Object> document=new LinkedHashMap<>(budget);document.put("sessionId",id);document.put("timestampMillis",System.currentTimeMillis());document.put("status","FINAL");write(Path.of(path).resolve("overhead.json"),document);}catch(Exception ignored){}
    }
    static void write(Path path,Object value)throws Exception{
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"diagnostic-",".tmp");
        try{Files.writeString(temp,json(value));try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
    private static String json(Object value){
        if(value==null)return "null";if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value.getClass().isArray()){StringJoiner join=new StringJoiner(",","[","]");for(int i=0;i<java.lang.reflect.Array.getLength(value);i++)join.add(json(java.lang.reflect.Array.get(value,i)));return join.toString();}
        if(value instanceof Map<?,?> map){StringJoiner join=new StringJoiner(",","{","}");map.forEach((k,v)->join.add(json(k.toString())+":"+json(v)));return join.toString();}
        if(value instanceof Iterable<?> list){StringJoiner join=new StringJoiner(",","[","]");for(Object v:list)join.add(json(v));return join.toString();}
        StringBuilder s=new StringBuilder("\"");for(char c:value.toString().toCharArray()){switch(c){case '"'->s.append("\\\"");case '\\'->s.append("\\\\");case '\n'->s.append("\\n");case '\r'->s.append("\\r");case '\t'->s.append("\\t");default->{if(c<32)s.append(String.format("\\u%04x",(int)c));else s.append(c);}}}return s.append('"').toString();
    }
}
