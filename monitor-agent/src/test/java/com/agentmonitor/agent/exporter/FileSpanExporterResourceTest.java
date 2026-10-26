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
    @Test
    void aFlushedFileManifestRetainsUpstreamLossAsIncompleteCapture() throws Exception {
        com.agentmonitor.agent.interceptor.InFlightSpans.startSession();
        Path session = temporaryDirectory.resolve("loss-session");
        FileSpanExporter exporter = new FileSpanExporter(temporaryDirectory.toString(), session.toString(),
                1024, 60000, false, new JsonSpanPayloadSerializer());
        assertTrue(exporter.start());
        exporter.captureQuality(new com.agentmonitor.model.output.ExporterStatistics(true, 10, 4, 0, 2, 0));
        assertTrue(exporter.drain(1000).drained());
        var manifest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(session.resolve("manifest.json").toFile());
        org.junit.jupiter.api.Assertions.assertFalse(manifest.path("captureComplete").asBoolean(true));
        org.junit.jupiter.api.Assertions.assertEquals(4, manifest.path("outputQuality").path("queueDroppedSpans").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(2, manifest.path("outputQuality").path("rejectedDestinations").asInt());
    }

}
