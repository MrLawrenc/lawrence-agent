package com.lawrence.monitor.core.impl;

import com.lawrence.monitor.AgentConfig;
import com.lawrence.monitor.ClassMatchRule;
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
import javassist.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * @author : MrLawrenc
 * date  2020/7/4 0:16
 * <p>
 * JDBC监控器实现
 */
public class BytesforceMonitor extends AbstractMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(BytesforceMonitor.class);

    protected List<ClassMatchRule> businessClassRules;

    @Override
    public void init(AgentConfig agentConfig) {
        this.businessClassRules = agentConfig.getBusinessClassRules();
    }

    @Override
    public StatisticsType type() {
        return StatisticsType.BF;
    }


    @Override
    public boolean isTarget(String className) {
        String fullClassName = className.replace("/", ".");
        boolean matched = businessClassRules.stream().anyMatch(rule -> rule.match(fullClassName));
        if (fullClassName.contains("bytesforce")) {
            LOGGER.info("monitor class:{} matched:{}", className, matched);
        }
        return matched;
    }

    //23+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:equals matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:finalize matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:test matched:true isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:toString matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:getClass matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:notifyAll matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:hashCode matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:wait matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:notify matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:test2 matched:true isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:wait matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:wait matched:false isStatic:false isPrivate:false
    //2025-12-27T14:53:43.223+08:00  INFO  24708[http-nio-8080-exec-1] com.lawrence.monitor.core.impl.BytesforceMonitor    : method:clone matched:false isStatic:false isPrivate:false
    @Override
    public List<CtMethod> targetMethods(ClassPool pool, CtClass clz) throws NotFoundException {
        return Arrays.stream(clz.getMethods()).filter(method -> {
            boolean matched = method.getName().contains("test");
            int mod = method.getModifiers();
            boolean isStatic = Modifier.isStatic(mod);
            boolean isPrivate = Modifier.isPrivate(mod);
            LOGGER.debug("method:{} matched:{} isStatic:{} isPrivate:{}", method.getName(), matched, isStatic, isPrivate);
            return matched && !isStatic && !isPrivate;
        }).toList();
    }

    @Override
    public MethodInfo getMethodInfo(String methodName) {
        return MethodInfo.newBuilder().createVoidBody(this, methodName);
    }

    @Override
    public Statistics begin(Object obj, Object... args) {
        Statistics statistics = StatisticsHelper.createStatistics(JdbcStatistics.class);
        LOGGER.debug("begin class:{} args:{}", obj.getClass(), args);
        statistics.setExecutor(obj);
        statistics.setArgs(args);
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
        LOGGER.info("Cost===>" + current.getCosTime());
        return obj;
    }

    @Override
    public WriterResp write(Writeable statistics) {
        return null;
    }
}