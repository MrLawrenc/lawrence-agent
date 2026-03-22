package com.lawrence.monitor.core.impl;

import com.lawrence.monitor.StatisticsType;
import com.lawrence.monitor.statistics.ServletStatistics;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * javax.servlet (Tomcat 9 / Servlet 4) 监控器。
 * 公共逻辑见 {@link AbstractServletMonitor}。
 */
public class ServletMonitor extends AbstractServletMonitor {

    @Override
    protected String targetClassName() {
        return "javax.servlet.http.HttpServlet";
    }

    @Override
    protected String servletPkg() {
        return "javax.servlet";
    }

    @Override
    public StatisticsType type() {
        return StatisticsType.SERVLET;
    }

    @Override
    public Statistics begin(Object obj, Object... args) {
        HttpServletRequest request = (HttpServletRequest) args[0];
        SpanNode span = trace.beginSpan(targetClassName(), "service");
        ServletStatistics statistics = new ServletStatistics(span.getTraceId(), span.getSpanId());
        statistics.setSpan(span);
        statistics.setClassName(targetClassName());
        statistics.setMethodName("service");
        statistics.setUrl(request.getRequestURL().toString());
        statistics.setMethod(request.getMethod());
        statistics.setStartTime(System.currentTimeMillis());

        MultiReadHttpServletRequest wrapped = new MultiReadHttpServletRequest(request);
        args[0] = wrapped;
        statistics.setArgs(args);

        try {
            BufferedReader reader = wrapped.getReader();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            statistics.setBodyData(sb.toString());
        } catch (IOException e) {
            logger.error("read body data error", e);
        }

        Enumeration<String> names = wrapped.getParameterNames();
        Map<String, String> urlParam = new HashMap<>();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            urlParam.put(name, wrapped.getParameter(name));
        }
        statistics.setUrlData(urlParam.toString());
        return statistics;
    }

    @Override
    protected Object doEnd(Statistics statistics, Object result) {
        HttpServletResponse response = (HttpServletResponse) ((ServletStatistics) statistics).getArgs()[1];
        ((ServletStatistics) statistics).setRespStatus(response.getStatus());
        return null;
    }

    public static class MultiReadHttpServletRequest extends HttpServletRequestWrapper {
        private final ByteArrayOutputStream cachedBytes = new ByteArrayOutputStream();

        public MultiReadHttpServletRequest(HttpServletRequest request) {
            super(request);
            try {
                ServletInputStream in = request.getInputStream();
                byte[] buf = new byte[1024];
                int read;
                while ((read = in.read(buf)) > 0) cachedBytes.write(buf, 0, read);
            } catch (IOException e) {
                // ignore
            }
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(cachedBytes);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream()));
        }
    }

    public static class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        public CachedServletInputStream(ByteArrayOutputStream cachedBytes) {
            input = new ByteArrayInputStream(cachedBytes.toByteArray());
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }
    }
}