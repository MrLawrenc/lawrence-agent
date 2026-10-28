package com.agentmonitor.core.service;

import java.io.EOFException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.agentmonitor.model.jvm.JvmMetrics;
import com.agentmonitor.core.util.AppLog;
import com.sun.tools.attach.VirtualMachine;

public class JvmMetricsService implements AutoCloseable {

    private final String pid;
    private JMXConnector connector;
    private MBeanServerConnection connection;
    private MemoryMXBean memoryBean;
    private ThreadMXBean threadBean;
    private ClassLoadingMXBean classLoadingBean;
    private List<GarbageCollectorMXBean> gcBeans;

    public JvmMetricsService(String pid) {
        this.pid = pid;
    }

    public synchronized JvmMetrics sample() {
        try {
            ensureConnected();
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
            long gcCount = 0;
            long gcTime = 0;
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                long count = gcBean.getCollectionCount();
                long time = gcBean.getCollectionTime();
                if (count > 0) gcCount += count;
                if (time > 0) gcTime += time;
            }
            return new JvmMetrics(
                    safe(heap.getUsed()),
                    safe(heap.getCommitted()),
                    safe(heap.getMax()),
                    safe(nonHeap.getUsed()),
                    safe(nonHeap.getCommitted()),
                    threadBean.getThreadCount(),
                    threadBean.getDaemonThreadCount(),
                    classLoadingBean.getLoadedClassCount(),
                    classLoadingBean.getTotalLoadedClassCount(),
                    classLoadingBean.getUnloadedClassCount(),
                    gcCount,
                    gcTime,
                    System.currentTimeMillis(),
                    "");
        } catch (Exception ex) {
            AppLog.warn("[JvmMetricsService] sample failed pid=" + pid + ": " + ex.getMessage());
            closeQuietly();
            return JvmMetrics.unavailable(describeFailure(ex));
        }
    }

    private void ensureConnected() throws Exception {
        if (connection != null) return;
        String address = localConnectorAddress();
        connector = JMXConnectorFactory.connect(new JMXServiceURL(address));
        connection = connector.getMBeanServerConnection();
        memoryBean = ManagementFactory.newPlatformMXBeanProxy(connection,
                ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        threadBean = ManagementFactory.newPlatformMXBeanProxy(connection,
                ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
        classLoadingBean = ManagementFactory.newPlatformMXBeanProxy(connection,
                ManagementFactory.CLASS_LOADING_MXBEAN_NAME, ClassLoadingMXBean.class);
        gcBeans = ManagementFactory.getPlatformMXBeans(connection, GarbageCollectorMXBean.class);
        AppLog.info("[JvmMetricsService] connected pid=" + pid);
    }

    private String localConnectorAddress() throws Exception {
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            String address = vm.getAgentProperties()
                    .getProperty("com.sun.management.jmxremote.localConnectorAddress");
            if (address == null || address.isBlank()) {
                address = vm.startLocalManagementAgent();
            }
            return address;
        } finally {
            vm.detach();
        }
    }

    private static long safe(long value) {
        return Math.max(0, value);
    }

    private static String describeFailure(Exception failure) {
        if (failure instanceof java.rmi.ConnectIOException && hasCause(failure, EOFException.class)) {
            return "JMX/RMI 连接中断；目标 JVM 可使用 -Djava.rmi.server.hostname=127.0.0.1 指定本机地址";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        String singleLine = message.replaceAll("\\s+", " ").trim();
        return failure.getClass().getSimpleName() + ": "
                + (singleLine.length() > 160 ? singleLine.substring(0, 157) + "..." : singleLine);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (connector != null) connector.close();
        } catch (Exception ignored) {
        } finally {
            connector = null;
            connection = null;
            memoryBean = null;
            threadBean = null;
            classLoadingBean = null;
            gcBeans = List.of();
        }
    }
}
