package com.agentmonitor.agent.log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the completed attach-time bytecode enhancement result as a JSON document. */
public final class EnhancementResultWriter {

    public static final String FILE_NAME = "enhancement-result.json";

    private final Path output;
    private final Map<String, Result> results = new LinkedHashMap<>();

    public EnhancementResultWriter(String directory) {
        output = directory == null || directory.isBlank() ? null
                : Path.of(directory).toAbsolutePath().normalize().resolve(FILE_NAME);
    }

    public synchronized void transformed(String className, String category, boolean retransformed,
                                         List<String> methodNames) {
        List<String> names = methodNames == null ? List.of() : List.copyOf(methodNames);
        results.put(className, new Result(className, category, "transformed",
                retransformed ? "retransformed" : "loaded-after-attach", names, ""));
    }

    public synchronized void failed(String className, String category, String reason) {
        results.put(className, new Result(className, category, "failed", "", List.of(), reason));
    }

    /** Publishes an atomic snapshot once the attach-time retransformation has completed. */
    public synchronized void complete() {
        if (output == null) return;
        int transformed = 0;
        int failed = 0;
        for (Result result : results.values()) {
            if ("transformed".equals(result.status)) transformed++;
            else if ("failed".equals(result.status)) failed++;
        }
        StringBuilder json = new StringBuilder(256 + results.size() * 160);
        json.append("{\"schemaVersion\":1,\"status\":\"completed\",\"generatedAt\":\"")
                .append(escape(Instant.now().toString())).append("\",\"summary\":{\"transformedClasses\":")
                .append(transformed).append(",\"failedClasses\":").append(failed)
                .append("},\"classes\":[");
        boolean first = true;
        for (Result result : results.values()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"className\":\"").append(escape(result.className))
                    .append("\",\"category\":\"").append(escape(result.category))
                    .append("\",\"status\":\"").append(result.status).append('"');
            if (!result.trigger.isEmpty()) {
                json.append(",\"trigger\":\"").append(result.trigger).append('"');
            }
            json.append(",\"methodCount\":").append(result.methodNames.size())
                    .append(",\"methodNames\":[");
            for (int index = 0; index < result.methodNames.size(); index++) {
                if (index > 0) json.append(',');
                json.append('"').append(escape(result.methodNames.get(index))).append('"');
            }
            json.append(']');
            if (!result.reason.isEmpty()) {
                json.append(",\"reason\":\"").append(escape(result.reason)).append('"');
            }
            json.append('}');
        }
        json.append("]}");
        writeAtomically(json.toString());
    }

    private void writeAtomically(String json) {
        try {
            Files.createDirectories(output.getParent());
            Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Diagnostics must not affect target application execution.
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }

    private record Result(String className, String category, String status, String trigger,
                          List<String> methodNames, String reason) { }
}
