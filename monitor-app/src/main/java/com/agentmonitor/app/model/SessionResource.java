package com.agentmonitor.app.model;
import java.util.*;
public record SessionResource(Map<String, String> attributes) {
    public SessionResource { attributes = Map.copyOf(attributes); }
    public String value(String key) { return attributes.getOrDefault(key, ""); }
    public String serviceName() { return value("service.name"); }
    public String serviceVersion() { return value("service.version"); }
    public String processId() { return value("process.pid"); }
    public String environment() { return value("deployment.environment.name").isBlank() ? value("deployment.environment") : value("deployment.environment.name"); }
    public String runtimeDisplayName() { return joinNonBlank(" ", value("process.runtime.name"), value("process.runtime.version")); }
    public static String joinNonBlank(String separator, String... values) {
        return String.join(separator, Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList());
    }
}
