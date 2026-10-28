package com.agentmonitor.core.util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public final class AtomicFiles {
    private AtomicFiles() { }
    public static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".agent-monitor-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
