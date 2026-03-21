package com.lawrence.monitor.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MonitorRegistry {

    private static final Map<Class<? extends AbstractMonitor>, AbstractMonitor> MONITORS
            = new ConcurrentHashMap<>();

    private MonitorRegistry() {
    }

    public static void register(AbstractMonitor monitor) {
        MONITORS.put(monitor.getClass(), monitor);
    }

    public static List<AbstractMonitor> getMonitors() {
        return List.copyOf(MONITORS.values());
    }

    @SuppressWarnings("unchecked")
    public static <T extends AbstractMonitor> T get(Class<T> type) {
        return (T) MONITORS.get(type);
    }
}
