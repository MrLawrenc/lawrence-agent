package com.lawrence.monitor.core.impl;

import com.lawrence.monitor.AgentConfig;
import com.lawrence.monitor.StatisticsType;
import com.lawrence.monitor.core.AbstractMonitor;
import com.lawrence.monitor.core.MethodInfo;
import com.lawrence.monitor.statistics.ServletStatistics;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.util.List;

/**
 * Servlet 监控基类，封装 javax.servlet 和 jakarta.servlet 的公共逻辑。
 * 子类只需提供包名 / 类名 / 统计类型，并实现 begin() 中的 request 包装。
 */
public abstract class AbstractServletMonitor extends AbstractMonitor {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    /** e.g. "javax.servlet.http.HttpServlet" / "jakarta.servlet.http.HttpServlet" */
    protected abstract String targetClassName();

    /** e.g. "javax.servlet" / "jakarta.servlet" */
    protected abstract String servletPkg();

    @Override
    public abstract StatisticsType type();

    @Override
    protected void doInit(AgentConfig agentConfig) {}

    @Override
    protected Class<? extends Statistics> statisticsClass() {
        return ServletStatistics.class;
    }

    @Override
    public boolean isTarget(String className) {
        return targetClassName().equals(className.replace("/", "."));
    }

    @Override
    public List<CtMethod> targetMethods(ClassPool pool, CtClass clz) throws NotFoundException {
        String reqClass  = servletPkg() + ".http.HttpServletRequest";
        String respClass = servletPkg() + ".http.HttpServletResponse";
        return List.of(clz.getDeclaredMethod("service",
                new CtClass[]{pool.get(reqClass), pool.get(respClass)}));
    }

    @Override
    public MethodInfo getMethodInfo(String methodName) {
        return MethodInfo.newBuilder().createVoidBody(this, methodName);
    }

    @Override
    public void exception(Statistics statistics, Throwable t) {
        statistics.setError(t);
        trace.markError();
    }

    @Override
    protected SpanNode endSpan(Statistics statistics) {
        return trace.endSpan();
    }
}
