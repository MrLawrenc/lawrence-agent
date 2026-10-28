package agentfixtures.performance;
import java.lang.invoke.MethodHandle;
public final class BatchRoot {
    public static long runRound(MethodHandle[] handles,long seed) throws Throwable {
        long value=seed;for(MethodHandle handle:handles)value^=(long)handle.invokeExact(value);return value;
    }
}
