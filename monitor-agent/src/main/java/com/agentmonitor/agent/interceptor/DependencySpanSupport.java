package com.agentmonitor.agent.interceptor;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import com.agentmonitor.model.span.SpanAttribute;
import com.agentmonitor.model.span.SpanKind;
import com.agentmonitor.agent.protocol.SpanPayloadLimits;

/** Builds standard Servlet server spans without linking the Agent to either Servlet API namespace. */
public final class DependencySpanSupport {

    public static volatile boolean HTTP_ENABLED = true;

    private DependencySpanSupport() { }

    public static Context enter(String className, String methodName, Object target, Object[] arguments) {
        if (!MethodSpanAdvice.ACTIVE || ValueCapture.active()) return null;
        long observerStarted = System.nanoTime();
        SpanKind kind = kindOf(arguments);
        if (kind == null || !enabled(kind)) return null;
        SpanContext parent = MethodSpanAdvice.SPAN_STACK.get().peek();
        if (parent == null) parent = AsyncSpanSupport.current();
        // Servlet filter chains call doFilter recursively for every filter.  The outer
        // invocation is the request root; nested framework dispatch must reuse it rather
        // than creating a row of synthetic HTTP-server spans.
        if (kind == SpanKind.HTTP_SERVER && parent != null) return null;
        Object request=servletRequest(arguments);
        if(Boolean.TRUE.equals(invoke(request,"getAttribute","com.agentmonitor.request.started")))return null;

        InFlightSpans.Ticket invocation = InFlightSpans.enter(parent == null);
        if (invocation == null) return null;
        try {
            DistributedContext remote=parent==null?DistributedContext.parse(header(request,"traceparent"),header(request,"tracestate")):null;
            String traceId = parent == null ? remote==null?MethodSpanAdvice.nextTraceId():remote.traceId() : parent.traceId;
            String spanId = MethodSpanAdvice.nextId();
            TraceSamplingState traceSampling = parent == null
                    ? remote==null?MethodSpanAdvice.newTraceSamplingState():new TraceSamplingState((remote.flags()&1)!=0,Long.MAX_VALUE,
                            MethodSpanAdvice.TAIL_MAX_BUFFERED_SPANS,MethodSpanAdvice.TAIL_MAX_BUFFERED_BYTES,
                            com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy.DROP,(remote.flags()&1)!=0?100:0)
                    : parent.traceSampling;
            if(remote!=null){traceSampling.traceState(remote.traceState());traceSampling.remoteNotSampled((remote.flags()&1)==0);}
            Map<String, String> attributes = new LinkedHashMap<>(SpanPayloadLimits.attributes(ValueCapture.suppress(() -> attributes(kind, className, methodName, target, arguments))));
            if(remote!=null)attributes.put("agent.remote_parent","true");
            SpanContext span = new SpanContext(traceId, spanId, parent == null ? remote==null?"":remote.parentId() : parent.spanId,
                    dependencyClass(kind), dependencyMethod(kind, methodName, attributes), Thread.currentThread().getName(),
                    parent == null ? 0 : parent.depth + 1, System.nanoTime(), System.currentTimeMillis(), "", kind, attributes,
                    traceSampling, invocation);
            MethodSpanAdvice.SPAN_STACK.get().push(span);
            MethodSpanAdvice.recordEntryOverhead(span, observerStarted);
            RequestMetrics.Ticket metric=RequestMetrics.begin();
            try{request.getClass().getMethod("setAttribute",String.class,Object.class).invoke(request,"com.agentmonitor.request.started",Boolean.TRUE);}catch(Exception ignored){}
            return new Context(span, new LinkedHashMap<>(attributes),metric,request);
        } catch (Throwable error) {
            invocation.close();
            throw error;
        }
    }

    public static void exit(Context context, Object target, Object[] arguments, Object result, Throwable error) {
        if (context == null) return;
        long observerStarted = System.nanoTime();
        long duration = MethodSpanAdvice.durationNanos(context.span(), observerStarted);
        MethodSpanAdvice.removeContext(context.span());
        try {
            ValueCapture.suppress(() -> {
                completeAttributes(context.attributes(), context.span().kind, target, arguments, result);
                return null;
            });
            boolean statusFailure = error == null && isHttpServerFailure(context.attributes());
            context.finishMetrics(arguments,error);
            String responseStatus = context.attributes().getOrDefault(SpanAttribute.HTTP_RESPONSE_STATUS_CODE,
                    context.attributes().getOrDefault(SpanAttribute.STATUS_CODE, "unknown"));
            if (statusFailure) context.attributes().put(SpanAttribute.ERROR_TYPE, responseStatus);
            Throwable effectiveError = statusFailure ? new HttpStatusException(responseStatus) : error;
            MethodSpanAdvice.complete(context.span(), MethodSpanAdvice.completedSpan(context.span(), "", duration,
                    effectiveError, "", context.attributes()));
        } finally {
            MethodSpanAdvice.recordExitOverhead(context.span(), observerStarted);
            if (context.span().invocation != null) context.span().invocation.close();
        }
    }

    private static boolean enabled(SpanKind kind) {
        return switch (kind) {
            case HTTP_SERVER -> HTTP_ENABLED;
            default -> false;
        };
    }

    private static SpanKind kindOf(Object[] arguments) {
        for (Object argument : safeArguments(arguments)) {
            if (isServletRequest(argument)) return SpanKind.HTTP_SERVER;
        }
        return null;
    }

    /** Used by the installation diagnostics; this does not enable a disabled dependency type. */
    public static boolean isSupportedClassName(String className) {
        return className != null && className.toLowerCase(java.util.Locale.ROOT).contains("servlet");
    }

    private static Map<String, String> attributes(SpanKind kind, String className, String methodName,
                                                   Object target, Object[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (kind) {
            case HTTP_SERVER -> httpAttributes(arguments, values);
            default -> { }
        }
        return values;
    }

    private static void httpAttributes(Object[] arguments, Map<String, String> values) {
        values.put(SpanAttribute.DEPENDENCY_TYPE, "HTTP_SERVER");
        Object request = servletRequest(arguments);
        if (request == null) return;

        String method = string(invoke(request, "getMethod"));
        if (!method.isBlank()) {
            values.put(SpanAttribute.HTTP_METHOD, method);
            values.put(SpanAttribute.HTTP_REQUEST_METHOD, method);
        }
        String uri = string(invoke(request, "getRequestURI"));
        if (uri.isBlank()) uri = string(invoke(request, "url"));
        if (uri.isBlank()) uri = string(invoke(request, "getURI"));
        if (!uri.isBlank()) {
            values.put(SpanAttribute.ROUTE_TEMPLATE, uri);
            values.put(SpanAttribute.URL_PATH, uri);
        }
        String scheme = string(invoke(request, "getScheme"));
        if (!scheme.isBlank()) values.put(SpanAttribute.URL_SCHEME, scheme);
        String serverAddress = string(invoke(request, "getServerName"));
        if (!serverAddress.isBlank()) {
            values.put(SpanAttribute.SERVER_ADDRESS, serverAddress);
            values.put(SpanAttribute.REMOTE_SERVICE, serverAddress);
        }
        String serverPort = string(invoke(request, "getServerPort"));
        if (!serverPort.isBlank()) values.put(SpanAttribute.SERVER_PORT, serverPort);
        String protocol = string(invoke(request, "getProtocol"));
        if (protocol.startsWith("HTTP/")) protocol = protocol.substring("HTTP/".length());
        if (!protocol.isBlank()) values.put(SpanAttribute.NETWORK_PROTOCOL_VERSION, protocol);
        if(MethodSpanAdvice.CAPTURE_ARGUMENTS&&!OverheadController.detailEnabled())values.put(SpanAttribute.CAPTURE_TRUNCATED,"httpParameters_adaptive");
        if (MethodSpanAdvice.CAPTURE_ARGUMENTS && OverheadController.detailEnabled()) {
            // Do not call getParameter*, getInputStream, or getReader here.  Servlet containers
            // can parse form parameters from the body lazily, and doing so would consume a
            // one-shot request stream before application code can read it.  The query string is
            // already request metadata and preserves repeated/encoded parameters unchanged.
            String query = string(invoke(request, "getQueryString"));
            if (!query.isBlank()) {
                values.put(SpanAttribute.HTTP_PARAMETERS, query);
                values.put(SpanAttribute.URL_QUERY, query);
            }
        }
    }

    private static void completeAttributes(Map<String, String> values, SpanKind kind, Object target,
                                           Object[] arguments, Object result) {
        if (kind == SpanKind.HTTP_SERVER) {
            String status = string(invoke(result, "getStatusCode"));
            if (status.isBlank()) status = string(invoke(result, "status"));
            for (Object argument : safeArguments(arguments)) {
                if (status.isBlank()) status = string(invoke(argument, "getStatus"));
            }
            if (!status.isBlank()) {
                values.put(SpanAttribute.STATUS_CODE, status);
                values.put(SpanAttribute.HTTP_RESPONSE_STATUS_CODE, status);
            }
            String route = routeTemplate(servletRequest(arguments));
            if (!route.isBlank()) values.put(SpanAttribute.HTTP_ROUTE, route);
        }
    }

    private static boolean isHttpServerFailure(Map<String, String> attributes) {
        try {
            return Integer.parseInt(attributes.getOrDefault(SpanAttribute.HTTP_RESPONSE_STATUS_CODE, "")) >= 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String dependencyClass(SpanKind kind) {
        return switch (kind) {
            case HTTP_SERVER -> "HTTP Server";
            default -> "Dependency";
        };
    }

    private static String dependencyMethod(SpanKind kind, String method, Map<String, String> attributes) {
        return switch (kind) {
            case HTTP_SERVER -> httpDisplayName(method, attributes);
            default -> method == null || method.isBlank() ? "CALL" : method;
        };
    }

    private static String httpDisplayName(String method, Map<String, String> attributes) {
        String httpMethod = attributes.get(SpanAttribute.HTTP_METHOD);
        String route = attributes.get(SpanAttribute.ROUTE_TEMPLATE);
        if (httpMethod != null && !httpMethod.isBlank()) return route == null || route.isBlank()
                ? httpMethod : httpMethod + " " + route;
        return method == null || method.isBlank() ? "REQUEST" : method;
    }

    private static Object[] safeArguments(Object[] arguments) { return arguments == null ? new Object[0] : arguments; }
    private static Object servletRequest(Object[] arguments) {
        for (Object argument : safeArguments(arguments)) {
            if (isServletRequest(argument)) return argument;
        }
        return null;
    }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String routeTemplate(Object request) {
        String spring=string(invoke(request,"getAttribute","org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"));
        if(!spring.isBlank())return spring;
        Object mapping = invoke(request, "getHttpServletMapping");
        String pattern = string(invoke(mapping, "getPattern"));
        if (!pattern.isBlank()) return pattern;
        return string(invoke(request, "getAttribute",
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern"));
    }
    private static boolean isServletRequest(Object value) {
        return hasType(value == null ? null : value.getClass(), "jakarta.servlet.ServletRequest")
                || hasType(value == null ? null : value.getClass(), "javax.servlet.ServletRequest");
    }
    private static boolean hasType(Class<?> type, String expectedName) {
        if (type == null) return false;
        if (expectedName.equals(type.getName())) return true;
        for (Class<?> contract : type.getInterfaces()) {
            if (hasType(contract, expectedName)) return true;
        }
        return hasType(type.getSuperclass(), expectedName);
    }
    private static Object invoke(Object target, String name) { if (target == null) return null; try { Method method = target.getClass().getMethod(name); method.setAccessible(true); return method.invoke(target); } catch (Exception ignored) { return null; } }
    private static Object invoke(Object target, String name, String argument) { if (target == null) return null; try { Method method = target.getClass().getMethod(name, String.class); method.setAccessible(true); return method.invoke(target, argument); } catch (Exception ignored) { return null; } }
    private static String header(Object request,String name){
        Object all=invoke(request,"getHeaders",name);
        if(all instanceof java.util.Enumeration<?> values){StringJoiner joined=new StringJoiner(",");int count=0,size=0;
            while(values.hasMoreElements()){String value=string(values.nextElement());if(++count>32||(size+=value.length())>512)return "invalid";
                if(name.equals("traceparent")&&count>1)return "invalid";joined.add(value);}
            return joined.toString();
        }
        return string(invoke(request,"getHeader",name));
    }
    private static final class HttpStatusException extends RuntimeException {
        private HttpStatusException(String status) { super("HTTP response status " + status); }
    }
    public record Context(SpanContext span, Map<String, String> attributes, RequestMetrics.Ticket metric,Object request) {
        public Context(SpanContext span,Map<String,String> attributes){this(span,attributes,null,null);}
        private void finishMetrics(Object[] arguments,Throwable error){
            Object response=null;for(Object argument:safeArguments(arguments))if(invoke(argument,"getStatus")!=null){response=argument;break;}
            final Object finalResponse=response;
            if(Boolean.TRUE.equals(invoke(request,"isAsyncStarted"))){
                attributes.put("agent.http.duration_scope","servlet_dispatch_only_async");
                try{
                    Object async=invoke(request,"getAsyncContext");
                    Class<?> listener=Class.forName(hasType(request.getClass(),"jakarta.servlet.ServletRequest")?"jakarta.servlet.AsyncListener":"javax.servlet.AsyncListener",false,request.getClass().getClassLoader());
                    Object[] proxy=new Object[1];java.util.concurrent.atomic.AtomicBoolean failed=new java.util.concurrent.atomic.AtomicBoolean(error!=null);
                    proxy[0]=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(p,m,a)->{
                        try{switch(m.getName()){
                            case "onError","onTimeout"->failed.set(true);
                            case "onComplete"->finishMetric(finalResponse,failed.get());
                            case "onStartAsync"->{Object next=invoke(a[0],"getAsyncContext");next.getClass().getMethod("addListener",listener).invoke(next,proxy[0]);}
                            case "toString"->{return "AgentMonitorAsyncMetrics";}
                            case "hashCode"->{return System.identityHashCode(p);}
                            case "equals"->{return p==a[0];}
                            default->{}
                        }}catch(Throwable unavailable){/* Observer callbacks must not change Servlet lifecycle. */}return null;
                    });
                    async.getClass().getMethod("addListener",listener).invoke(async,proxy[0]);return;
                }catch(Exception unavailable){attributes.put("agent.http.async_metrics","listener_unavailable_pending");return;}
            }
            finishMetric(response,error!=null);
        }
        private void finishMetric(Object response,boolean failed){
            int status=-1;try{status=Integer.parseInt(string(invoke(response,"getStatus")));}catch(Exception ignored){}
            RequestMetrics.finish(metric,attributes.get(SpanAttribute.HTTP_REQUEST_METHOD),routeTemplate(request),status,failed);
        }
    }
}
