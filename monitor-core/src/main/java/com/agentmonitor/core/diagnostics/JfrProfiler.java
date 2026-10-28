package com.agentmonitor.core.diagnostics;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import jdk.management.jfr.FlightRecorderMXBean;

/** Owns exactly one recording; never stops or changes the user's other recordings. */
public final class JfrProfiler implements AutoCloseable {
    private final FlightRecorderMXBean bean;
    private final long id;
    public JfrProfiler(TargetJvm target, boolean exceptions) throws Exception {
        bean = target.bean(FlightRecorderMXBean.MXBEAN_NAME, FlightRecorderMXBean.class);
        id = bean.newRecording();
        try {
            bean.setRecordingOptions(id, Map.of("name", "agent-monitor-" + UUID.randomUUID(), "disk", "true", "duration", "3600 s", "maxAge", "120 s", "maxSize", "33554432"));
            Map<String,String> settings = new HashMap<>();
            for (String event : List.of("jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.ObjectAllocationSample", "jdk.GarbageCollection", "jdk.GCHeapSummary", "jdk.GCPhasePause", "jdk.CPULoad", "jdk.ThreadCPULoad"))
                settings.put(event + "#enabled", "true");
            settings.put("jdk.ExecutionSample#period", "20 ms"); settings.put("jdk.NativeMethodSample#period", "20 ms");
            for (String event : List.of("jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite")) {
                settings.put(event+"#enabled", "true"); settings.put(event+"#threshold", "10 ms"); settings.put(event+"#stackTrace", "true");
            }
            settings.put("jdk.ObjectAllocationSample#stackTrace", "true");
            settings.put("jdk.JavaExceptionThrow#enabled", Boolean.toString(exceptions));
            settings.put("jdk.JavaExceptionThrow#stackTrace", "true");
            bean.setRecordingSettings(id, settings); bean.startRecording(id);
        } catch (Exception error) { bean.closeRecording(id); throw error; }
    }
    /** Snapshot a running ring without disturbing it; stream is copied to the host atomically. */
    public synchronized void dump(Path output) throws Exception {
        long snapshot = bean.cloneRecording(id, true);
        long stream = -1; Path temporary = null;
        try {
            stream = bean.openStream(snapshot, Map.of("blockSize", "65536"));
            Files.createDirectories(output.toAbsolutePath().getParent());
            temporary = Files.createTempFile(output.toAbsolutePath().getParent(), "jfr-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                long bytes = 0;
                for (byte[] block; (block = bean.readStream(stream)) != null;) {
                    bytes += block.length; if (bytes > 128L*1024*1024) throw new IOException("JFR snapshot exceeds 128MiB");
                    out.write(block);
                }
            }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); temporary = null;
        } finally {
            try { if (stream >= 0) bean.closeStream(stream); }
            finally { bean.closeRecording(snapshot); if (temporary != null) Files.deleteIfExists(temporary); }
        }
    }
    @Override public synchronized void close() throws IOException { bean.closeRecording(id); }
}
