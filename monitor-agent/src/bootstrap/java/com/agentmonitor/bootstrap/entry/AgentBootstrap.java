package com.agentmonitor.bootstrap.entry;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Thin JVM Agent entry point.  It intentionally has no dependency on Agent core, Byte Buddy,
 * Netty or the Bootstrap bridge.  JVM attach loads this class through the system loader; core is
 * subsequently loaded from the nested runtime JAR through an agent-first class loader.
 */
public final class AgentBootstrap {

    private static final String BRIDGE_ENTRY = "agent/monitor-bootstrap-bridge.jar";
    private static final String CORE_ENTRY = "agent/monitor-agent-core.jar";
    private static final String CORE_MAIN_CLASS = "com.agentmonitor.agent.AgentMain";
    private static final String BRIDGE_CLASS = "com.agentmonitor.bootstrap.bridge.BootstrapBridge";
    private static final String STARTUP_CLASS = BRIDGE_CLASS + "$Startup";
    private static final int BRIDGE_API_VERSION = 4;
    private static final String CONFIG_FILE_PREFIX = "cfg=";
    private static final String AGENT_JAR_PROPERTY = "jar";
    private static final String RUNTIME_DIRECTORY = "agent-monitor/runtime";

    /** Bootstrap resolves classes from appended JARs lazily, so their JarFiles must stay open. */
    private static final List<JarFile> BOOTSTRAP_BRIDGE_JARS = new CopyOnWriteArrayList<>();
    private static final Set<Path> APPENDED_BRIDGE_PATHS = ConcurrentHashMap.newKeySet();

    private AgentBootstrap() { }

    public static void premain(String agentArgs, Instrumentation instrumentation) throws Exception {
        start(agentArgs, instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) throws Exception {
        start(agentArgs, instrumentation);
    }

    private static void start(String agentArgs, Instrumentation instrumentation) throws Exception {
        if (instrumentation == null) throw new IllegalArgumentException("Instrumentation is required");
        Path outerAgentJar = resolveOuterAgentJar(agentArgs);
        Path bridgeJar = extractNestedJar(outerAgentJar, BRIDGE_ENTRY, "bootstrap-bridge");
        appendBridge(instrumentation, bridgeJar);
        BridgeStartup startup = beginStartup();

        AgentRuntimeClassLoader runtimeLoader = null;
        boolean handedOff = false;
        Thread thread = Thread.currentThread();
        ClassLoader previousContextLoader = thread.getContextClassLoader();
        try {
            Path coreJar = extractNestedJar(outerAgentJar, CORE_ENTRY, "agent-core");
            runtimeLoader = new AgentRuntimeClassLoader(
                    new URL[] { coreJar.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
            thread.setContextClassLoader(runtimeLoader);
            Class<?> runtimeMain = Class.forName(CORE_MAIN_CLASS, true, runtimeLoader);
            Method agentmain = runtimeMain.getMethod("agentmain", String.class, Instrumentation.class,
                    startup.startupClass());
            agentmain.invoke(null, agentArgs, instrumentation, startup.ticket());
            // A successful core invocation owns its loader through the Bootstrap bridge until detach.
            handedOff = true;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (!abortStartup(startup, cause)) handedOff = true;
            throw rethrow(cause);
        } catch (Throwable error) {
            if (!abortStartup(startup, error)) handedOff = true;
            throw rethrow(error);
        } finally {
            thread.setContextClassLoader(previousContextLoader);
            if (!handedOff && runtimeLoader != null) runtimeLoader.close();
        }
    }

    /** Uses reflection so the thin system-loader entry never links against the Bootstrap bridge. */
    private static BridgeStartup beginStartup() throws Exception {
        Class<?> bridge = Class.forName(BRIDGE_CLASS, true, null);
        Class<?> startupClass = Class.forName(STARTUP_CLASS, true, null);
        try {
            Object ticket = bridge.getMethod("beginStartup").invoke(null);
            return new BridgeStartup(startupClass, ticket);
        } catch (InvocationTargetException error) {
            throw rethrow(error.getCause());
        }
    }

    /** Returns false only when the Bridge still owns a failed core and its loader must stay open. */
    private static boolean abortStartup(BridgeStartup startup, Throwable originalFailure) {
        try {
            Class<?> bridge = Class.forName(BRIDGE_CLASS, true, null);
            Object status = bridge.getMethod("abortStartup", startup.startupClass()).invoke(null, startup.ticket());
            Object restored = status.getClass().getMethod("restored").invoke(status);
            if (Boolean.TRUE.equals(restored)) return true;
            Object message = status.getClass().getMethod("message").invoke(status);
            originalFailure.addSuppressed(new IllegalStateException(
                    "Agent core remains registered for a later restore retry: " + message));
            return false;
        } catch (Throwable abortFailure) {
            originalFailure.addSuppressed(abortFailure);
            return false;
        }
    }

    private static Path resolveOuterAgentJar(String agentArgs) throws Exception {
        String configured = jarFromConfig(agentArgs);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) return path;
            throw new IllegalStateException("configured Agent JAR does not exist: " + path);
        }
        URL location = AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        URI uri = location.toURI();
        Path path = Path.of(uri).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Agent entry is not packaged as a JAR: " + path);
        }
        return path;
    }

    private static String jarFromConfig(String agentArgs) {
        if (agentArgs == null || agentArgs.isBlank()) return "";
        String arguments = agentArgs.trim();
        if (arguments.startsWith(CONFIG_FILE_PREFIX)) {
            String configPath = arguments.substring(CONFIG_FILE_PREFIX.length()).trim();
            try (InputStream input = Files.newInputStream(Path.of(configPath))) {
                Properties properties = new Properties();
                properties.load(input);
                String configuredJar = properties.getProperty(AGENT_JAR_PROPERTY, "").trim();
                if (configuredJar.isBlank()) {
                    throw new IllegalStateException("Agent configuration does not contain a JAR path: " + configPath);
                }
                return configuredJar;
            } catch (Exception error) {
                throw new IllegalStateException("cannot read Agent configuration: " + configPath, error);
            }
        }
        for (String argument : arguments.split(",")) {
            String[] pair = argument.split("=", 2);
            if (pair.length == 2 && AGENT_JAR_PROPERTY.equals(pair[0].trim())) return pair[1].trim();
        }
        return "";
    }

    private static Path extractNestedJar(Path outerAgentJar, String entryName, String label) throws Exception {
        byte[] content;
        try (JarFile outer = new JarFile(outerAgentJar.toFile())) {
            JarEntry entry = outer.getJarEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException("Agent artifact is missing " + entryName + ": " + outerAgentJar);
            }
            try (InputStream input = outer.getInputStream(entry)) {
                content = input.readAllBytes();
            }
        }
        return writeImmutableRuntimeJar(label, content);
    }

    /** Stores nested artifacts by content hash and never overwrites a JAR already in use. */
    private static Path writeImmutableRuntimeJar(String label, byte[] content) throws Exception {
        String digest = sha256(content);
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), RUNTIME_DIRECTORY)
                .toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve(label + '-' + digest + ".jar");
        if (Files.isRegularFile(target)) return verifyRuntimeJar(target, digest);

        Path temporary = Files.createTempFile(directory, label + '-', ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return verifyRuntimeJar(target, digest);
    }

    private static Path verifyRuntimeJar(Path target, String expectedDigest) throws Exception {
        String actualDigest = sha256(Files.readAllBytes(target));
        if (!expectedDigest.equals(actualDigest)) {
            throw new IllegalStateException("content-addressed Agent runtime is corrupt: " + target);
        }
        return target;
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException ignored) {
            // Another simultaneous attach materialized the same content-addressed artifact.
        } catch (AtomicMoveNotSupportedException ignored) {
            try {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException alreadyPresent) {
                // Another simultaneous attach materialized the same content-addressed artifact.
            }
        }
    }

    private static void appendBridge(Instrumentation instrumentation, Path bridgeJar) throws IOException {
        synchronized (BOOTSTRAP_BRIDGE_JARS) {
            Integer loadedVersion = loadedBootstrapBridgeVersion();
            if (loadedVersion != null) {
                if (loadedVersion != BRIDGE_API_VERSION) {
                    throw new IllegalStateException("incompatible Bootstrap bridge API: " + loadedVersion);
                }
                return;
            }

            Path normalized = bridgeJar.toAbsolutePath().normalize();
            if (!APPENDED_BRIDGE_PATHS.add(normalized)) return;
            JarFile jarFile = null;
            try {
                jarFile = new JarFile(normalized.toFile());
                instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
                BOOTSTRAP_BRIDGE_JARS.add(jarFile);
            } catch (IOException | RuntimeException error) {
                APPENDED_BRIDGE_PATHS.remove(normalized);
                if (jarFile != null) jarFile.close();
                throw error;
            }
        }
    }

    private static Integer loadedBootstrapBridgeVersion() {
        try {
            Class<?> bridge = Class.forName(BRIDGE_CLASS, false, null);
            Object version = bridge.getMethod("apiVersion").invoke(null);
            return version instanceof Integer value ? value : null;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot inspect Bootstrap bridge", error);
        }
    }

    private static Exception rethrow(Throwable cause) throws Exception {
        if (cause instanceof Exception exception) return exception;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("Agent core failed", cause);
    }

    private record BridgeStartup(Class<?> startupClass, Object ticket) { }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /**
     * Mirrors Arthas' agent-first core loader.  It delegates Java platform and bridge classes to
     * its parent, but loads every Agent/runtime dependency from the private core JAR first.
     */
    private static final class AgentRuntimeClassLoader extends URLClassLoader {

        static {
            registerAsParallelCapable();
        }

        private AgentRuntimeClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (isParentFirst(name)) {
                        loaded = getParent().loadClass(name);
                    } else {
                        try {
                            loaded = findClass(name);
                        } catch (ClassNotFoundException ignored) {
                            loaded = getParent().loadClass(name);
                        }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private static boolean isParentFirst(String name) {
            return name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("jdk.")
                    || name.startsWith("sun.")
                    || name.startsWith("com.sun.")
                    || name.startsWith("com.agentmonitor.bootstrap.bridge.");
        }
    }
}
