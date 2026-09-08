package com.agentmonitor.agent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agentmonitor.bootstrap.bridge.BootstrapBridge;

class BootstrapBridgeTest {

    @AfterEach
    void resetBridge() {
        BootstrapBridge.detachActive();
    }

    @Test
    void exitIsBoundToTheRuntimeThatCreatedItsTokenAcrossARetach() {
        RecordingRuntime oldRuntime = new RecordingRuntime();
        RecordingRuntime newRuntime = new RecordingRuntime();
        BootstrapBridge.register(oldRuntime);

        BootstrapBridge.InvocationToken token = BootstrapBridge.enterBusiness("sample.Type", "work", new Object[0]);
        BootstrapBridge.DetachStatus detached = BootstrapBridge.detachActive();
        BootstrapBridge.register(newRuntime);
        BootstrapBridge.exitBusiness(token, "sample.Type", "work", "()V", null, null);

        assertTrue(detached.restored());
        assertEquals(1, oldRuntime.detachCalls.get());
        assertEquals(1, oldRuntime.exitCalls.get());
        assertEquals(0, newRuntime.exitCalls.get());
        assertTrue(BootstrapBridge.hasActiveRuntime());
    }

    @Test
    void detachMakesNewEntriesNoOps() {
        RecordingRuntime runtime = new RecordingRuntime();
        BootstrapBridge.register(runtime);

        assertTrue(BootstrapBridge.detachActive().restored());

        assertFalse(BootstrapBridge.hasActiveRuntime());
        assertNull(BootstrapBridge.enterJdbc("execute", new Object(), new Object[0]));
    }

    @Test
    void failedRestoreKeepsTheOldRuntimeReachableForARetry() {
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.detachStatus = BootstrapBridge.DetachStatus.failed("JVM rejected reset");
        BootstrapBridge.register(runtime);

        BootstrapBridge.DetachStatus failed = BootstrapBridge.detachActive();

        assertFalse(failed.restored());
        assertTrue(BootstrapBridge.hasActiveRuntime());
        runtime.detachStatus = BootstrapBridge.DetachStatus.success("restored");
        assertTrue(BootstrapBridge.detachActive().restored());
        assertFalse(BootstrapBridge.hasActiveRuntime());
        assertEquals(2, runtime.detachCalls.get());
    }

    @Test
    void stopDuringStartupCannotPublishAnInstallerThatFinishedLater() {
        RecordingRuntime runtime = new RecordingRuntime();
        BootstrapBridge.Startup startup = BootstrapBridge.beginStartup();
        BootstrapBridge.bindStartup(startup, runtime);

        BootstrapBridge.DetachStatus stopped = BootstrapBridge.detachActive();

        assertTrue(stopped.restored());
        assertFalse(BootstrapBridge.activateStartup(startup, runtime));
        assertFalse(BootstrapBridge.hasActiveRuntime());
        assertEquals(1, runtime.detachCalls.get());
    }

    @Test
    void startupReservationRestoresThePreviousGenerationBeforePublishingTheNextOne() {
        RecordingRuntime oldRuntime = new RecordingRuntime();
        RecordingRuntime newRuntime = new RecordingRuntime();
        BootstrapBridge.register(oldRuntime);

        BootstrapBridge.Startup startup = BootstrapBridge.beginStartup();
        BootstrapBridge.bindStartup(startup, newRuntime);

        assertEquals(1, oldRuntime.detachCalls.get());
        assertFalse(BootstrapBridge.hasActiveRuntime());
        assertTrue(BootstrapBridge.activateStartup(startup, newRuntime));
        assertTrue(BootstrapBridge.hasActiveRuntime());
    }

    private static final class RecordingRuntime implements BootstrapBridge.Runtime {
        private final AtomicInteger detachCalls = new AtomicInteger();
        private final AtomicInteger exitCalls = new AtomicInteger();
        private BootstrapBridge.DetachStatus detachStatus = BootstrapBridge.DetachStatus.success("done");

        @Override
        public Object enterBusiness(String className, String methodName, Object[] arguments) {
            return new Object();
        }

        @Override
        public void exitBusiness(Object state, String className, String methodName, String descriptor,
                                 Throwable error, Object returnValue) {
            exitCalls.incrementAndGet();
        }

        @Override
        public Object enterDependency(String className, String methodName, Object target, Object[] arguments) {
            return null;
        }

        @Override
        public void exitDependency(Object state, Object target, Object[] arguments, Throwable error) { }

        @Override
        public Object enterJdbc(String methodName, Object statement, Object[] arguments) {
            return null;
        }

        @Override
        public void exitJdbc(Object state, Throwable error) { }

        @Override
        public void jdbcStatementCreated(String methodName, Object connection, Object[] arguments,
                                         Object statement) { }

        @Override
        public BootstrapBridge.DetachStatus detach() {
            detachCalls.incrementAndGet();
            return detachStatus;
        }
    }
}
