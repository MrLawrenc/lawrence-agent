package com.agentmonitor.agent.interceptor;

import com.agentmonitor.model.span.SpanKind;
import java.net.URI;
import java.net.http.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional clients are inspected through public contracts, without adding client libraries to the Agent. */
public final class ClientSpanSupport {
    private ClientSpanSupport(){}
    public static boolean supported(String name){return name.equals("jdk.internal.net.http.HttpClientFacade")
            || name.startsWith("org.apache.http.") || name.startsWith("org.apache.hc.client5.")
            || name.startsWith("redis.clients.jedis.") || name.startsWith("com.rabbitmq.client.");}
    public static Context enter(String type,String operation,Object target,Object[] args){
        if(!MethodSpanAdvice.ACTIVE||ValueCapture.active())return null;
        long observerStarted=System.nanoTime();
        boolean http=type.equals("jdk.internal.net.http.HttpClientFacade")||type.startsWith("org.apache.");
        if(http&&!DependencySpanSupport.HTTP_ENABLED)return null;
        SpanContext parent=MethodSpanAdvice.SPAN_STACK.get().peek();if(parent==null)parent=AsyncSpanSupport.current();
        // Nested delegating client implementations represent one dependency operation.
        SpanKind kind=http?SpanKind.HTTP_CLIENT:type.startsWith("redis.")?SpanKind.REDIS:SpanKind.MESSAGING;
        if(parent!=null&&parent.kind==kind)return null;
        boolean async=operation.equals("sendAsync");
        InFlightSpans.Ticket ticket=InFlightSpans.enter(parent==null||async);if(ticket==null)return null;
        SpanContext span=null;
        try{
            Map<String,String> attrs=new LinkedHashMap<>();String method=operation;URI uri=null;
            if(http){
                Object request=null;for(Object arg:args)if(arg instanceof HttpRequest||call(arg,"getRequestLine")!=null||call(arg,"getRequestUri")!=null){request=arg;break;}
                if(request instanceof HttpRequest req){uri=req.uri();method=req.method();}
                else if(request!=null){Object line=call(request,"getRequestLine");method=string(call(line==null?request:line,"getMethod"),operation);String url=string(call(request,"getUri"),string(call(line,"getUri"),string(call(request,"getRequestUri"),"")));try{uri=URI.create(url);}catch(Exception ignored){}}
                attrs.put("http.request.method",method);attrs.put("agent.dependency.phase","http_client_api_completion");
                if(uri!=null){if(uri.getHost()!=null)attrs.put("server.address",uri.getHost());if(uri.getPort()>0)attrs.put("server.port",Integer.toString(uri.getPort()));attrs.put("url.scheme",Objects.toString(uri.getScheme(),""));attrs.put("url.path",Objects.toString(uri.getRawPath(),""));}
                attrs.put("agent.http.body_completion",type.startsWith("jdk.")?"body_handler_dependent":"not_observed");
            }else if(kind==SpanKind.REDIS){
                attrs.put("db.system.name","redis");attrs.put("agent.dependency.phase","command_round_trip");
                Object command=args.length==0?null:args[0];Object argument=call(command,"getArguments");Object verb=call(argument==null?command:argument,"getCommand");if(verb==null&&command instanceof Enum<?>)verb=command;
                method=verb instanceof Enum<?> e?e.name():operation;attrs.put("db.operation.name",method);
                Object endpoint=call(target,"getHostAndPort");Object host=call(endpoint,"getHost"),port=call(endpoint,"getPort");if(host!=null)attrs.put("server.address",host.toString());if(port!=null)attrs.put("server.port",port.toString());
            }else{
                attrs.put("messaging.system","rabbitmq");attrs.put("messaging.operation.name","publish");attrs.put("agent.dependency.phase","client_publish_dispatch");attrs.put("agent.messaging.confirmation","not_observed");
                Object connection=call(target,"getConnection"),address=call(connection,"getAddress"),port=call(connection,"getPort");if(address instanceof java.net.InetAddress host)attrs.put("server.address",host.getHostAddress());if(port!=null)attrs.put("server.port",port.toString());
                // No payload, routing key, queue contents or message headers are read.
            }
            TraceSamplingState sampling=parent==null?MethodSpanAdvice.newTraceSamplingState():async?new TraceSamplingState(parent.traceSampling.isHeadSampled(),Long.MAX_VALUE,
                    MethodSpanAdvice.TAIL_MAX_BUFFERED_SPANS,MethodSpanAdvice.TAIL_MAX_BUFFERED_BYTES,com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy.DROP,parent.traceSampling.headRatePercent()):parent.traceSampling;
            if(async&&parent!=null){sampling.traceState(parent.traceSampling.traceState());sampling.remoteNotSampled(!parent.traceSampling.isHeadSampled());attrs.put("agent.async.parent.trace_id",parent.traceId);attrs.put("agent.async.parent.span_id",parent.spanId);attrs.put("agent.local.detached","true");}
            span=new SpanContext(parent==null?MethodSpanAdvice.nextTraceId():parent.traceId,MethodSpanAdvice.nextId(),parent==null?"":parent.spanId,
                    http?"HTTP Client":kind==SpanKind.REDIS?"Redis":"RabbitMQ",method,Thread.currentThread().getName(),parent==null||async?0:parent.depth+1,
                    System.nanoTime(),System.currentTimeMillis(),"",kind,attrs,sampling,ticket);
            Runnable restore=http?inject(span,args,attrs):()->{};
            MethodSpanAdvice.SPAN_STACK.get().push(span);MethodSpanAdvice.recordEntryOverhead(span,observerStarted);return new Context(span,attrs,restore);
        }catch(Throwable ignored){ticket.close();if(span!=null)MethodSpanAdvice.removeContext(span);return null;}
    }
    private static Runnable inject(SpanContext span,Object[] args,Map<String,String> attrs){
        attrs.put("agent.propagation.status","unavailable");
        for(int i=0;i<args.length;i++){
            if(args[i] instanceof HttpRequest request){
                // Leave caller-owned tracing intact. Conflicting ownership is visible in the exported span.
                if(request.headers().firstValue("traceparent").isPresent()){callerOwned(attrs,request.headers().allValues("traceparent").size()==1?request.headers().firstValue("traceparent").orElse(""):"invalid");return ()->{};}
                HttpRequest.Builder copy=HttpRequest.newBuilder(request,(k,v)->!k.equalsIgnoreCase("tracestate"));
                copy.header("traceparent",DistributedContext.header(span));
                if(!span.traceSampling.traceState().isBlank())copy.header("tracestate",span.traceSampling.traceState());args[i]=copy.build();attrs.put("agent.propagation.status","injected");return ()->{};
            }
            Object request=args[i];if(call(request,"getRequestLine")==null&&call(request,"getRequestUri")==null)continue;
            Object existing=call(request,"getFirstHeader","traceparent");
            if(existing!=null){callerOwned(attrs,string(call(existing,"getValue"),""));return ()->{};}
            // Keep Configurable / cancellation interfaces and request identity intact. Apache classic
            // requests are mutable and not safe for concurrent reuse; remove only our own values at exit.
            String owner=DistributedContext.header(span),state=span.traceSampling.traceState();
            boolean addState=!state.isBlank()&&call(request,"getFirstHeader","tracestate")==null;
            call(request,"setHeader","traceparent",owner);
            Object ownedParent=call(request,"getFirstHeader","traceparent");
            if(!owner.equals(string(call(ownedParent,"getValue"),"")))return ()->{};
            if(addState)call(request,"setHeader","tracestate",state);
            Object ownedState=addState?call(request,"getFirstHeader","tracestate"):null;
            attrs.put("agent.propagation.status","injected");
            return ()->{call(request,"removeHeader",ownedParent);if(ownedState!=null)call(request,"removeHeader",ownedState);};
        }
        return ()->{};
    }
    private static void callerOwned(Map<String,String> attrs,String header){
        attrs.put("agent.propagation.status","caller_owned");DistributedContext carrier=DistributedContext.parse(header,"");
        if(carrier!=null){attrs.put("agent.propagation.carrier.trace_id",carrier.traceId());attrs.put("agent.propagation.carrier.parent_id",carrier.parentId());}
    }
    public static void exit(Context context,Object result,Throwable error){
        if(context==null)return;long observerStarted=System.nanoTime();MethodSpanAdvice.removeContext(context.span);try{context.restore.run();}catch(Throwable ignored){}
        if(error==null&&result instanceof CompletionStage<?> stage){
            MethodSpanAdvice.recordExitOverhead(context.span,observerStarted);
            try{stage.whenComplete((response,failure)->finish(context,response,failure));return;}catch(Throwable ignored){}
        }
        finish(context,result,error);
    }
    private static void finish(Context context,Object response,Throwable error){
        long observerStarted=System.nanoTime();
        if(!context.completed.compareAndSet(false,true))return;
        Map<String,String> attrs=new LinkedHashMap<>(context.attributes);int status=-1;
        if(context.span.threadId!=Thread.currentThread().getId())attrs.put("agent.async.completion_thread.id",Long.toString(Thread.currentThread().getId()));
        if(response instanceof HttpResponse<?> http)status=http.statusCode();
        else {Object line=call(response,"getStatusLine");try{status=Integer.parseInt(string(call(line==null?response:line,line==null?"getCode":"getStatusCode"),"-1"));}catch(Exception ignored){}}
        if(status>0)attrs.put("http.response.status_code",Integer.toString(status));
        // HTTP client 4xx and 5xx indicate unsuccessful dependency responses.
        if(error==null&&status>=400){attrs.put("error.type",Integer.toString(status));error=new IllegalStateException("HTTP response status "+status);}
        try{MethodSpanAdvice.complete(context.span,MethodSpanAdvice.completedSpan(context.span,"",MethodSpanAdvice.durationNanos(context.span,observerStarted),error,"",attrs));}
        finally{MethodSpanAdvice.recordExitOverhead(context.span,observerStarted);if(context.span.invocation!=null)context.span.invocation.close();}
    }
    private static Object call(Object target,String name,Object... arguments){if(target==null)return null;try{for(Method m:target.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==arguments.length){try{return m.invoke(target,arguments);}catch(IllegalArgumentException ignored){}}}catch(Exception ignored){}return null;}
    private static String string(Object value,String fallback){return value==null?fallback:value.toString();}
    public static final class Context {final SpanContext span;final Map<String,String> attributes;final Runnable restore;final AtomicBoolean completed=new AtomicBoolean();Context(SpanContext span,Map<String,String> attributes,Runnable restore){this.span=span;this.attributes=Map.copyOf(attributes);this.restore=restore;}}
}
