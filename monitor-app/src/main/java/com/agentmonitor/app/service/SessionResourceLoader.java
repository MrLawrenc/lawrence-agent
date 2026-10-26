package com.agentmonitor.app.service;
import com.agentmonitor.app.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.LinkedHashMap;
public final class SessionResourceLoader {
    public record Result(SessionResource resource, String failureDetail) { public boolean loaded() { return resource != null; } }
    public Result load(MonitoringSession session) {
        String detail = "会话不存在";
        if (session == null) return new Result(null, detail);
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                var node = new ObjectMapper().readTree(Files.readString(session.resourcePath()));
                if (!node.path("attributes").isObject()) throw new IllegalArgumentException("Resource attributes 不是对象");
                var attributes = new LinkedHashMap<String, String>();
                node.path("attributes").fields().forEachRemaining(e -> { if (e.getValue().isTextual()) attributes.put(e.getKey(), e.getValue().asText()); });
                return new Result(new SessionResource(attributes), "");
            } catch (Exception error) { detail = error.getMessage(); }
            try { Thread.sleep(100); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
        }
        return new Result(null, detail);
    }
}
