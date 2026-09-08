package com.agentmonitor.agent.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Session-scoped Agent diagnostics. Logging must never make instrumentation fail. */
public final class AgentLog {

    private static final String LOG_FILE_NAME = "agent-monitor.log";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int FLUSH_BATCH_SIZE = 64;
    private static final long FLUSH_INTERVAL_MILLIS = 1_000;
    private static final Object LOCK = new Object();
    private static BufferedWriter writer;
    private static int pendingLines;
    private static long lastFlushAt;

    private AgentLog() { }

    public static void configure(String configuredDirectory) {
        synchronized (LOCK) {
            closeWriter();
            if (configuredDirectory == null || configuredDirectory.isBlank()) return;
            try {
                Path directory = Path.of(configuredDirectory).toAbsolutePath().normalize();
                Files.createDirectories(directory);
                writer = Files.newBufferedWriter(directory.resolve(LOG_FILE_NAME), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                pendingLines = 0;
                lastFlushAt = System.currentTimeMillis();
            } catch (IOException ignored) {
                writer = null;
            }
        }
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void error(String message) {
        write("ERROR", message);
    }

    public static void warn(String message) {
        write("WARN", message);
    }

    public static void close() {
        synchronized (LOCK) {
            closeWriter();
        }
    }

    private static void write(String level, String message) {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writer.write(TIMESTAMP.format(LocalDateTime.now()));
                writer.write(" [");
                writer.write(level);
                writer.write("] ");
                writer.write(message == null ? "" : message);
                writer.newLine();
                pendingLines++;
                long now = System.currentTimeMillis();
                if ("ERROR".equals(level) || pendingLines >= FLUSH_BATCH_SIZE
                        || now - lastFlushAt >= FLUSH_INTERVAL_MILLIS) {
                    writer.flush();
                    pendingLines = 0;
                    lastFlushAt = now;
                }
            } catch (IOException ignored) {
                closeWriter();
            }
        }
    }

    private static void closeWriter() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // Diagnostics must not affect target application execution.
        } finally {
            writer = null;
            pendingLines = 0;
        }
    }
}
