package com.agentmonitor.core.service;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class TargetJvmLeaseTest {
    @TempDir Path directory;
    @Test void excludesConcurrentOwnersAndBlocksAnUnconfirmedPreviousCapture() throws Exception {
        String previous = System.getProperty("agent.monitor.leaseDir");
        System.setProperty("agent.monitor.leaseDir", directory.toString());
        String pid = Long.toString(ProcessHandle.current().pid());
        try {
            try (var first = TargetJvmLease.acquire(pid)) {
                assertThrows(IllegalStateException.class, () -> TargetJvmLease.acquire(pid));
                assertThrows(IllegalStateException.class, () -> TargetJvmLease.acquire("0" + pid));
                assertThrows(IllegalStateException.class, () -> TargetJvmLease.acquire("+" + pid));
                first.state("IDLE");
            }
            try (var second = TargetJvmLease.acquire(pid)) { second.state("MONITORING"); }
            assertThrows(IllegalStateException.class, () -> TargetJvmLease.acquire(pid));
        } finally {
            if (previous == null) System.clearProperty("agent.monitor.leaseDir"); else System.setProperty("agent.monitor.leaseDir", previous);
        }
    }
}
