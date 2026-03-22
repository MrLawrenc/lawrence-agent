package com.lawrence.monitor.core.impl;

import com.lawrence.monitor.AgentConfig;
import com.lawrence.monitor.StatisticsType;
import com.lawrence.monitor.TimingConfig;
import com.lawrence.monitor.OutputConfig;
import com.lawrence.monitor.collect.StatisticsExporter;
import com.lawrence.monitor.collect.exporter.ChainExporter;
import com.lawrence.monitor.core.AbstractMonitor;
import com.lawrence.monitor.core.MethodInfo;
import com.lawrence.monitor.core.MonitorRegistry;
import com.lawrence.monitor.statistics.Statistics;
import com.lawrence.monitor.statistics.TimingStatistics;
import com.lawrence.monitor.trace.SpanNode;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 方法耗时链路监控器
 * <p>
 * 支持传入目标包路径，自动排除框架包，
 * 在方法调用链结束后以树形结构输出所有方法耗时到控制台。
 */
public class TimingMonitor extends AbstractMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimingMonitor.class);

    /** 默认排除的框架包前缀，不会被监控 */
    private static final List<String> DEFAULT_EXCLUDES = Arrays.asList(
            "java.", "javax.", "jakarta.", "sun.", "com.sun.", "jdk.",
            "org.springframework.", "org.apache.", "org.slf4j.", "org.yaml.",
            "ch.qos.", "javassist.", "net.bytebuddy.", "lombok.",
            "com.fasterxml.", "io.netty.", "com.zaxxer.", "com.mysql.",
            "com.lawrence.monitor.", "com.eclipsesource."
    );

    /** 非 void 方法包装模板 */
    private static final String NON_VOID_TEMPLATE = "{\n"
            + "%s\n"
            + "Object result = null;\n"
            + "try{\n"
            + "   result=($w)$0.%s($$);\n"
            + "}catch(java.lang.Throwable t){\n"
            + "   %s\n"
            + "   throw t;\n"
            + "}finally{\n"
            + "  %s\n"
            + "}\n"
            + "   return ($r) result;\n"
            + "}";

    /** void 方法包装模板 */
    private static final String VOID_TEMPLATE = "{\n"
            + "%s\n"
            + "try{\n"
            + "   $0.%s($$);\n"
            + "}catch(java.lang.Throwable t){\n"
            + "   %s\n"
            + "   throw t;\n"
            + "}finally{\n"
            + "  %s\n"
            + "}\n"
            + "}";

    private List<String> targetPackages = new ArrayList<>();
    private List<String> excludePackages = new ArrayList<>();
    private boolean enable = false;

    @Override
    protected void doInit(AgentConfig agentConfig) {
        TimingConfig config = agentConfig.getTimingConfig();
        if (config == null) {
            return;
        }
        this.enable = config.isEnable();
        if (config.getPackages() != null) {
            this.targetPackages = config.getPackages();
        }
        if (config.getExcludePackages() != null) {
            this.excludePackages = config.getExcludePackages();
        }
        OutputConfig outputConfig = agentConfig.getOutputConfig() != null
                ? agentConfig.getOutputConfig() : new OutputConfig();
        LOGGER.info("TimingMonitor init: enable={}, packages={}, excludePackages={}, outputMode={}",
                enable, targetPackages, excludePackages, outputConfig.getModes());
    }

    @Override
    protected Class<? extends Statistics> statisticsClass() {
        return TimingStatistics.class;
    }

    @Override
    protected StatisticsExporter buildExporter(OutputConfig outputConfig) {
        return new ChainExporter(outputConfig.buildChainOutput());
    }

    @Override
    public StatisticsType type() {
        return StatisticsType.TIMING;
    }

    @Override
    public boolean isTarget(String className) {
        if (!enable || targetPackages.isEmpty()) {
            return false;
        }
        String clzName = className.replace("/", ".");

        // 跳过 CGLIB 代理类和 JDK/JVM 合成类（类名含 $$ 或 $Lambda$）
        if (clzName.contains("$$") || clzName.contains("$Lambda$")) {
            return false;
        }

        // 先检查默认排除包
        for (String exclude : DEFAULT_EXCLUDES) {
            if (clzName.startsWith(exclude)) {
                return false;
            }
        }
        // 检查用户自定义排除包
        for (String exclude : excludePackages) {
            if (clzName.startsWith(exclude)) {
                return false;
            }
        }
        // 检查是否在目标包路径下
        for (String pkg : targetPackages) {
            if (clzName.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<CtMethod> targetMethods(ClassPool pool, CtClass clz) throws NotFoundException {
        return Arrays.stream(clz.getDeclaredMethods())
                .filter(m -> {
                    int mod = m.getModifiers();
                    // 跳过 abstract、native、static（static 方法无 $0）
                    return !Modifier.isAbstract(mod)
                            && !Modifier.isNative(mod)
                            && !Modifier.isStatic(mod);
                })
                .collect(Collectors.toList());
    }

    /**
     * 默认 fallback，不应被调用到（已被下方带 CtMethod 的重载替代）
     */
    @Override
    public MethodInfo getMethodInfo(String oldMethodName) {
        return MethodInfo.newBuilder().createVoidBody(this, oldMethodName);
    }

    /**
     * 生成方法植入体，区分 void / 非 void 返回类型，
     * 并将类名和方法名直接嵌入到 beginMethod 调用中，便于调用链展示。
     */
    @Override
    public MethodInfo getMethodInfo(String newMethodName, CtMethod originalMethod) {
        String monitorClz = TimingMonitor.class.getName();
        String statisticsClz = Statistics.class.getName();
        String originalMethodName = originalMethod.getName();
        String targetClassName;
        try {
            targetClassName = originalMethod.getDeclaringClass().getName();
        } catch (Exception e) {
            targetClassName = "Unknown";
        }

        // 从 MonitorRegistry 获取 monitor 实例，调用 beginMethod 传入类名和方法名
        String beginCode =
                monitorClz + " monitor = (" + monitorClz + ") "
                + MonitorRegistry.class.getName() + ".get(" + monitorClz + ".class);\n"
                + statisticsClz + " statistic = monitor.beginMethod($0, \""
                + targetClassName + "\", \"" + originalMethodName + "\", $args);\n";

        String exceptionCode = "monitor.exception(statistic, t);\n";

        MethodInfo methodInfo = new MethodInfo();
        methodInfo.setNewInfo(true);
        try {
            boolean isVoid = "void".equals(originalMethod.getReturnType().getName());
            if (isVoid) {
                String endCode = "monitor.end(statistic, null);\n";
                methodInfo.setNewBody(String.format(VOID_TEMPLATE,
                        beginCode, newMethodName, exceptionCode, endCode));
            } else {
                String endCode = "result = monitor.end(statistic, result);\n";
                methodInfo.setNewBody(String.format(NON_VOID_TEMPLATE,
                        beginCode, newMethodName, exceptionCode, endCode));
            }
        } catch (NotFoundException e) {
            // 无法获取返回类型时，降级为 void 模板
            String endCode = "monitor.end(statistic, null);\n";
            methodInfo.setNewBody(String.format(VOID_TEMPLATE,
                    beginCode, newMethodName, exceptionCode, endCode));
        }
        return methodInfo;
    }

    /**
     * 方法开始时调用，将当前方法入栈并建立父子关系
     *
     * @param obj       this 引用（实例方法）
     * @param className 类全限定名
     * @param methodName 方法名
     * @param args      方法参数
     */
    public Statistics beginMethod(Object obj, String className, String methodName, Object... args) {
        SpanNode span = trace.beginSpan(className, methodName);
        TimingStatistics statistics = new TimingStatistics(span.getTraceId(), span.getSpanId());
        statistics.setClassName(className);
        statistics.setMethodName(methodName);
        statistics.setArgs(args);
        statistics.setStartTime(System.currentTimeMillis());
        statistics.setSpan(span);
        return statistics;
    }

    @Override
    public Statistics begin(Object obj, Object... args) {
        return beginMethod(obj, "Unknown", "Unknown", args);
    }

    @Override
    public void exception(Statistics statistics, Throwable t) {
        statistics.setError(t);
        trace.markError();
    }

    @Override
    protected SpanNode endSpan(Statistics statistics) {
        SpanNode span = trace.endSpan();
        return (span != null && span.isRoot()) ? span : null;
    }
}
