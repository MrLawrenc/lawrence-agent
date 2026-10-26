package com.agentmonitor.app.service;
import com.agentmonitor.app.util.AtomicFiles;
import com.agentmonitor.app.util.UserPath;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
/** Cache identity includes process start time, so PID reuse never inherits another JVM's classes. */
public final class JvmClassIndexCache {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final String identity;
    public JvmClassIndexCache(String pid) {
        String started;
        try { started = ProcessHandle.of(Long.parseLong(pid)).flatMap(p -> p.info().startInstant()).map(Object::toString).orElse(""); }
        catch (Exception ignored) { started = ""; }
        identity = pid + "@" + started;
        Path directory = UserPath.resolve(System.getProperty("agent.monitor.cacheDir"), Path.of(System.getProperty("user.home"), ".agent-monitor", "cache"));
        file = started.isEmpty() ? null : directory.resolve(pid + "-" + started.replaceAll("[^0-9]", "") + ".json");
    }
    private JsonNode read() {
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) > 64L * 1024 * 1024) return JSON.createObjectNode();
            JsonNode document = JSON.readTree(file.toFile());
            return identity.equals(document.path("identity").asText()) ? document : JSON.createObjectNode();
        } catch (Exception ignored) { return JSON.createObjectNode(); }
    }
    public synchronized List<String> readClasses() {
        List<String> values = new ArrayList<>();
        read().path("classes").forEach(n -> { if (n.isTextual()) values.add(n.asText()); });
        return List.copyOf(values);
    }
    public synchronized Map<String, List<String>> readMethods() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        read().path("methods").fields().forEachRemaining(entry -> {
            List<String> methods = new ArrayList<>(); entry.getValue().forEach(n -> { if (n.isTextual()) methods.add(n.asText()); });
            values.put(entry.getKey(), List.copyOf(methods));
        });
        return Map.copyOf(values);
    }
    public synchronized void writeClasses(List<String> values) { write("classes", values); }
    public synchronized void writeMethods(Map<String, List<String>> values) { write("methods", values); }
    private void write(String key, Object value) {
        if (file == null) return;
        try {
            var document = JSON.createObjectNode(); document.setAll((com.fasterxml.jackson.databind.node.ObjectNode)read());
            document.put("identity", identity); document.set(key, JSON.valueToTree(value));
            AtomicFiles.write(file, JSON.writeValueAsString(document));
        } catch (Exception error) { com.agentmonitor.app.util.AppLog.warn("类索引缓存写入失败: " + error.getMessage()); }
    }
}
