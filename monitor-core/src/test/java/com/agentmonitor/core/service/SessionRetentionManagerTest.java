package com.agentmonitor.core.service;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.model.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class SessionRetentionManagerTest {
    @TempDir Path directory;
    private MonitoringSession completed() throws Exception {
        var session = MonitoringSession.create(directory.toString());
        Files.writeString(session.performanceReportPath(), "{\"status\":\"COMPLETED\",\"captureComplete\":true}");
        return session;
    }
    @Test void retentionIsOptInAndNeverDeletesCurrentIncompleteOrUnownedDirectories() throws Exception {
        var old = completed(); Thread.sleep(2);
        var incomplete = MonitoringSession.create(directory.toString()); Thread.sleep(2);
        var current = completed();
        var manager = new SessionRetentionManager();
        assertEquals(0, manager.pruneCompletedSessions(current, SessionRetentionPolicy.disabled()).deletedCount());
        Path foreign = directory.resolve("20200101-000000-000/session-00000000-0000-0000-0000-000000000000");
        Files.createDirectories(foreign.resolve("reports"));
        Files.writeString(foreign.resolve("reports/performance-report.json"), "{\"status\":\"COMPLETED\",\"captureComplete\":true}");
        var result = manager.pruneCompletedSessions(current, new SessionRetentionPolicy(1));
        assertEquals(1, result.deletedCount()); assertFalse(Files.exists(old.sessionDirectory()));
        assertTrue(Files.exists(current.sessionDirectory())); assertTrue(Files.exists(incomplete.sessionDirectory())); assertTrue(Files.exists(foreign));
    }
    @Test void retentionNeverTraversesOrRemovesASymbolicLinkInsideACandidate() throws Exception {
        var old = completed();
        Path protectedFile = directory.resolve("protected.txt"); Files.writeString(protectedFile, "keep");
        Files.createSymbolicLink(old.spansDirectory().resolve("external"), protectedFile);
        var current = completed();
        var result = new SessionRetentionManager().pruneCompletedSessions(current, new SessionRetentionPolicy(1));
        assertEquals(0, result.deletedCount()); assertTrue(Files.exists(old.sessionDirectory())); assertEquals("keep", Files.readString(protectedFile));
    }
    @Test void anUnreadableDirectoryBecomesACleanupFailureInsteadOfBreakingStop() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        var old = completed(); var current = completed();
        Path unreadable = Files.createDirectory(old.sessionDirectory().resolve("unreadable"));
        var permissions = Files.getPosixFilePermissions(unreadable);
        try {
            Files.setPosixFilePermissions(unreadable, java.util.Set.of());
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(unreadable), "Privileged user bypasses test permissions");
            var result = assertDoesNotThrow(() -> new SessionRetentionManager().pruneCompletedSessions(current, new SessionRetentionPolicy(1)));
            assertFalse(result.failures().isEmpty()); assertTrue(Files.exists(old.sessionDirectory()));
        } finally { Files.setPosixFilePermissions(unreadable, permissions); }
    }
}
