package com.agentmonitor.agent.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

/** Exercises the packaged Agent in a separate JVM instead of only unit-testing advice methods. */
class AgentClassLoadingIntegrationTest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);

    @Test
    void isolatedApplicationLoaderSeesOnlyBootstrapBridgeAndStillProducesASpan() throws Exception {
        Path agentJar = Path.of("build", "libs", "monitor-agent.jar").toAbsolutePath().normalize();
        Path testClasses = Path.of("build", "classes", "java", "test").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(agentJar), "agentJar task must create the thin attach artifact");
        assertTrue(Files.isDirectory(testClasses), "test class output must exist for the child JVM");
        assertThinOuterArtifact(agentJar);

        Path output = Files.createTempDirectory("agent-monitor-classloader-test-");
        Path session = output.resolve("session");
        String agentArgs = "pkg=agentfixtures.,exporters=file,fileDir=" + output
                + ",fileSessionDir=" + session
                + ",captureArgs=false,captureReturnValue=false,samplingRate=100";
        String childOutput = runProbe(agentJar, testClasses, List.of(agentArgs));
        assertTrue(childOutput.contains("bridge-loader=bootstrap"), childOutput);
        assertTrue(childOutput.contains("core-hidden"), childOutput);
        assertTrue(childOutput.contains("system-core-hidden"), childOutput);
        assertTrue(childOutput.contains("business-result=processed:ok"), childOutput);

        List<Path> spanFiles;
        try (var paths = Files.walk(session)) {
            spanFiles = paths.filter(path -> path.getFileName().toString().startsWith("spans-")
                            && path.getFileName().toString().endsWith(".json"))
                    .toList();
        }
        assertFalse(spanFiles.isEmpty(), "the isolated business method should be instrumented");
        assertTrue(Files.readString(spanFiles.get(0)).contains("agentfixtures.isolated.IsolatedBusiness"));
    }

    @Test
    void repeatedAgentStartsReplaceTheOldIsolatedCoreWithoutLinkageErrors() throws Exception {
        Path agentJar = Path.of("build", "libs", "monitor-agent.jar").toAbsolutePath().normalize();
        Path testClasses = Path.of("build", "classes", "java", "test").toAbsolutePath().normalize();
        Path output = Files.createTempDirectory("agent-monitor-reattach-test-");
        Path firstSession = output.resolve("first");
        Path secondSession = output.resolve("second");
        String firstArgs = "pkg=ignored.,exporters=file,fileDir=" + output
                + ",fileSessionDir=" + firstSession + ",samplingRate=100";
        String secondArgs = "pkg=agentfixtures.,exporters=file,fileDir=" + output
                + ",fileSessionDir=" + secondSession + ",samplingRate=100";

        String childOutput = runProbe(agentJar, testClasses, List.of(firstArgs, secondArgs));

        assertFalse(childOutput.contains("LinkageError"), childOutput);
        assertTrue(childOutput.contains("business-result=processed:ok"), childOutput);
        try (var paths = Files.walk(secondSession)) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith("spans-")), childOutput);
        }
    }

    private static void assertThinOuterArtifact(Path agentJar) throws Exception {
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            assertTrue(jar.getJarEntry("agent/monitor-bootstrap-bridge.jar") != null);
            assertTrue(jar.getJarEntry("agent/monitor-agent-core.jar") != null);
            assertTrue(jar.getJarEntry("com/agentmonitor/bootstrap/entry/AgentBootstrap.class") != null);
            assertTrue(jar.getJarEntry("com/agentmonitor/agent/AgentMain.class") == null);
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("net/bytebuddy/")));
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("io/netty/")));
        }
    }

    private static String runProbe(Path agentJar, Path testClasses, List<String> agentArguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String arguments : agentArguments) command.add("-javaagent:" + agentJar + '=' + arguments);
        command.add("-cp");
        command.add(testClasses.toString());
        command.add(AgentPremainProbe.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String childOutput = readFully(process.getInputStream());
        assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                () -> "child JVM timed out:\n" + childOutput);
        assertEquals(0, process.exitValue(), () -> "child JVM failed:\n" + childOutput);
        return childOutput;
    }

    private static String readFully(InputStream input) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
