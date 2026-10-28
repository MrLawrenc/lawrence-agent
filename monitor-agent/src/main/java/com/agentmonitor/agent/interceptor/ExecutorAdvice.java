package com.agentmonitor.agent.interceptor;
import com.agentmonitor.bootstrap.bridge.BootstrapBridge;
import net.bytebuddy.asm.Advice;

/** JDK-only calls inline into selected JDK executors. No tasks are replaced or wrapped. */
public final class ExecutorAdvice {
    public static final class Submit {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static BootstrapBridge.InvocationToken enter(@Advice.This Object executor,@Advice.AllArguments Object[] args){
            return BootstrapBridge.enterDependency("agent.async","submit",executor,args);
        }
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void exit(@Advice.Enter BootstrapBridge.InvocationToken token,@Advice.This Object executor,@Advice.AllArguments Object[] args,@Advice.Thrown Throwable error){
            BootstrapBridge.exitDependency(token,executor,args,error);
        }
    }
    public static final class Before {
        @Advice.OnMethodExit(suppress=Throwable.class)
        public static void enter(@Advice.This Object executor,@Advice.Argument(1) Object task){BootstrapBridge.enterDependency("agent.async","begin",executor,new Object[]{task});}
    }
    public static final class After {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static void enter(@Advice.This Object executor,@Advice.Argument(1) Throwable error){BootstrapBridge.enterDependency("agent.async","end",executor,new Object[]{error});}
    }
    public static final class Reject {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static void enter(@Advice.This Object executor,@Advice.AllArguments Object[] args){BootstrapBridge.enterDependency("agent.async","reject",executor,args);}
    }
    public static final class Fork {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static void enter(@Advice.This Object task){BootstrapBridge.enterDependency("agent.async","submit",null,new Object[]{task});}
    }
    public static final class Run {
        @Advice.OnMethodEnter(suppress=Throwable.class)
        public static BootstrapBridge.InvocationToken enter(@Advice.This Object task){return BootstrapBridge.enterDependency("agent.async","run",task,new Object[]{task});}
        @Advice.OnMethodExit(onThrowable=Throwable.class,suppress=Throwable.class)
        public static void exit(@Advice.Enter BootstrapBridge.InvocationToken token,@Advice.Thrown Throwable error){BootstrapBridge.exitDependency(token,null,null,error);}
    }
}
