package com.agentmonitor.agent.interceptor;

import com.agentmonitor.bootstrap.bridge.BootstrapBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Replaces immutable HTTP request arguments with metadata-only copies; preserves response/future identity. */
public final class ClientSpanAdvice {
    @Advice.OnMethodEnter(suppress=Throwable.class)
    public static BootstrapBridge.InvocationToken enter(@Advice.Origin("#t") String type,@Advice.Origin("#m") String method,
            @Advice.This(optional=true) Object target,@Advice.AllArguments(readOnly=false,typing=Assigner.Typing.DYNAMIC) Object[] arguments){
        Object[] updated=arguments;
        BootstrapBridge.InvocationToken token=BootstrapBridge.enterDependency(type,method,target,updated);
        arguments=updated;
        return token;
    }
    @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
    public static void exit(@Advice.Enter BootstrapBridge.InvocationToken token,@Advice.This(optional=true) Object target,
            @Advice.Return(typing=Assigner.Typing.DYNAMIC) Object result,@Advice.Thrown Throwable error){
        BootstrapBridge.exitDependency(token,target,new Object[]{result},error);
    }
}
