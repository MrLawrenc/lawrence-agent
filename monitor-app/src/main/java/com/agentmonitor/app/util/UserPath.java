package com.agentmonitor.app.util;
import java.nio.file.Path;
public final class UserPath {
    private UserPath() { }
    public static Path resolve(String value, Path fallback) {
        if (value == null || value.isBlank()) return fallback.toAbsolutePath().normalize();
        String text = value.trim();
        if (text.equals("~")) text = System.getProperty("user.home");
        else if (text.startsWith("~/") || text.startsWith("~\\")) text = System.getProperty("user.home") + text.substring(1);
        return Path.of(text).toAbsolutePath().normalize();
    }
}
