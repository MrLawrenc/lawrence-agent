package com.lawrence.monitor;

import com.lawrence.monitor.core.AbstractMonitor;
import com.lawrence.monitor.core.MethodInfo;
import com.lawrence.monitor.core.Monitor;
import com.lawrence.monitor.core.MonitorRegistry;
import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;
import javassist.*;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

/**
 * @author : MrLawrenc
 * date  2020/7/4 19:13
 */
public class TransformerService implements ClassFileTransformer {
    private static final Logger logger = LoggerFactory.getLogger(TransformerService.class);
    /**
     * 插桩复制之后的方法名后缀
     */
    public static final String AGENT_SUFFIX = "$lawrence";

    private final AgentConfig agentConfig;

    public TransformerService(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
        ServiceLoader<AbstractMonitor> loader = ServiceLoader.load(AbstractMonitor.class);
        for (AbstractMonitor monitor : loader) {
            logger.info("load monitor:{}", monitor.getClass().getName());
            monitor.init(this.agentConfig);
            MonitorRegistry.register(monitor);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] data) {
        if (loader == null || className == null) {
            return data;
        }
        Monitor monitor = MonitorRegistry.getMonitors().stream().filter(m -> m.isTarget(className)).findAny().orElse(null);
        if (monitor == null) {
            return data;
        }
        try {
            ClassPool pool = new ClassPool(true);
            pool.insertClassPath(new LoaderClassPath(loader));
            pool.appendClassPath(new ClassClassPath(AbstractMonitor.class));
            CtClass targetClz = pool.get(className.replaceAll("/", "."));

            int modifiers = targetClz.getModifiers();
            if (Modifier.isNative(modifiers) || Modifier.isEnum(modifiers) || Modifier.isInterface(modifiers)) {
                return data;
            }

            String clzName = className.replaceAll("/", ".");
            logger.info("!!!!!monitor class[{}] for class[{}]", monitor.getClass().getName(), clzName);
            List<CtMethod> methods = monitor.targetMethods(pool, targetClz);
            if (Objects.nonNull(methods) && methods.size() > 0) {
                CtClass throwable = pool.get(Throwable.class.getName());
                for (CtMethod method : methods) {
                    logger.info("target {}#{}  use monitor:{}", clzName, method.getName(), monitor.getClass().getName());
                    String newMethodName = method.getName() + AGENT_SUFFIX;
                    logger.info("start copy new method : {}", newMethodName);
                    CtMethod newMethod = CtNewMethod.copy(method, newMethodName, targetClz, null);
                    targetClz.addMethod(newMethod);

                    MethodInfo methodInfo = monitor.getMethodInfo(newMethodName, method);
                    if (methodInfo.isNewInfo()) {
                        method.setBody(methodInfo.getNewBody());
                    } else {
                        method.setBody(methodInfo.getTryBody());
                        method.addCatch(methodInfo.getCatchBody(), throwable);
                        method.insertAfter(methodInfo.getFinallyBody(), true);
                    }
                    logger.info("copy method{} end", method.getName());
                }
                targetClz.writeFile("class");
                logger.info(new File("class").getAbsolutePath());
                return targetClz.toBytecode();
            }
        } catch (Throwable e) {
            logger.error("transformer error for class [{}]: {}", className, e.getMessage());
        }

        return data;
    }
}