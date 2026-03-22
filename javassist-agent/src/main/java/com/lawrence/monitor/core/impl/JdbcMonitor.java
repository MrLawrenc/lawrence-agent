package com.lawrence.monitor.core.impl;

import com.lawrence.monitor.AgentConfig;
import com.lawrence.monitor.StatisticsType;
import com.lawrence.monitor.collect.StatisticsCollector;
import com.lawrence.monitor.core.AbstractMonitor;
import com.lawrence.monitor.core.MethodInfo;
import com.lawrence.monitor.statistics.JdbcStatistics;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.trace.SpanNode;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author : MrLawrenc
 * date  2020/7/4 0:16
 * <p>
 * JDBC监控器实现
 */
public class JdbcMonitor extends AbstractMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcMonitor.class);

    private static final String[] PROXY_CONNECTION_METHOD = new String[]{"prepareStatement"};
    private static final String[] STATEMENT_METHOD = new String[]{"executeUpdate", "execute", "executeQuery", "getResultSet"};

    /** 运行时从 JdbcConfig 加载，支持同时监控多个驱动 */
    private List<String> targetDriverClasses = Arrays.asList("com.mysql.cj.jdbc.NonRegisteringDriver");

    @Override
    protected void doInit(AgentConfig agentConfig) {
        if (agentConfig.getJdbcConfig() != null
                && agentConfig.getJdbcConfig().getDriverClasses() != null
                && !agentConfig.getJdbcConfig().getDriverClasses().isEmpty()) {
            targetDriverClasses = agentConfig.getJdbcConfig().getDriverClasses();
        }
        LOGGER.info("JdbcMonitor targeting drivers: {}", targetDriverClasses);
    }

    @Override
    protected Class<? extends Statistics> statisticsClass() {
        return JdbcStatistics.class;
    }

    @Override
    public StatisticsType type() {
        return StatisticsType.JDBC;
    }

    /**
     * 生成connection代理对象
     *
     * @param connection 源对象
     * @return 代理对象
     */
    public Connection proxyConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(JdbcMonitor.class.getClassLoader(),
                new Class[]{Connection.class}, (proxy, method, args) -> {
                    JdbcStatistics statistics = null;
                    if (Arrays.asList(PROXY_CONNECTION_METHOD).contains(method.getName())) {
                        SpanNode span = trace.beginSpan(connection.getClass().getName(), method.getName());
                        statistics = new JdbcStatistics(span.getTraceId(), span.getSpanId());
                        statistics.setSpan(span);
                        statistics.setStartTime(System.currentTimeMillis());
                    }
                    Object result = method.invoke(connection, args);
                    if (Arrays.asList(PROXY_CONNECTION_METHOD).contains(method.getName())
                            && result instanceof PreparedStatement) {
                        if (Objects.nonNull(statistics)) {
                            statistics.setUrl(connection.getMetaData().getURL());
                            statistics.setSql(args[0].toString());
                        }
                        result = proxyStatement((PreparedStatement) result, statistics);
                    }
                    return result;
                });
    }

    /**
     * 生成PreparedStatement代理对象
     *
     * @param statement connection对象执行prepareStatement方法返回结果
     * @return 代理connection#prepareStatement()结果对象
     */
    private PreparedStatement proxyStatement(PreparedStatement statement, JdbcStatistics statistics) {
        return (PreparedStatement) Proxy.newProxyInstance(JdbcMonitor.class.getClassLoader(),
                new Class[]{PreparedStatement.class}, (proxy, method, args) -> {
                    Object result = method.invoke(statement, args);
                    if (Arrays.asList(STATEMENT_METHOD).contains(method.getName())) {
                        statistics.finish(System.currentTimeMillis());
                        SpanNode span = trace.endSpan();
                        if (result instanceof ResultSet) {
                            ResultSet resultSet = (ResultSet) result;
                            statistics.setResultSet(resultSet);
                            try {
                                resultSet.last();
                                statistics.setCount(resultSet.getRow());
                                resultSet.beforeFirst();
                            } catch (Exception ignored) {
                                // forward-only ResultSet 不支持 last()/beforeFirst()，忽略行数统计
                            }

                        } else if (result instanceof Long) {
                            statistics.setCount((Long) result);
                        } else if (result instanceof Integer) {
                            statistics.setCount(((Integer) result).longValue());
                        } else if (result instanceof Boolean) {
                            statistics.setSuccess((Boolean) result);
                        }
                        StatisticsCollector.collect(statistics, span);
                    }
                    return result;
                });
    }

    @Override
    public boolean isTarget(String className) {
        String dotName = className.replace("/", ".");
        return targetDriverClasses.contains(dotName);
    }

    @Override
    public List<CtMethod> targetMethods(ClassPool pool, CtClass clz) throws NotFoundException {
        try {
            CtMethod ctMethod = clz.getMethod("connect", "(Ljava/lang/String;Ljava/util/Properties;)Ljava/sql/Connection;");
            return Collections.singletonList(ctMethod);
        } catch (NotFoundException e) {
            LOGGER.warn("connect() not found in {}, skipping", clz.getName());
            return Collections.emptyList();
        }
    }

    @Override
    public MethodInfo getMethodInfo(String methodName) {
        return MethodInfo.newBuilder().createBody(this, methodName);
    }

    @Override
    public Statistics begin(Object obj, Object... args) {
        String driverClass = obj.getClass().getName();
        SpanNode span = trace.beginSpan(driverClass, "connect");
        JdbcStatistics statistics = new JdbcStatistics(span.getTraceId(), span.getSpanId());
        statistics.setSpan(span);
        statistics.setClassName(driverClass);
        statistics.setMethodName("connect");
        LOGGER.debug("begin class:{} args:{}", driverClass, args);
        statistics.setStartTime(System.currentTimeMillis());
        return statistics;
    }

    @Override
    public void exception(Statistics statistics, Throwable t) {
        statistics.setError(t);
        trace.markError();
    }

    @Override
    protected Object doEnd(Statistics statistics, Object result) {
        JdbcStatistics jdbc = (JdbcStatistics) statistics;
        if (Objects.nonNull(result) && result instanceof Connection) {
            jdbc.setOriginalConnection(result);
            Object proxied = proxyConnection((Connection) result);
            jdbc.setProxiedConnection(proxied);
            return proxied;
        }
        return result;
    }

    @Override
    protected SpanNode endSpan(Statistics statistics) {
        return trace.endSpan();
    }
}