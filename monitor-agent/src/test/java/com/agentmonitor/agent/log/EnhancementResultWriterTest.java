package com.agentmonitor.agent.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnhancementResultWriterTest {

    @TempDir
    Path directory;

    @Test
    void writesAnAtomicStructuredAttachResult() throws Exception {
        EnhancementResultWriter writer = new EnhancementResultWriter(directory.toString());
        writer.transformed("com.example.OrderService", "business", true,
                List.of("createOrder", "findOrder"));
        writer.failed("com.example.BrokenService", "business", "IllegalStateException: bad \"bytecode\"");

        writer.complete();

        Path result = directory.resolve(EnhancementResultWriter.FILE_NAME);
        String json = Files.readString(result);
        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"status\":\"completed\""));
        assertTrue(json.contains("\"transformedClasses\":1"));
        assertTrue(json.contains("\"failedClasses\":1"));
        assertTrue(json.contains("\"className\":\"com.example.OrderService\""));
        assertTrue(json.contains("\"methodCount\":2"));
        assertTrue(json.contains("\"methodNames\":[\"createOrder\",\"findOrder\"]"));
        assertTrue(json.contains("bad \\\"bytecode\\\""));
        assertFalse(Files.exists(directory.resolve(EnhancementResultWriter.FILE_NAME + ".tmp")));
    }
}
