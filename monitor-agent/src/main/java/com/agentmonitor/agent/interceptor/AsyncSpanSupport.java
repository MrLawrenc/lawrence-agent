package com.agentmonitor.agent.interceptor;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import com.agentmonitor.model.span.SpanKind;

/** Weak identity task correlation. Does not wrap tasks or change executor/cancellation semantics. */
public final class AsyncSpanSupport {
    private record Submission(String trace,String span,long submittedNanos){}
    private static final ReferenceQueue<Object> QUEUE=new ReferenceQueue<>();
    private static final Map<Key,Submission> TASKS=new HashMap<>();
    private static final AtomicLong DROPPED=new AtomicLong(),LINKED=new AtomicLong();
    private static final Map<Thread,SpanContext> EXECUTING=Collections.synchronizedMap(new WeakHashMap<>());
    public static SpanContext current(){return EXECUTING.get(Thread.currentThread());}
    private static final class Key extends WeakReference<Object>{
        private final int hash;
        Key(Object task){super(task,QUEUE);hash=System.identityHashCode(task);}
        public int hashCode(){return hash;}
        public boolean equals(Object o){return this==o || o instanceof Key k && get()!=null && get()==k.get();}
    }
    private static synchronized void expunge(){for(Reference<?> ref;(ref=QUEUE.poll())!=null;)TASKS.remove(ref);}
    public static void submit(Object executor,Object task){
        if(!AgentDiagnostics.ENABLED||!MethodSpanAdvice.ACTIVE||task==null||ValueCapture.active())return;
        SpanContext parent=MethodSpanAdvice.SPAN_STACK.get().peek();if(parent==null)parent=current();if(parent==null)return;AgentDiagnostics.pool(executor);
        synchronized(AsyncSpanSupport.class){
        Key key=new Key(task);if(TASKS.containsKey(key))return;
        if(TASKS.size()>=8192){DROPPED.incrementAndGet();return;}TASKS.put(key,new Submission(parent.traceId,parent.spanId,System.nanoTime()));
        }
    }
    public static SpanContext begin(Object task){
        if(!AgentDiagnostics.ENABLED||!MethodSpanAdvice.ACTIVE||ValueCapture.active()||current()!=null||!MethodSpanAdvice.SPAN_STACK.get().isEmpty())return null;
        Submission submission;synchronized(AsyncSpanSupport.class){expunge();submission=TASKS.remove(new Key(task));}
        if(submission==null)return null;
        InFlightSpans.Ticket ticket=InFlightSpans.enter(true);if(ticket==null)return null;
        long now=System.nanoTime();SpanContext span=new SpanContext(MethodSpanAdvice.nextTraceId(),MethodSpanAdvice.nextId(),"",task.getClass().getName(),"asyncTask",
                Thread.currentThread().getName(),0,now,System.currentTimeMillis(),"",SpanKind.BUSINESS,
                Map.of("agent.async.parent.trace_id",submission.trace,"agent.async.parent.span_id",submission.span,"agent.async.queue_nanos",Long.toString(Math.max(0,now-submission.submittedNanos))),
                MethodSpanAdvice.newTraceSamplingState(),ticket);
        EXECUTING.put(Thread.currentThread(),span);LINKED.incrementAndGet();return span;
    }
    public static void finish(SpanContext span,Throwable error){
        if(span==null)return;EXECUTING.remove(Thread.currentThread());MethodSpanAdvice.removeContext(span);
        MethodSpanAdvice.complete(span,MethodSpanAdvice.completedSpan(span,"",MethodSpanAdvice.durationNanos(span,System.nanoTime()),error,"",span.attributes));
    }
    public static void finishCurrent(Throwable error){finish(current(),error);}
    public static void failed(Object executor,Object task,Throwable error){if(error instanceof RejectedExecutionException){if(!(executor instanceof java.util.concurrent.ThreadPoolExecutor))AgentDiagnostics.rejected();synchronized(AsyncSpanSupport.class){TASKS.remove(new Key(task));}}}
    public static synchronized Map<String,Object> statistics(){expunge();return Map.of("pendingTaskLinks",TASKS.size(),"linkedTasks",LINKED.get(),"droppedTaskLinks",DROPPED.get());}
    public static synchronized void clear(){TASKS.clear();synchronized(EXECUTING){for(SpanContext span:EXECUTING.values())if(span.invocation!=null)span.invocation.close();EXECUTING.clear();}}
}
