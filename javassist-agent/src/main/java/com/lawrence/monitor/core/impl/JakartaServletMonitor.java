package com.lawrence.monitor.core.impl;

import com.lawrence.monitor.AgentConfig;
import com.lawrence.monitor.StatisticsType;
import com.lawrence.monitor.core.AbstractMonitor;
import com.lawrence.monitor.core.MethodInfo;
import com.lawrence.monitor.stack.StackNode;
import com.lawrence.monitor.statistics.ServletStatistics;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.util.Collector;
import com.lawrence.monitor.util.StatisticsHelper;
import com.lawrence.monitor.util.ThreadLocalUtil;
import com.lawrence.monitor.write.Writeable;
import com.lawrence.monitor.write.WriterResp;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;
import jakarta.servlet.ReadListener;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static com.lawrence.monitor.util.Collector.buildStack;
import static com.lawrence.monitor.util.Collector.print;

/**
 * adapt servlet 5.0(tomcat 10)
 */
public class JakartaServletMonitor extends AbstractMonitor {
    private static final Logger logger = LoggerFactory.getLogger(JakartaServletMonitor.class);
    private static final String TARGET_CLZ = "jakarta.servlet.http.HttpServlet";
    public static JakartaServletMonitor INSTANCE;

    @Override
    public void init(AgentConfig agentConfig) {
        JakartaServletMonitor.INSTANCE = this;
    }


    @Override
    public boolean isTarget(String className) {
        return TARGET_CLZ.equals(className.replace("/", "."));

    }

    @Override
    public CtMethod targetMethod(ClassPool pool, CtClass clz) throws NotFoundException {
        return clz.getDeclaredMethod("service", new CtClass[]{pool
                .get("jakarta.servlet.http.HttpServletRequest"), pool.get("jakarta.servlet.http.HttpServletResponse")});
    }

    @Override
    public MethodInfo getMethodInfo(String methodName) {
        return MethodInfo.newBuilder().createVoidBody(this, methodName);
    }

    @Override
    public StatisticsType type() {
        return StatisticsType.SERVLET3;
    }

    @Override
    public Statistics begin(Object obj, Object... args) {
        ThreadLocalUtil.globalThreadLocal.set(StackNode.createParentNode());

        ServletStatistics statistics = StatisticsHelper.createStatistics(ServletStatistics.class);
        HttpServletRequest servletRequest = (HttpServletRequest) args[0];
        StringBuffer url = servletRequest.getRequestURL();
        statistics.setUrl(url.toString());
        statistics.setArgs(args);
        statistics.setStartTime(System.currentTimeMillis());

        statistics.setMethod(servletRequest.getMethod());

        // if (Objects.nonNull(servletRequest.getContentType()) && servletRequest.getContentType().contains("json")) {
        //读了req之后就没法读取了 ,所以将req包装为可重复读取的httpRequest
        MultiReadHttpServletRequest wrapperRequest = new MultiReadHttpServletRequest(servletRequest);
        args[0] = wrapperRequest;


        try {
            BufferedReader reader = wrapperRequest.getReader();
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            statistics.setBodyData(sb.toString());
        } catch (IOException e) {
            logger.error("read body data error", e);
        }
        //url参数
        Enumeration<String> parameterNames = wrapperRequest.getParameterNames();
        Map<String, String> urlParam = new HashMap<>();
        while (parameterNames.hasMoreElements()) {
            String element = parameterNames.nextElement();
            urlParam.put(element, wrapperRequest.getParameter(element));
        }
        statistics.setUrlData(urlParam.toString());
        return statistics;
    }

    @Override
    public void exception(Statistics statistics, Throwable t) {
        statistics.setT(t);
    }

    @Override
    public Object end(Statistics current, Object obj) {
        current.setEndTime(System.currentTimeMillis());
        ServletStatistics servletStatistics = (ServletStatistics) current;
        HttpServletResponse servletResponse = (HttpServletResponse) servletStatistics.getArgs()[1];
        servletStatistics.setRespStatus(servletResponse.getStatus());
        logger.info("monitor data:{}", servletStatistics);
        Collector.RESULT.forEach((outerKey, outerValue) -> {
            StackNode.Node head = buildStack(outerValue.getNodeList());
            print(head);
        });
        return obj;
    }

    @Override
    public WriterResp write(Writeable statistics) {
        return null;
    }

    /**
     * 包装req对象，使得req可以重复读取.
     * <p>RequestWrapper
     * 当在使用地获取input stream时 实际获取的是包装之后的input stream{@link CachedServletInputStream}
     */
    public static class MultiReadHttpServletRequest extends HttpServletRequestWrapper {
        private final ByteArrayOutputStream cachedBytes;

        public MultiReadHttpServletRequest(HttpServletRequest request) {
            super(request);
            cachedBytes = new ByteArrayOutputStream();

            //复制 req 流到内存中,切记不能关inputStream
            try {
                ServletInputStream inputStream = request.getInputStream();
                byte[] data = new byte[1024];

                int start = 0;
                while (inputStream.read(data) > 0) {
                    cachedBytes.write(data, start, data.length);
                    start += data.length;
                }
            } catch (IOException e) {
                logger.error("copy wrapper request fail!", e);
            }

        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CachedServletInputStream(cachedBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream()));
        }
    }

    public static class CachedServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        public CachedServletInputStream(ByteArrayOutputStream cachedBytes) {
            // create a new input stream from the cached request body
            byte[] bytes = cachedBytes.toByteArray();
            input = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        public boolean isFinished() {
            return false;
        }

        public boolean isReady() {
            return true;
        }

        public void setReadListener(ReadListener readListener) {
        }
    }
}