package agentfixtures.performance;
import java.io.*;
import java.lang.invoke.*;
import java.net.*;
import java.nio.file.*;
import java.lang.management.*;
public final class ScaleWorkload {
    public static void main(String[] args) throws Throwable {
        var loader=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ScaleWorkload.class.getClassLoader());
        MethodHandle[] handles=new MethodHandle[2250];
        for(int i=0;i<handles.length;i++) {
            Class<?> cls=Class.forName("agentfixtures.performance.generated.Catalog$C"+i,true,loader);
            handles[i]=MethodHandles.publicLookup().findStatic(cls,"work",MethodType.methodType(long.class,long.class));
        }
        var thread=ManagementFactory.getThreadMXBean();
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        var memory=ManagementFactory.getMemoryMXBean();
        System.out.println("READY");
        var input=new BufferedReader(new InputStreamReader(System.in));int round=0;
        for(String command;(command=input.readLine())!=null && !command.equals("EXIT");) {
            if(command.equals("GC")){System.gc();System.out.println("GC_DONE");continue;}
            if(!command.equals("RUN"))continue;
            long heapBefore=memory.getHeapMemoryUsage().getUsed(),gcBefore=gcCount(),gcMsBefore=gcMillis();
            long cpu=os.getProcessCpuTime(),own=thread.getCurrentThreadCpuTime(),start=System.nanoTime();
            long checksum=BatchRoot.runRound(handles,++round);
            long elapsed=System.nanoTime()-start,ownElapsed=thread.getCurrentThreadCpuTime()-own,cpuElapsed=os.getProcessCpuTime()-cpu;
            System.out.println("{\"elapsedNanos\":"+elapsed+",\"threadCpuNanos\":"+ownElapsed+",\"processCpuNanos\":"+cpuElapsed+
                ",\"heapBefore\":"+heapBefore+",\"heapAfter\":"+memory.getHeapMemoryUsage().getUsed()+",\"gcCollections\":"+(gcCount()-gcBefore)+
                ",\"gcMillis\":"+(gcMillis()-gcMsBefore)+",\"businessCalls\":2250,\"checksum\":"+checksum+"}");
        }
        loader.close();
    }
    private static long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionCount())).sum();}
    private static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum();}
}
