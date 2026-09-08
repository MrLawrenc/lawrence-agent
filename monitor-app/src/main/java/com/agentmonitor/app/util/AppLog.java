package com.agentmonitor.app.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AppLog {

    private static final String LOG_FILE_NAME = "agent-monitor.log";
    private static final String LOG_DIRECTORY_PROPERTY = "agent.monitor.app.logDir";
    private static final Path FALLBACK_LOG_FILE = Path.of("build", "logs", LOG_FILE_NAME).toAbsolutePath().normalize();
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Path APPLICATION_LOG_FILE = applicationLogFile();
    private static boolean installed = false;

    private AppLog() {
    }

    public static Path file() {
        return APPLICATION_LOG_FILE;
    }

    public static void installUncaughtHandler() {
        if (installed) return;
        installed = true;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                error("uncaught exception in " + thread.getName(), error));
        info("========== agent-monitor application log initialized ==========");
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void warn(String message) {
        write("WARN", message);
    }

    public static void error(String message) {
        write("ERROR", message);
    }

    public static void error(String message, Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        write("ERROR", message + System.lineSeparator() + writer);
    }

    private static synchronized void write(String level, String message) {
        try {
            Path activeFile = file();
            Files.createDirectories(activeFile.getParent());
            String line = FORMATTER.format(LocalDateTime.now()) + " [" + level + "] " + message + System.lineSeparator();
            Files.writeString(activeFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static Path applicationLogFile() {
        try {
            String configuredDirectory = System.getProperty(LOG_DIRECTORY_PROPERTY, "").trim();
            Path directory = configuredDirectory == null || configuredDirectory.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".agent-monitor", "logs")
                    : UserPath.resolve(configuredDirectory, FALLBACK_LOG_FILE.getParent());
            return directory.toAbsolutePath().normalize().resolve(LOG_FILE_NAME);
        } catch (Exception ignored) {
            return FALLBACK_LOG_FILE;
        }
    }
}
