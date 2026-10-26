package com.agentmonitor.app.util;
import java.awt.Desktop;
import java.nio.file.*;
public final class SessionArtifactOpener {
    private SessionArtifactOpener() { }
    public record OpenResult(boolean opened, String message) { }
    public static OpenResult open(Path path) {
        if (path == null || !Files.exists(path)) return new OpenResult(false, "文件尚未生成");
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) return new OpenResult(false, "系统不支持直接打开，请访问 " + path);
            Desktop.getDesktop().open(path.toFile());
            return new OpenResult(true, "已打开 " + path.getFileName());
        } catch (Exception error) { return new OpenResult(false, error.getMessage()); }
    }
}
