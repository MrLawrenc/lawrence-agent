package com.agentmonitor.core.service;

import com.agentmonitor.core.util.AtomicFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.Map;

/** Cross-process ownership plus a durable marker for interrupted or unconfirmed restores. */
final class TargetJvmLease implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FileChannel channel;
    private final FileLock lock;
    private final Path marker;
    private boolean closed;

    static TargetJvmLease acquire(String pid) throws IOException {
        pid = Long.toString(Long.parseLong(pid));
        ProcessHandle target = ProcessHandle.of(Long.parseLong(pid)).filter(ProcessHandle::isAlive).orElse(null);
        if (target == null) throw new IllegalArgumentException("目标 JVM 不存在: " + pid);
        String started = target.info().startInstant().orElseThrow(() -> new IllegalArgumentException("无法确认目标进程启动时间"))
                .toString().replaceAll("[^0-9]", "");
        Path directory = Path.of(System.getProperty("agent.monitor.leaseDir",
                Path.of(System.getProperty("user.home"), ".agent-monitor", "leases").toString()));
        Files.createDirectories(directory);
        Path marker = directory.resolve(pid + "-" + started + ".json");
        FileChannel channel = FileChannel.open(directory.resolve(pid + "-" + started + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
            if (lock == null) throw new IllegalStateException("目标 JVM 已有活动采集会话: " + pid);
            if (Files.exists(marker)) {
                String state = JSON.readTree(marker.toFile()).path("state").asText();
                if (!"IDLE".equals(state)) throw new IllegalStateException("该 JVM 的上次采集未确认还原，请重启目标 JVM 后再采集: " + marker);
            }
            return new TargetJvmLease(channel, lock, marker);
        } catch (Exception error) {
            if (lock != null) lock.release(); channel.close(); throw error;
        }
    }
    private TargetJvmLease(FileChannel channel, FileLock lock, Path marker) {
        this.channel = channel; this.lock = lock; this.marker = marker;
    }
    void state(String state) throws IOException {
        AtomicFiles.write(marker, JSON.writeValueAsString(Map.of("state", state, "ownerPid", ProcessHandle.current().pid())));
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { lock.release(); } catch (IOException ignored) { }
        try { channel.close(); } catch (IOException ignored) { }
        // Never unlink a lock file: another process may already be waiting on the inode.
    }
}
