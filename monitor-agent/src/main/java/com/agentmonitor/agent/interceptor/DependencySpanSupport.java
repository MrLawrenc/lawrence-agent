package com.agentmonitor.agent.interceptor;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import com.agentmonitor.model.span.SpanAttribute;
import com.agentmonitor.model.span.SpanKind;

/** Builds standard Servlet server spans without linking the Agent to either Servlet API namespace. */
public final class DependencySpanSupport {

    public static volatile boolean HTTP_ENABLED = true;

    private DependencySpanSupport() { }

    public static Context enter(String className, String methodName, Object target, Object[] arguments) {
        if (!MethodSpanAdvice.ACTIVE) return null;
        SpanKind kind = kindOf(arguments);
        if (kind == null || !enabled(kind)) return null;
        SpanContext parent = MethodSpanAdvice.SPAN_STACK.get().peek();
        // Servlet filter chains call doFilter recursively for every filter.  The outer
        // invocation is the request root; nested framework dispatch must reuse it rather
        // than creating a row of synthetic HTTP-server spans.
        if (kind == SpanKind.HTTP_SERVER && parent != null) return null;

        long startNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        String traceId = parent == null ? "trace-" + MethodSpanAdvice.nextId() : parent.traceId;
        String spanId = MethodSpanAdvice.nextId();
        TraceSamplingState traceSampling = parent == null
                ? MethodSpanAdvice.newTraceSamplingState()
                : parent.traceSampling;
        Map<String, String> attributes = attributes(kind, className, methodName, target, arguments);
        SpanContext span = new SpanContext(traceId, spanId, parent == null ? "" : parent.spanId,
                dependencyClass(kind), dependencyMethod(kind, methodName, attributes), Thread.currentThread().getName(),
                parent == null ? 0 : parent.depth + 1, startNanos, startedAt, "", kind, attributes,
                traceSampling);
        MethodSpanAdvice.SPAN_STACK.get().push(span);
        return new Context(span, new LinkedHashMap<>(attributes));
    }

    public static void exit(Context context, Object target, Object[] arguments, Object result, Throwable error) {
        if (context == null) return;
        MethodSpanAdvice.removeContext(context.span());
        completeAttributes(context.attributes(), context.span().kind, target, arguments, result);
        long duration = Math.max(0, System.nanoTime() - context.span().startNanos);
        boolean statusFailure = error == null && isHttpServerFailure(context.attributes());
        String responseStatus = context.attributes().getOrDefault(SpanAttribute.HTTP_RESPONSE_STATUS_CODE,
                context.attributes().getOrDefault(SpanAttribute.STATUS_CODE, "unknown"));
        if (statusFailure) context.attributes().put(SpanAttribute.ERROR_TYPE, responseStatus);
        Throwable effectiveError = statusFailure ? new HttpStatusException(responseStatus) : error;
        MethodSpanAdvice.complete(context.span(), MethodSpanAdvice.completedSpan(context.span(), "", duration,
                effectiveError, "", context.attributes()));
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
        if (MethodSpanAdvice.CAPTURE_ARGUMENTS) {
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
    private static final class HttpStatusException extends RuntimeException {
        private HttpStatusException(String status) { super("HTTP response status " + status); }
    }
    public record Context(SpanContext span, Map<String, String> attributes) { }
}
