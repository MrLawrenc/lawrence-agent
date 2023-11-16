package com.github.mrlawrenc.demo;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Collects statistics on the agent that takes time to execute a specified method
 *
 * @author : Lawrence
 * date  2023/3/26 21:43
 */
public class CostTimeStatisticAgent {
    public static void premain(String agentOps, Instrumentation inst) {
        System.out.println("#######                     Agent  Success                     #######");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classFileBuffer) throws IllegalClassFormatException {
                return ClassFileTransformer.super.transform(loader, className, classBeingRedefined, protectionDomain, classFileBuffer);
            }

            @Override
            public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classFileBuffer) throws IllegalClassFormatException {
                return ClassFileTransformer.super.transform(module, loader, className, classBeingRedefined, protectionDomain, classFileBuffer);
            }
        }, true);
    }
}