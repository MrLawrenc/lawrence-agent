package com.agentmonitor.agent.resource;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import com.agentmonitor.agent.config.AgentConfig;

/** Collects session-stable identity for the JVM being observed, never the monitoring App. */
public final class AgentResource {

    private static final String AGENT_NAME = "com.agentmonitor.java-agent";

    private AgentResource() { }

    public static Map<String, String> detect(AgentConfig config) {
        Map<String, String> attributes = new LinkedHashMap<>();
        String command = firstCommandToken(System.getProperty("sun.java.command", ""));
        String host = hostName();
        long pid = ProcessHandle.current().pid();

        put(attributes, "agent.monitor.session.id", config.getCaptureSessionId());
        put(attributes, "service.name", firstNonBlank(config.getServiceName(),
                System.getProperty("otel.service.name"), System.getenv("OTEL_SERVICE_NAME"),
                command.isBlank() ? "unknown_service:java" : command));
        put(attributes, "service.version", firstNonBlank(config.getServiceVersion(),
                System.getProperty("otel.service.version"), System.getenv("OTEL_SERVICE_VERSION")));
        put(attributes, "deployment.environment.name", firstNonBlank(config.getDeploymentEnvironment(),
                System.getProperty("otel.deployment.environment")));
        put(attributes, "service.instance.id", pid + "@" + (host.isBlank() ? "unknown-host" : host));
        put(attributes, "process.pid", String.valueOf(pid));
        put(attributes, "process.command", command);
        put(attributes, "process.runtime.name", System.getProperty("java.runtime.name"));
        put(attributes, "process.runtime.version", System.getProperty("java.runtime.version"));
        put(attributes, "host.name", host);
        put(attributes,"os.name",System.getProperty("os.name"));put(attributes,"os.version",System.getProperty("os.version"));put(attributes,"host.arch",System.getProperty("os.arch"));
        put(attributes,"host.available_processors",Integer.toString(Runtime.getRuntime().availableProcessors()));
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");String args=String.join("\n",ManagementFactory.getRuntimeMXBean().getInputArguments());put(attributes,"process.runtime.arguments.sha256",java.util.HexFormat.of().formatHex(digest.digest(args.getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(Exception ignored){}
        put(attributes, "telemetry.sdk.name", AGENT_NAME);
        put(attributes, "telemetry.sdk.language", "java");
        put(attributes, "telemetry.sdk.version", implementationVersion());
        put(attributes, "telemetry.distro.name", "agent-monitor");
        put(attributes, "telemetry.distro.version", implementationVersion());
        return Map.copyOf(attributes);
    }

    private static String implementationVersion() {
        Package pkg = AgentResource.class.getPackage();
        String version = pkg == null ? "" : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int delimiter = runtimeName.indexOf('@');
            return delimiter < 0 ? "" : runtimeName.substring(delimiter + 1);
        }
    }

    private static String firstCommandToken(String command) {
        if (command == null || command.isBlank()) return "";
        String trimmed = command.trim();
        int delimiter = trimmed.indexOf(' ');
        return delimiter < 0 ? trimmed : trimmed.substring(0, delimiter);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        return "";
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value.trim());
    }
}
