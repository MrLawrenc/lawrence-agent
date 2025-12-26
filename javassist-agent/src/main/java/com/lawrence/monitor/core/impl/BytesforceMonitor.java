package com.lawrence.monitor.core.impl;

import com.lawrence.monitor.AgentConfig;
import com.lawrence.monitor.StatisticsType;
import com.lawrence.monitor.core.AbstractMonitor;
import com.lawrence.monitor.core.MethodInfo;
import com.lawrence.monitor.statistics.JdbcStatistics;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.util.StatisticsHelper;
import com.lawrence.monitor.write.Writeable;
import com.lawrence.monitor.write.WriterResp;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Objects;

/**
 * @author : MrLawrenc
 * date  2020/7/4 0:16
 * <p>
 * JDBC监控器实现
 */
public class BytesforceMonitor extends AbstractMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(BytesforceMonitor.class);

    private static final String TARGET_CLZ = "com.mysql.cj.jdbc.NonRegisteringDriver";
    public static BytesforceMonitor INSTANCE;
    /**
     * {@link Connection}中需要代理的方法名集合
     */
    private static final String[] PROXY_CONNECTION_METHOD = new String[]{"prepareStatement"};
    /**
     * {@link PreparedStatement}中需要代理的方法名集合
     * 若执行查询时，没有使用{@link PreparedStatement#executeQuery()}方法获取查询结果，
     * 则之后会调用{@link PreparedStatement#getResultSet()}方法获取结果集
     */
    private static final String[] STATEMENT_METHOD = new String[]{"executeUpdate", "execute", "executeQuery", "getResultSet"};


    private String begin = "long start = System.currentTimeMillis();" +
            "com.github.mrlawrenc.attach.monitor.impl.JdbcMonitor collector=com.github.mrlawrenc.attach.monitor.impl.JdbcMonitor.INSTANCE;";

    private String end = "java.sql.Connection result=collector.proxyConnection((java.sql.Connection)c);" +
            "long cos = System.currentTimeMillis()-start;" +
            "System.out.println(\"方法耗时:\"+cos);";

    private String catchSrc = "{ $e.printStackTrace();" +
            "throw $e;}";

    private String finallySrc = "{Long end=System.nanoTime();\n" +
            "System.out.println(\"finally end:\");}";

    @Override
    public void init(AgentConfig agentConfig) {
        BytesforceMonitor.INSTANCE = this;
    }

    @Override
    public StatisticsType type() {
        return StatisticsType.BF;
    }


    @Override
    public boolean isTarget(String className) {
        return className.replace("/", ".").contains("bytesforce");
    }

    @Override
    public CtMethod targetMethod(ClassPool pool, CtClass clz) throws NotFoundException {
        CtMethod ctMethod = Arrays.stream(clz.getMethods()).filter(method -> method.getName().equals("test")).findFirst().orElse(null);
        LOGGER.info("targetMethod:{}", ctMethod);
        return ctMethod;
    }

    @Override
    public MethodInfo getMethodInfo(String methodName) {
        return MethodInfo.newBuilder().createVoidBody(this, methodName);
    }

    @Override
    public Statistics begin(Object obj, Object... args) {
        Statistics statistics = StatisticsHelper.createStatistics(JdbcStatistics.class);
        LOGGER.debug("begin class:{} args:{}", obj.getClass(), args);
        statistics.setStartTime(System.currentTimeMillis());
        return statistics;
    }

    @Override
    public void exception(Statistics statistics, Throwable t) {
        statistics.setT(t);
    }

    @Override
    public Object end(Statistics current, Object obj) {
        current.setEndTime(System.currentTimeMillis());
        LOGGER.info("Cost===>" + (current.getEndTime() - current.getStartTime()));
        return obj;
    }


    @Override
    public WriterResp write(Writeable statistics) {
        return null;
    }
}