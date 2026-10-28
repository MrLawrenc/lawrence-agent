package com.agentmonitor.core.diagnostics;

import com.sun.tools.attach.VirtualMachine;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import javax.management.*;
import javax.management.remote.*;

/** One local management connection, owned by a diagnostic operation/session. */
public final class TargetJvm implements AutoCloseable {
    private final JMXConnector connector;
    private final MBeanServerConnection connection;
    public TargetJvm(String pid) throws Exception {
        long value = Long.parseLong(pid);
        if (value <= 0 || value == ProcessHandle.current().pid()) throw new IllegalArgumentException("需要外部 JVM PID");
        VirtualMachine vm = VirtualMachine.attach(pid);
        String address;
        try {
            address = vm.getAgentProperties().getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (address == null || address.isBlank()) address = vm.startLocalManagementAgent();
        } finally { vm.detach(); }
        Map<String,Object> environment = Map.of("jmx.remote.x.request.waiting.timeout", 5000L);
        try { connector = JMXConnectorFactory.connect(new JMXServiceURL(address), environment); }
        catch (java.io.IOException failure) { throw new java.io.IOException("本机 JMX 连接失败；检查目标 JVM 公布的 RMI 主机名。若该主机名不可达，可在目标启动参数配置 -Djava.rmi.server.hostname=127.0.0.1 后重试: " + failure.getMessage(), failure); }
        connection = connector.getMBeanServerConnection();
    }
    public MBeanServerConnection connection() { return connection; }
    public <T> T bean(String name, Class<T> type) throws Exception {
        return ManagementFactory.newPlatformMXBeanProxy(connection, name, type);
    }
    public Object attribute(ObjectName name, String attribute) throws Exception { return connection.getAttribute(name, attribute); }
    public String command(String operation, String... arguments) throws Exception {
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        boolean supported = Arrays.stream(connection.getMBeanInfo(name).getOperations()).anyMatch(o -> o.getName().equals(operation));
        if (!supported) throw new UnsupportedOperationException("目标 JVM 不支持 " + operation);
        return String.valueOf(connection.invoke(name, operation, new Object[]{arguments}, new String[]{"[Ljava.lang.String;"}));
    }
    public Map<String,Object> environment() throws Exception {
        RuntimeMXBean runtime = bean(ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("timestampMillis", System.currentTimeMillis()); result.put("vmName", runtime.getVmName());
        result.put("vmVendor", runtime.getVmVendor()); result.put("vmVersion", runtime.getVmVersion());
        result.put("startTimeMillis", runtime.getStartTime()); result.put("inputArguments", runtime.getInputArguments());
        result.put("systemProperties", runtime.getSystemProperties()); result.put("classPath", runtime.getClassPath());
        result.put("libraryPath", runtime.getLibraryPath());
        return result;
    }
    public Map<String,Object> sample() throws Exception {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("timestampMillis", System.currentTimeMillis());
        MemoryMXBean memory = bean(ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        result.put("heap", memory.getHeapMemoryUsage()); result.put("nonHeap", memory.getNonHeapMemoryUsage());
        ObjectName os = new ObjectName(ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME);
        for (String field : List.of("ProcessCpuLoad", "CpuLoad", "ProcessCpuTime", "AvailableProcessors", "CommittedVirtualMemorySize")) {
            try { Object value=attribute(os,field); if ((field.equals("ProcessCpuLoad") || field.equals("CpuLoad")) && value instanceof Number n && (!Double.isFinite(n.doubleValue()) || n.doubleValue()<0 || n.doubleValue()>1)) value=null; result.put(field, value); } catch (Exception ignored) { result.put(field, null); }
        }
        ClassLoadingMXBean classes = bean(ManagementFactory.CLASS_LOADING_MXBEAN_NAME, ClassLoadingMXBean.class);
        result.put("loadedClasses", classes.getLoadedClassCount()); result.put("unloadedClasses", classes.getUnloadedClassCount());
        var gc = new ArrayList<Map<String,Object>>();
        for (var item : ManagementFactory.getPlatformMXBeans(connection, GarbageCollectorMXBean.class))
            gc.add(Map.of("name", item.getName(), "count", item.getCollectionCount(), "timeMillis", item.getCollectionTime()));
        result.put("gc", gc);
        var buffers = new ArrayList<Map<String,Object>>();
        for (var item : ManagementFactory.getPlatformMXBeans(connection, BufferPoolMXBean.class))
            buffers.add(Map.of("name", item.getName(), "count", item.getCount(), "usedBytes", item.getMemoryUsed(), "capacityBytes", item.getTotalCapacity()));
        result.put("bufferPools", buffers); result.put("pools", pools());
        ThreadMXBean threads = bean(ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        result.put("threadCount", threads.getThreadCount()); result.put("peakThreadCount", threads.getPeakThreadCount());
        return result;
    }
    /** MBeans may be absent (e.g. Hikari registerMbeans=false); absence is explicit evidence. */
    public Map<String,Object> pools() throws Exception {
        List<Map<String,Object>> pools = new ArrayList<>();
        int omitted = 0;
        for (ObjectName name : connection.queryNames(null, null)) {
            String text = name.toString().toLowerCase(Locale.ROOT);
            if (!(text.contains("hikari") || text.contains("threadpool") || text.contains("executor") || text.contains("datasource"))) continue;
            if (pools.size() >= 128) { omitted++; continue; }
            Map<String,Object> values = new LinkedHashMap<>(); values.put("objectName", name.toString());
            String[] fields={"ActiveConnections", "IdleConnections", "TotalConnections", "ThreadsAwaitingConnection",
                    "ActiveCount", "PoolSize", "CorePoolSize", "MaximumPoolSize", "QueueSize", "CompletedTaskCount", "RejectedCount", "maxThreads", "currentThreadsBusy", "currentThreadCount"};
            try { for (Object item : connection.getAttributes(name,fields)) { Attribute a=(Attribute)item; if (a.getValue() instanceof Number || a.getValue() instanceof Boolean) values.put(a.getName(),a.getValue()); } }
            catch (Exception ignored) { }
            if (values.size() > 1) pools.add(values);
        }
        return Map.of("registeredPools", pools, "omittedPools", omitted,
                "coverage", "Registered readable MBeans only; unregistered pools require agent evidence. Hikari requires registerMbeans=true.");
    }
    public Map<String,Object> threads() throws Exception {
        ThreadMXBean bean = bean(ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        long[] all = bean.getAllThreadIds(); long[] selected = Arrays.copyOf(all, Math.min(all.length, 1024));
        ThreadInfo[] infos = bean.getThreadInfo(selected, 64);
        Map<Long,Long> cpuTimes=new HashMap<>();
        try { var extended=bean(ManagementFactory.THREAD_MXBEAN_NAME,com.sun.management.ThreadMXBean.class);
            if (extended.isThreadCpuTimeSupported() && extended.isThreadCpuTimeEnabled()) { long[] times=extended.getThreadCpuTime(selected); for(int i=0;i<selected.length;i++)cpuTimes.put(selected[i],times[i]); }
        } catch(Exception ignored) { }
        long[] deadlocked;
        try { deadlocked = bean.isSynchronizerUsageSupported() ? bean.findDeadlockedThreads() : bean.findMonitorDeadlockedThreads(); }
        catch (UnsupportedOperationException ignored) { deadlocked = null; }
        List<Map<String,Object>> entries = new ArrayList<>(); Map<String,List<Long>> groups = new LinkedHashMap<>();
        Map<String,Integer> states = new TreeMap<>();
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            List<String> stack = Arrays.stream(info.getStackTrace()).map(StackTraceElement::toString).toList();
            String fingerprint = info.getThreadState() + "\n" + String.join("\n", stack);
            groups.computeIfAbsent(fingerprint, k -> new ArrayList<>()).add(info.getThreadId());
            states.merge(info.getThreadState().name(), 1, Integer::sum);
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("threadId", info.getThreadId()); item.put("name", info.getThreadName()); item.put("state", info.getThreadState().name());
            item.put("lockOwnerId", info.getLockOwnerId()); item.put("lockOwnerName", Objects.toString(info.getLockOwnerName(), ""));
            item.put("lock", Objects.toString(info.getLockName(), "")); item.put("stack", stack);
            item.put("stackDepthLimited", stack.size() == 64);
            item.put("cpuNanos", cpuTimes.getOrDefault(info.getThreadId(), -1L));
            entries.add(item);
        }
        var grouped = groups.entrySet().stream().sorted(Comparator.<Map.Entry<String,List<Long>>>comparingInt(e -> e.getValue().size()).reversed())
                .limit(128).map(e -> Map.of("stack", e.getKey(), "threadIds", e.getValue(), "count", e.getValue().size())).toList();
        return Map.of("timestampMillis", System.currentTimeMillis(), "threads", entries, "states", states,
                "stackGroups", grouped, "omittedGroups", Math.max(0, groups.size()-128), "omittedThreads", Math.max(0, all.length-selected.length),
                "deadlockedThreadIds", deadlocked == null ? new long[0] : deadlocked,
                "coverage", "ThreadMXBean platform threads; virtual thread coverage is JVM-dependent. Repeated stacks are clues, not proof of a hang.");
    }
    public Map<String,Object> histogram(boolean live) throws Exception {
        // -all avoids the full GC requested by a live-only histogram.
        String output = live ? command("gcClassHistogram") : command("gcClassHistogram", "-all");
        List<Map<String,Object>> rows = new ArrayList<>(); int omittedRows=0;
        for (String line : output.split("\n")) {
            String[] fields = line.trim().split("\\s+", 4);
            if (fields.length == 4 && fields[0].matches("[0-9]+:")) {
                if (rows.size() >= 10000) { omittedRows++; continue; }
                rows.add(Map.of("class", fields[3], "instances", Long.parseLong(fields[1]), "bytes", Long.parseLong(fields[2])));
            }
        }
        return Map.of("timestampMillis", System.currentTimeMillis(), "liveOnly", live, "classes", rows, "rowLimit", 10000, "omittedRows", omittedRows);
    }
    public String heapDump(Path output) throws Exception {
        Path path = output.toAbsolutePath().normalize();
        if (Files.exists(path)) throw new IllegalArgumentException("目标文件已存在: " + path);
        Files.createDirectories(path.getParent());
        bean("com.sun.management:type=HotSpotDiagnostic",com.sun.management.HotSpotDiagnosticMXBean.class).dumpHeap(path.toString(),false);
        return "Heap dump written: "+path;
    }
    @Override public void close() throws java.io.IOException { connector.close(); }
}
