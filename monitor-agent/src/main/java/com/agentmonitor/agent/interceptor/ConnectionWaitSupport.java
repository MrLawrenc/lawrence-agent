package com.agentmonitor.agent.interceptor;
import java.util.*;
import com.agentmonitor.model.span.SpanKind;
/** Measures DataSource.getConnection separately from query execution. */
public final class ConnectionWaitSupport {
    public static SpanContext enter(String className){
        if(!MethodSpanAdvice.ACTIVE||!JdbcSpanSupport.ENABLED||ValueCapture.active())return null;
        SpanContext parent=MethodSpanAdvice.SPAN_STACK.get().peek();if(parent==null)parent=AsyncSpanSupport.current();if(parent==null||parent.attributes.containsKey("agent.pool.connection_wait_nanos"))return null;
        InFlightSpans.Ticket ticket=InFlightSpans.enter(false);if(ticket==null)return null;
        SpanContext span=new SpanContext(parent.traceId,MethodSpanAdvice.nextId(),parent.spanId,className,"getConnection",Thread.currentThread().getName(),parent.depth+1,
                System.nanoTime(),System.currentTimeMillis(),"",SpanKind.BUSINESS,Map.of("agent.pool.connection_wait_nanos","pending"),parent.traceSampling,ticket);
        MethodSpanAdvice.SPAN_STACK.get().push(span);return span;
    }
    public static void exit(SpanContext span,Throwable error){
        long duration=Math.max(0,System.nanoTime()-span.startNanos);MethodSpanAdvice.removeContext(span);
        MethodSpanAdvice.complete(span,MethodSpanAdvice.completedSpan(span,"",duration,error,"",Map.of("agent.pool.connection_wait_nanos",Long.toString(duration))));
    }
}
