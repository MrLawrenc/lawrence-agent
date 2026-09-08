package com.agentmonitor.agent.interceptor;

import com.agentmonitor.bootstrap.bridge.BootstrapBridge;

import net.bytebuddy.asm.Advice;

/** Lightweight shell injected into optional dependency clients. */
public final class DependencySpanAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static BootstrapBridge.InvocationToken onEnter(@Advice.Origin("#t") String className,
                                                           @Advice.Origin("#m") String methodName,
                                                           @Advice.This(optional = true) Object target,
                                                           @Advice.AllArguments Object[] arguments) {
        return BootstrapBridge.enterDependency(className, methodName, target, arguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter BootstrapBridge.InvocationToken token,
                              @Advice.This(optional = true) Object target,
                              @Advice.AllArguments Object[] arguments,
                              @Advice.Thrown Throwable error) {
        BootstrapBridge.exitDependency(token, target, arguments, error);
    }
}
