package com.agentmonitor.core.service;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.model.config.MonitoringConfig;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonitoringRuntimeStopTest {
    @TempDir Path directory;
    @Test void aBlockedAttachCannotHoldTheStopMutexOrOverwriteAnUnknownLease() throws Exception {
        Process target = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                Path.of("build/classes/java/test").toAbsolutePath().toString(), "agentfixtures.apppipeline.TargetLoop").start();
        String previous = System.getProperty("agent.monitor.leaseDir");
        System.setProperty("agent.monitor.leaseDir", directory.resolve("leases").toString());
        var entered = new CountDownLatch(1); var unblock = new CountDownLatch(1);
        var d = MonitoringConfig.defaults();
        var config = new MonitoringConfig(2, new MonitoringConfig.Scope(List.of(), List.of("example.Service"), List.of(), List.of()),
                d.sampling(), new MonitoringConfig.Output(directory.resolve("sessions").toString(), d.output().exporters(),
                d.output().capture(), d.output().spanFiles(), d.output().retention()), d.resource(), d.dependencies(), d.analysis());
        var runtime = new MonitoringRuntime(Long.toString(target.pid()), config, (pid, c, port, session) -> {
            entered.countDown(); unblock.await(30, TimeUnit.SECONDS);
        });
        Thread attaching = new Thread(() -> { try { runtime.attach(); } catch (Exception error) { throw new RuntimeException(error); } });
        try {
            runtime.startCollector(); attaching.start(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            var result = CompletableFuture.supplyAsync(runtime::stopAndAwaitRestore).get(12, TimeUnit.SECONDS);
            assertFalse(result.restored()); assertTrue(runtime.wasAttachAttempted());
            unblock.countDown(); attaching.join(2000); assertFalse(attaching.isAlive());
            try (var retry = new MonitoringRuntime(Long.toString(target.pid()), config)) {
                assertThrows(IllegalStateException.class, retry::startCollector,
                        "A late attach return must not clear an unconfirmed restoration marker");
            }
        } finally {
            unblock.countDown(); attaching.join(2000); runtime.close(); target.destroyForcibly();
            if (previous == null) System.clearProperty("agent.monitor.leaseDir"); else System.setProperty("agent.monitor.leaseDir", previous);
        }
    }
}
