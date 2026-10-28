package com.agentmonitor.core.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmServiceClasspathScanTest {
    @TempDir Path directory;

    @Test void scansDirectAndNestedJarEntriesWithoutLosingMethodNames() throws Exception {
        byte[] classBytes;
        try (var input = getClass().getResourceAsStream("JvmServiceClasspathScanTest.class")) {
            assertNotNull(input);
            classBytes = input.readAllBytes();
        }
        byte[] nested = jar("fixture/Nested.class", classBytes);
        Path outer = directory.resolve("application.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(outer))) {
            write(output, "BOOT-INF/classes/fixture/Direct.class", classBytes);
            write(output, "BOOT-INF/lib/dependency.jar", nested);
        }

        var classes = new LinkedHashSet<String>();
        JvmService.scanClassJar(outer, classes);
        assertEquals(new LinkedHashSet<>(List.of("fixture.Direct", "fixture.Nested")), classes);

        var methods = new LinkedHashMap<String, List<String>>();
        JvmService.scanMethodJar(outer, methods);
        assertEquals(classes, methods.keySet());
        assertTrue(methods.get("fixture.Direct").contains("scansDirectAndNestedJarEntriesWithoutLosingMethodNames"));
        assertEquals(methods.get("fixture.Direct"), methods.get("fixture.Nested"));
    }

    private static byte[] jar(String name, byte[] value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(bytes)) { write(output, name, value); }
        return bytes.toByteArray();
    }

    private static void write(JarOutputStream output, String name, byte[] value) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(value);
        output.closeEntry();
    }
}
