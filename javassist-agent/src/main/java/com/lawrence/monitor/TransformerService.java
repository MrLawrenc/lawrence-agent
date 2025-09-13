package com.lawrence.monitor;

import com.lawrence.monitor.core.AbstractMonitor;
import com.lawrence.monitor.core.MethodInfo;
import com.lawrence.monitor.core.Monitor;
import com.lawrence.monitor.core.impl.JakartaServletMonitor;
import com.lawrence.monitor.core.impl.JdbcMonitor;
import com.lawrence.monitor.core.impl.ServletMonitor;
import com.lawrence.monitor.stack.StackNode;
import com.lawrence.monitor.util.ThreadLocalUtil;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;
import javassist.*;
import lombok.SneakyThrows;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author : MrLawrenc
 * date  2020/7/4 19:13
 */
public class TransformerService implements ClassFileTransformer {
    private static final Logger logger = LoggerFactory.getLogger(TransformerService.class);
    /**
     * 插桩复制之后的方法名后缀
     */
    private static final String AGENT_SUFFIX = "$lawrence";


    /**
     * 需要统计堆栈数据的包
     */
    private static final List<String> STACK_BASE_PKG = Arrays.asList("com.huize", "com.swust"
            , "com.github.mrlarence", "com.mrlarence", "com.lawrence");
    /**
     * 统计堆栈数据时需要排除的包
     */
    private static final List<String> STACK_EXCLUDE_PKG = Arrays.asList("java", "sun", "javassist", "org", "com.lawrence.monitor");
    /**
     * 统计堆栈数据时需要排除的方法
     */
    private static final List<String> STACK_EXCLUDE_METHOD = Arrays.asList("main", "toString", "equals", "hashCode", "wait", "notify", "clone");
    /**
     * 统计堆栈数据时 排除getter和setter方法
     */
    private static final List<String> STACK_EXCLUDE_GET_AND_SET = Arrays.asList("get", "set");

    private static final String STACK_LOCAL_VARIABLE_SRC = "stackNode";
    private static final String STACK_SRC = "{\n" +
//            "StackTraceElement stackTraceElement = Thread.currentThread().getStackTrace()[1];\n" +
//             "StackTraceElement[] stackTraceElement = Thread.currentThread().getStackTrace();\n" +
//             "StackTraceElement[] stackTraceElement = new Throwable().getStackTrace();\n" +
//             StackNode.class.getName() + " stackNode = " + ThreadLocalUtil.class.getName() + ".globalThreadLocal.get();\n" +
//             "if(java.util.Objects.nonNull(stackNode)){\n" +
//              "   stackNode.addNode(stackTraceElement);\n" +
//              "}\n" +
            "stackNode = " + ThreadLocalUtil.class.getName() + ".globalThreadLocal.get();\n" +
            "   if(java.util.Objects.nonNull(stackNode)){\n" +
            "       System.out.println(stackNode.getClass());\n" +
            StackNode.class.getName() + ".addNode();\n" +
            "   }\n" +
            "}";

    /**
     * 所有monitor实现类集合
     */
    private final List<AbstractMonitor> monitorList = new ArrayList<>();

    private final AgentConfig agentConfig;

    public TransformerService(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
        ServiceLoader<AbstractMonitor> loader = ServiceLoader.load(AbstractMonitor.class);
        for (AbstractMonitor monitor : loader) {
            logger.info("load monitor:{}", monitor.getClass().getName());
            monitor.init(this.agentConfig);
            monitorList.add(monitor);
        }
    }

    @SneakyThrows
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] data) {
        if (className.replaceAll("/", ".").equals(StackNode.class.getName())) {
            return data;
        }
        ClassPool pool = new ClassPool(true);
        pool.insertClassPath(new LoaderClassPath(loader));
        //若需要在系统类里面植入代码（即当前loader为app loader的双亲），需要在class pool中加入app loader ，否则无法找到相关植入的类
        pool.appendClassPath(new ClassClassPath(StackNode.class));
        CtClass targetClz = pool.get(className.replaceAll("/", "."));

        int modifiers = targetClz.getModifiers();
        if (Modifier.isNative(modifiers) || Modifier.isEnum(modifiers) || Modifier.isInterface(modifiers)) {
            //skip
            return data;
        }

        Monitor monitor = monitorList.stream().filter(m -> m.isTarget(className)).findAny().orElse(null);
        String clzName = className.replaceAll("/", ".");
        try {
            if (Objects.nonNull(monitor)) {
                logger.info("!!!!!monitor class[{}] for class[{}]", monitor.getClass().getName(), clzName);
                CtMethod method = monitor.targetMethod(pool, targetClz);
                if (Objects.nonNull(method)) {
                    logger.info("target {}#{}  use monitor:{}", clzName, method.getName(), monitor.getClass().getName());
                    String newMethodName = method.getName() + AGENT_SUFFIX;
                    logger.info("start copy new method : {}", newMethodName);
                    CtMethod newMethod = CtNewMethod.copy(method, newMethodName, targetClz, null);
                    targetClz.addMethod(newMethod);
                    CtClass throwable = pool.get(Throwable.class.getName());

                    MethodInfo methodInfo = monitor.getMethodInfo(newMethodName);
                    if (methodInfo.isNewInfo()) {
                        method.setBody(methodInfo.getNewBody());
                    } else {
                        method.setBody(methodInfo.getTryBody());
                        method.addCatch(methodInfo.getCatchBody(), throwable);
                        method.insertAfter(methodInfo.getFinallyBody(), true);
                    }
                    logger.info("copy method{} end", method.getName());
                    targetClz.writeFile("class");
                    logger.info(new File("class").getAbsolutePath());
                    return targetClz.toBytecode();
                }
            } else {
                for (String excludePkg : STACK_EXCLUDE_PKG) {
                    if (clzName.startsWith(excludePkg)) {
                        return data;
                    }
                }

                boolean isStackClz = false;
                for (String stackBasePkg : STACK_BASE_PKG) {
                    if (clzName.startsWith(stackBasePkg)) {
                        isStackClz = true;
                        break;
                    }
                }
                if (!isStackClz) {
                    return data;
                }
                logger.info("!!!!!stack for class[{}]", targetClz.getClass().getName());
                //以下是符合堆栈统计的class，插入堆栈统计代码
                CtMethod[] methods = targetClz.getDeclaredMethods();
                List<String> fieldNameList = Stream.of(targetClz.getDeclaredFields())
                        .map(field -> field.getName().toLowerCase()).collect(Collectors.toList());

                one:
                for (CtMethod method : methods) {
                    if (Modifier.isAbstract(method.getModifiers()) || Modifier.isNative(method.getModifiers())) {
                        continue;
                    }
                    if (STACK_EXCLUDE_METHOD.contains(method.getName())) {
                        continue;
                    }
                    String methodName = method.getName();

                    for (String getAndSet : STACK_EXCLUDE_GET_AND_SET) {
                        if (methodName.startsWith(getAndSet) && fieldNameList.contains(methodName.substring(3).toLowerCase())) {
                            continue one;
                        }
                    }

                    //插入堆栈统计代码
                    System.out.println("insert stack node : " + className + "#" + methodName);
                    //插入局部变量,不能直接写long startTime = System.currentTimeMillis(),会报错,
                    //要更改为先定义变量startTime，再插入startTime = System.currentTimeMillis()
                    CtClass stackNode = pool.get(StackNode.class.getName());
                    method.addLocalVariable(STACK_LOCAL_VARIABLE_SRC, stackNode);
                    method.insertBefore(STACK_SRC);
                    logger.info("insert source:{}", STACK_SRC);

                    //也可以使用如下方法插入堆栈
/*                    method.addLocalVariable("stackTree",pool.get(StackNode.class.getName()));
                    method.addLocalVariable("stackTraceElement",pool.get(StackTraceElement.class.getName()));
                    String src="{" +
                            "stackTraceElement = Thread.currentThread().getStackTrace()[1];\n" +
                            "stackTree = " + ThreadLocalUtil.class.getName() + ".globalThreadLocal.get();\n" +
                            "System.out.println(\"#########我是植入代码#########\");" +
                            "if(java.util.Objects.nonNull(stackTree)){\n" +
                            "   stackTree.addNode(stackTraceElement);\n" +
                            "}\n" +
                            "}";
                    method.insertBefore(src);*/
                }
                targetClz.writeFile("class");
                logger.info(new File("class").getAbsolutePath());
                return targetClz.toBytecode();
            }
        } catch (Exception e) {
            logger.error("transformer error", e);
        }

        return data;
    }
}