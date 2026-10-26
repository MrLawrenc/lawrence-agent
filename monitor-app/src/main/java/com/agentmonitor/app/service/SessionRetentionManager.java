package com.agentmonitor.app.service;

import com.agentmonitor.app.model.MonitoringSession;
import com.agentmonitor.app.model.SessionRetentionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Opt-in retention only touches tool-owned, finalized sessions; symbolic links are never traversed. */
public final class SessionRetentionManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record CleanupResult(List<Path> deleted, List<String> failures) {
        public CleanupResult { deleted = List.copyOf(deleted); failures = List.copyOf(failures); }
        public int deletedCount() { return deleted.size(); }
    }
    public CleanupResult pruneCompletedSessions(MonitoringSession current, SessionRetentionPolicy policy) {
        if (current == null || policy == null || policy.maxSessions() == 0) return new CleanupResult(List.of(), List.of());
        List<Path> candidates = new ArrayList<>(), deleted = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Path root = current.sessionRootDirectory().toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(root)) return new CleanupResult(List.of(), List.of("保留清理跳过符号链接路径: " + root));
            root = root.toRealPath();
            Path currentDirectory = current.sessionDirectory().toRealPath();
            try (var stamps = Files.list(root)) {
                for (Path stamp : stamps.filter(p -> p.getFileName().toString().matches("[0-9]{8}-[0-9]{6}-[0-9]{3}")
                        && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    try (var sessions = Files.list(stamp)) {
                        for (Path session : sessions.toList()) if (ownedAndCompleted(session)) candidates.add(session);
                    }
                }
            }
            candidates.removeIf(p -> p.toAbsolutePath().normalize().equals(currentDirectory));
            candidates.sort(Comparator.comparing(Path::toString).reversed());
            int keepOld = Math.max(0, policy.maxSessions() - 1);
            for (Path candidate : candidates.subList(Math.min(keepOld, candidates.size()), candidates.size())) {
                try {
                    if (!ownedAndCompleted(candidate)) continue;
                    Files.walkFileTree(candidate, new SimpleFileVisitor<>() {
                        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                            if (attributes.isSymbolicLink()) throw new IOException("拒绝删除符号链接: " + file);
                            Files.delete(file); return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                            if (error != null) throw error;
                            Files.delete(directory); return FileVisitResult.CONTINUE;
                        }
                    });
                    deleted.add(candidate);
                } catch (IOException error) { failures.add(candidate + ": " + error.getMessage()); }
            }
        } catch (IOException error) { failures.add(error.getMessage()); }
        return new CleanupResult(deleted, failures);
    }
    private static boolean containsSymlink(Path path) {
        for (Path current = path; current != null; current = current.getParent()) if (Files.isSymbolicLink(current)) return true;
        return false;
    }
    private static boolean ownedAndCompleted(Path session) throws IOException {
        if (!session.getFileName().toString().matches("session-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || !Files.isDirectory(session, LinkOption.NOFOLLOW_LINKS) || containsSymlink(session)) return false;
        try (var files = Files.walk(session)) { if (files.anyMatch(Files::isSymbolicLink)) return false; }
        Path marker = session.resolve("session.json"), report = session.resolve("reports/performance-report.json");
        if (!Files.isRegularFile(marker) || !Files.isRegularFile(report)) return false;
        try {
            var owner = JSON.readTree(marker.toFile());
            var result = JSON.readTree(report.toFile());
            return "agent-monitor".equals(owner.path("application").asText())
                    && session.getFileName().toString().equals("session-" + owner.path("sessionId").asText())
                    && "COMPLETED".equals(result.path("status").asText())
                    && result.path("captureComplete").asBoolean(false);
        } catch (IOException ignored) { return false; }
    }
}
