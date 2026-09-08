package com.agentmonitor.agent.exporter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentmonitor.agent.protocol.JsonSpanPayloadSerializer;

class FileSpanExporterResourceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void manifestCarriesTheObservedJvmAndAgentIdentity() throws Exception {
        Path session = temporaryDirectory.resolve("session");
        FileSpanExporter exporter = new FileSpanExporter(temporaryDirectory.toString(), session.toString(),
                1_024, 60_000, false, new JsonSpanPayloadSerializer(), Map.of(
                        "service.name", "orders",
                        "process.pid", "123",
                        "telemetry.sdk.name", "com.agentmonitor.java-agent"));

        assertTrue(exporter.start());
        String manifest = Files.readString(session.resolve("manifest.json"));

        assertTrue(manifest.contains("\"resource\""));
        assertTrue(manifest.contains("\"service.name\":\"orders\""));
        assertTrue(manifest.contains("\"telemetry.sdk.name\":\"com.agentmonitor.java-agent\""));
        exporter.drain(0);
    }
}
