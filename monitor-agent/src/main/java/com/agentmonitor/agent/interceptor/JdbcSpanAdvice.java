package com.agentmonitor.agent.interceptor;

import com.agentmonitor.bootstrap.bridge.BootstrapBridge;

import net.bytebuddy.asm.Advice;

/** Advice injected into implementations of the standard JDBC Statement interfaces. */
public final class JdbcSpanAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static BootstrapBridge.InvocationToken onEnter(@Advice.Origin("#m") String methodName,
                                                           @Advice.This(optional = true) Object statement,
                                                           @Advice.AllArguments Object[] arguments) {
        return BootstrapBridge.enterJdbc(methodName, statement, arguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter BootstrapBridge.InvocationToken token,
                              @Advice.Thrown Throwable error) {
        BootstrapBridge.exitJdbc(token, error);
    }
}
