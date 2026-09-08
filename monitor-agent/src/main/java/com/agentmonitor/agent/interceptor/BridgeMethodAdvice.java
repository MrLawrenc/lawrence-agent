package com.agentmonitor.agent.interceptor;

import com.agentmonitor.bootstrap.bridge.BootstrapBridge;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Advice shell whose inlined bytecode refers only to the Bootstrap bridge.  Runtime tracing
 * logic deliberately lives behind that bridge in the isolated Agent core loader.
 */
public final class BridgeMethodAdvice {

    private BridgeMethodAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static BootstrapBridge.InvocationToken onEnter(@Advice.Origin("#t") String className,
                                                           @Advice.Origin("#m") String methodName,
                                                           @Advice.AllArguments Object[] arguments) {
        return BootstrapBridge.enterBusiness(className, methodName, arguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Origin("#t") String className,
                              @Advice.Origin("#m") String methodName,
                              @Advice.Origin("#d") String descriptor,
                              @Advice.Enter BootstrapBridge.InvocationToken token,
                              @Advice.Thrown Throwable error,
                              @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue) {
        BootstrapBridge.exitBusiness(token, className, methodName, descriptor, error, returnValue);
    }
}
