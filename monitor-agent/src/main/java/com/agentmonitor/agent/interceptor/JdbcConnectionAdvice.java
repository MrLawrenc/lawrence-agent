package com.agentmonitor.agent.interceptor;

import com.agentmonitor.bootstrap.bridge.BootstrapBridge;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Advice injected into JDBC Connection implementations to retain prepared SQL templates. */
public final class JdbcConnectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static BootstrapBridge.InvocationToken onEnter() {
        return BootstrapBridge.enterJdbcConnection();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter BootstrapBridge.InvocationToken token,
                              @Advice.Origin("#m") String methodName,
                              @Advice.This(optional = true) Object connection,
                              @Advice.AllArguments Object[] arguments,
                              @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object statement,
                              @Advice.Thrown Throwable error) {
        BootstrapBridge.jdbcStatementCreated(token, methodName, connection, arguments, statement, error);
    }
}
