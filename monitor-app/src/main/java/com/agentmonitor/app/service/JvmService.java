package com.agentmonitor.app.service;

import java.io.File;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

import com.agentmonitor.app.model.JvmProcess;
import com.agentmonitor.app.model.AgentOutputConfig;
import com.agentmonitor.app.model.MonitoringSession;
import com.agentmonitor.app.util.AppLog;
import com.agentmonitor.model.config.MonitoringConfig;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

public class JvmService {

    private static final int DEFAULT_SAMPLING_RATE_PERCENT = 10;
    private static final long DEFAULT_SLOW_THRESHOLD_MILLIS = 50;

    private static File agentJarFile;

    public static List<JvmProcess> listProcesses() {
        Map<String, JvmProcess> result = new LinkedHashMap<>();
        String selfPid = String.valueOf(ProcessHandle.current().pid());
        addAttachProcesses(result, selfPid);
        addJcmdProcesses(result, selfPid);
        addPsJavaProcesses(result, selfPid);
        AppLog.info("[JvmService] JVM process refresh found=" + result.size());
        result.values().forEach(process -> AppLog.info("[JvmService] JVM process pid="
                + process.getPid() + " name=" + process.getDisplayName()));
        return new ArrayList<>(result.values());
    }

    private static void addAttachProcesses(Map<String, JvmProcess> result, String selfPid) {
        try {
            for (VirtualMachineDescriptor vmd : VirtualMachine.list()) {
                if (vmd.id().equals(selfPid)) continue;
                String name = vmd.displayName();
                if (name == null || name.isBlank()) name = "(unknown)";
                putProcess(result, vmd.id(), name);
            }
        } catch (Exception e) {
            AppLog.warn("[JvmService] attach list error: " + e.getMessage());
        }
    }

    private static void addJcmdProcesses(Map<String, JvmProcess> result, String selfPid) {
        try {
            Process process = new ProcessBuilder("jcmd", "-l")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String text = line.trim();
                    if (text.isEmpty()) continue;
                    int split = text.indexOf(' ');
                    String pid = split < 0 ? text : text.substring(0, split).trim();
                    String name = split < 0 ? "(unknown)" : text.substring(split + 1).trim();
                    if (pid.equals(selfPid) || name.contains("sun.tools.jcmd.JCmd")) continue;
                    putProcess(result, pid, name);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            AppLog.warn("[JvmService] jcmd list error: " + e.getMessage());
        }
    }

    private static void addPsJavaProcesses(Map<String, JvmProcess> result, String selfPid) {
        try {
            Process process = new ProcessBuilder("/bin/ps", "-axo", "pid=,command=")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String text = line.trim();
                    if (text.isEmpty()) continue;
                    int split = text.indexOf(' ');
                    if (split <= 0) continue;
                    String pid = text.substring(0, split).trim();
                    String command = text.substring(split + 1).trim();
                    if (pid.equals(selfPid)) continue;
                    if (!isJavaCommand(command)) continue;
                    if (command.contains("sun.tools.jcmd.JCmd") || command.contains("sun.tools.jps.Jps")) continue;
                    if (command.contains("com.agentmonitor.app.MainApp")) continue;
                    putProcess(result, pid, displayCommand(command));
                }
            }
            process.waitFor();
        } catch (Exception e) {
            AppLog.warn("[JvmService] ps java list error: " + e.getMessage());
        }
    }

    private static void putProcess(Map<String, JvmProcess> result, String pid, String displayName) {
        if (pid == null || pid.isBlank()) return;
        result.putIfAbsent(pid, new JvmProcess(pid, displayName == null || displayName.isBlank()
                ? "(unknown)"
                : displayName));
    }

    private static boolean isJavaCommand(String command) {
        String value = command == null ? "" : command.toLowerCase();
        return value.contains("/java ")
                || value.endsWith("/java")
                || value.startsWith("java ")
                || value.contains("java -")
                || value.contains("jdk/bin/java")
                || value.contains(".app/contents/home/bin/java");
    }

    private static String compactCommand(String command) {
        if (command.length() <= 1200) return command;
        return command.substring(0, 1180) + " ...";
    }

    private static String displayCommand(String command) {
        String main = extractMainCommand(command);
        String compact = compactCommand(command);
        if (main.isBlank() || compact.startsWith(main)) return compact;
        return main + "  |  " + compact;
    }

    private static String extractMainCommand(String command) {
        String jar = extractOptionValue(command, "-jar");
        if (!jar.isBlank()) return jar;
        String mainClass = extractMainClassAfterClasspath(command, "-cp");
        if (!mainClass.isBlank()) return mainClass;
        return extractMainClassAfterClasspath(command, "-classpath");
    }

    private static String extractOptionValue(String command, String option) {
        int index = command.indexOf(" " + option + " ");
        if (index < 0 && command.startsWith(option + " ")) index = -1;
        if (index < 0) return "";
        int valueStart = index + option.length() + 2;
        int valueEnd = command.indexOf(' ', valueStart);
        return valueEnd < 0 ? command.substring(valueStart).trim() : command.substring(valueStart, valueEnd).trim();
    }

    private static String extractMainClassAfterClasspath(String command, String option) {
        int index = command.indexOf(" " + option + " ");
        if (index < 0 && command.startsWith(option + " ")) index = -1;
        if (index < 0) return "";
        int classpathStart = index + option.length() + 2;
        int classpathEnd = command.indexOf(' ', classpathStart);
        if (classpathEnd < 0) return "";
        int mainStart = classpathEnd + 1;
        while (mainStart < command.length() && command.charAt(mainStart) == ' ') mainStart++;
        int mainEnd = command.indexOf(' ', mainStart);
        String main = mainEnd < 0 ? command.substring(mainStart).trim() : command.substring(mainStart, mainEnd).trim();
        return main.startsWith("-") ? "" : main;
    }

    public static List<String> listLoadedClasses(String pid) {
        Set<String> classes = new LinkedHashSet<>();
        classes.addAll(listClasspathClasses(pid));
        try {
            Process process = new ProcessBuilder("jcmd", pid, "GC.class_histogram")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String className = parseHistogramClassName(line);
                    if (className == null) continue;
                    if (isNoiseClass(className)) continue;
                    classes.add(className);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            AppLog.warn("[JvmService] class histogram error: " + e.getMessage());
        }
        return new ArrayList<>(classes);
    }

    public static Map<String, List<String>> listClassMethods(String pid) {
        Map<String, List<String>> methods = new LinkedHashMap<>();
        TargetRuntime runtime = readTargetRuntime(pid);
        AppLog.info("[JvmService] scan method index user.dir=" + runtime.workingDirectory());
        for (String entry : runtime.classpathEntries()) {
            if (entry == null || entry.isBlank()) continue;
            List<Path> paths;
            try {
                paths = resolveClasspathEntry(entry, runtime.workingDirectory());
            } catch (Exception e) {
                AppLog.warn("[JvmService] method classpath resolve skipped " + entry + ": " + e.getMessage());
                continue;
            }
            for (Path path : paths) {
                if (shouldSkipClasspathPath(path, runtime.workingDirectory())) continue;
                try {
                    int before = methods.size();
                    if (Files.isDirectory(path)) {
                        scanMethodDirectory(path, methods);
                    } else if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                        scanMethodJar(path, methods);
                    }
                    int added = methods.size() - before;
                    if (added > 0) {
                        AppLog.info("[JvmService] indexed methods " + path + " +" + added + " classes");
                    }
                } catch (Exception e) {
                    AppLog.warn("[JvmService] method scan skipped " + path + ": " + e.getMessage());
                }
            }
        }
        return methods;
    }

    private static List<String> listClasspathClasses(String pid) {
        Set<String> classes = new LinkedHashSet<>();
        TargetRuntime runtime = readTargetRuntime(pid);
        AppLog.info("[JvmService] scan runtime user.dir=" + runtime.workingDirectory());
        AppLog.info("[JvmService] scan classpath entries=" + runtime.classpathEntries().size());
        for (String entry : runtime.classpathEntries()) {
            if (entry == null || entry.isBlank()) continue;
            List<Path> paths;
            try {
                paths = resolveClasspathEntry(entry, runtime.workingDirectory());
            } catch (Exception e) {
                AppLog.warn("[JvmService] classpath resolve skipped " + entry + ": " + e.getMessage());
                continue;
            }
            for (Path path : paths) {
                if (shouldSkipClasspathPath(path, runtime.workingDirectory())) continue;
                try {
                    int before = classes.size();
                    if (Files.isDirectory(path)) {
                        scanClassDirectory(path, classes);
                    } else if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                        scanClassJar(path, classes);
                    }
                    int added = classes.size() - before;
                    if (added > 0) {
                        AppLog.info("[JvmService] scanned " + path + " +" + added + " classes");
                    }
                } catch (Exception e) {
                    AppLog.warn("[JvmService] classpath scan skipped " + path + ": " + e.getMessage());
                }
            }
        }
        return new ArrayList<>(classes);
    }

    private static TargetRuntime readTargetRuntime(String pid) {
        List<String> entries = new ArrayList<>();
        String workingDirectory = "";
        String sunJavaCommand = "";
        String loaderPath = "";
        String loaderHome = "";
        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            try {
                Properties props = vm.getSystemProperties();
                String classpath = props.getProperty("java.class.path", "");
                for (String entry : classpath.split(java.io.File.pathSeparator)) {
                    if (!entry.isBlank()) entries.add(entry);
                }
                workingDirectory = props.getProperty("user.dir", "");
                sunJavaCommand = props.getProperty("sun.java.command", "");
                loaderPath = props.getProperty("loader.path", "");
                loaderHome = props.getProperty("loader.home", "");
            } finally {
                vm.detach();
            }
        } catch (Exception e) {
            AppLog.warn("[JvmService] attach properties read error: " + e.getMessage());
        }
        try {
            Process process = new ProcessBuilder("jcmd", pid, "VM.system_properties")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("java.class.path=")) {
                        String classpath = line.substring("java.class.path=".length());
                        for (String entry : classpath.split(java.io.File.pathSeparator)) {
                            if (!entry.isBlank()) entries.add(entry);
                        }
                    } else if (line.startsWith("user.dir=")) {
                        workingDirectory = line.substring("user.dir=".length()).trim();
                    } else if (line.startsWith("sun.java.command=")) {
                        sunJavaCommand = line.substring("sun.java.command=".length()).trim();
                    } else if (line.startsWith("loader.path=")) {
                        loaderPath = line.substring("loader.path=".length()).trim();
                    } else if (line.startsWith("loader.home=")) {
                        loaderHome = line.substring("loader.home=".length()).trim();
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            AppLog.warn("[JvmService] classpath read error: " + e.getMessage());
        }
        for (String commandJar : commandJars(sunJavaCommand)) entries.add(commandJar);
        entries.addAll(loaderPathEntries(loaderPath));
        entries.addAll(defaultSpringBootLocations(workingDirectory, loaderHome));
        return new TargetRuntime(entries.stream().distinct().toList(), workingDirectory);
    }

    private static List<String> commandJars(String command) {
        if (command == null || command.isBlank()) return List.of();
        List<String> jars = new ArrayList<>();
        for (String token : command.split("\\s+")) {
            String value = token.trim();
            if (value.endsWith(".jar")) jars.add(value);
        }
        return jars;
    }

    private static List<Path> resolveClasspathEntry(String entry, String workingDirectory) throws Exception {
        List<Path> paths = new ArrayList<>();
        Path path = Path.of(entry);
        if (!path.isAbsolute() && workingDirectory != null && !workingDirectory.isBlank()) {
            path = Path.of(workingDirectory).resolve(path).normalize();
        }
        String text = path.toString();
        if (text.endsWith("*")) {
            Path dir = path.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                            .forEach(paths::add);
                }
            }
        } else {
            paths.add(path);
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.list(path)) {
                    stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                            .forEach(paths::add);
                }
            }
        }
        return paths;
    }

    private static boolean shouldSkipClasspathPath(Path path, String workingDirectory) {
        String value = path.toString();
        if (value.contains("/.gradle/caches/")) return true;
        if (value.contains("/.m2/repository/")) return true;
        if (workingDirectory == null || workingDirectory.isBlank()) return false;
        Path work = Path.of(workingDirectory).toAbsolutePath().normalize();
        Path current = path.toAbsolutePath().normalize();
        Path parent = work.getParent();
        return parent != null && !current.startsWith(parent) && !current.startsWith(work);
    }

    private static List<String> loaderPathEntries(String loaderPath) {
        if (loaderPath == null || loaderPath.isBlank()) return List.of();
        List<String> entries = new ArrayList<>();
        for (String part : loaderPath.split(",")) {
            String value = part.trim();
            if (!value.isBlank()) entries.add(value);
        }
        return entries;
    }

    private static List<String> defaultSpringBootLocations(String workingDirectory, String loaderHome) {
        List<String> entries = new ArrayList<>();
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            entries.add(Path.of(workingDirectory, "lib").toString());
            entries.add(Path.of(workingDirectory, "libs").toString());
        }
        if (loaderHome != null && !loaderHome.isBlank()) {
            entries.add(Path.of(loaderHome, "lib").toString());
            entries.add(Path.of(loaderHome, "libs").toString());
        }
        return entries;
    }

    private static void scanClassDirectory(Path root, Set<String> classes) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(name -> name.replace(java.io.File.separatorChar, '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .filter(name -> !name.endsWith("module-info") && !name.endsWith("package-info"))
                    .filter(name -> !isNoiseClass(name))
                    .forEach(classes::add);
        }
    }

    private static void scanMethodDirectory(Path root, Map<String, List<String>> methods) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(file -> Files.isRegularFile(file) && file.toString().endsWith(".class")).toList()) {
                String entryName = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                String className = jarEntryToClassName(entryName);
                addClassMethods(className, Files.readAllBytes(path), methods);
            }
        }
    }

    private static void scanClassJar(Path jarPath, Set<String> classes) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    addJarClassName(name, classes);
                } else if ((name.startsWith("BOOT-INF/lib/") || name.startsWith("lib/")) && name.endsWith(".jar")) {
                    int before = classes.size();
                    scanNestedJar(jar.getInputStream(entry), classes);
                    int added = classes.size() - before;
                    if (added > 0) {
                        AppLog.info("[JvmService] scanned nested " + jarPath.getFileName() + "!" + name + " +" + added + " classes");
                    }
                }
            }
        }
    }

    private static void scanMethodJar(Path jarPath, Map<String, List<String>> methods) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (JarEntry entry : java.util.Collections.list(jar.entries())) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    String className = jarEntryToClassName(name);
                    if (className != null) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            addClassMethods(className, in.readAllBytes(), methods);
                        }
                    }
                } else if ((name.startsWith("BOOT-INF/lib/") || name.startsWith("lib/")) && name.endsWith(".jar")) {
                    scanNestedMethodJar(jar.getInputStream(entry), methods);
                }
            }
        }
    }

    private static void scanNestedJar(InputStream inputStream, Set<String> classes) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        inputStream.transferTo(buffer);
        try (JarInputStream nestedJar = new JarInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            JarEntry entry;
            while ((entry = nestedJar.getNextJarEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    addJarClassName(entry.getName(), classes);
                }
            }
        }
    }

    private static void scanNestedMethodJar(InputStream inputStream, Map<String, List<String>> methods) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        inputStream.transferTo(buffer);
        try (JarInputStream nestedJar = new JarInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            JarEntry entry;
            while ((entry = nestedJar.getNextJarEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    String className = jarEntryToClassName(entry.getName());
                    if (className != null) {
                        addClassMethods(className, nestedJar.readAllBytes(), methods);
                    }
                }
            }
        }
    }

    private static void addClassMethods(String className, byte[] classBytes, Map<String, List<String>> methods) {
        if (className == null || className.endsWith("module-info") || className.endsWith("package-info")) return;
        if (isNoiseClass(className)) return;
        try {
            List<String> methodNames = parseClassMethodNames(classBytes);
            if (!methodNames.isEmpty()) methods.putIfAbsent(className, methodNames);
        } catch (Exception e) {
            AppLog.warn("[JvmService] method parse skipped " + className + ": " + e.getMessage());
        }
    }

    private static List<String> parseClassMethodNames(byte[] classBytes) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes))) {
            if (in.readInt() != 0xCAFEBABE) return List.of();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    default -> throw new IllegalArgumentException("unsupported constant pool tag " + tag);
                }
            }
            in.skipBytes(6);
            int interfacesCount = in.readUnsignedShort();
            in.skipBytes(interfacesCount * 2);
            skipMembers(in);
            int methodCount = in.readUnsignedShort();
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < methodCount; i++) {
                int access = in.readUnsignedShort();
                String name = utf8[in.readUnsignedShort()];
                in.readUnsignedShort();
                skipAttributes(in);
                if (name == null || name.startsWith("<")) continue;
                if ((access & 0x1000) != 0 || (access & 0x0040) != 0) continue;
                names.add(name);
            }
            return new ArrayList<>(names);
        }
    }

    private static void skipMembers(DataInputStream in) throws Exception {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(6);
            skipAttributes(in);
        }
    }

    private static void skipAttributes(DataInputStream in) throws Exception {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            long length = Integer.toUnsignedLong(in.readInt());
            long skipped = 0;
            while (skipped < length) {
                long step = in.skip(length - skipped);
                if (step <= 0) throw new IllegalStateException("unable to skip class attribute");
                skipped += step;
            }
        }
    }

    private static void addJarClassName(String entryName, Set<String> classes) {
        String className = jarEntryToClassName(entryName);
        if (className == null) return;
        if (className.endsWith("module-info") || className.endsWith("package-info")) return;
        if (isNoiseClass(className)) return;
        classes.add(className);
    }

    private static String jarEntryToClassName(String entryName) {
        String name = entryName;
        if (name.startsWith("BOOT-INF/classes/")) {
            name = name.substring("BOOT-INF/classes/".length());
        } else if (name.startsWith("WEB-INF/classes/")) {
            name = name.substring("WEB-INF/classes/".length());
        } else if (name.startsWith("classes/")) {
            name = name.substring("classes/".length());
        } else if (name.startsWith("BOOT-INF/lib/") || name.startsWith("META-INF/versions/") || name.startsWith("lib/")) {
            return null;
        }
        if (!name.endsWith(".class")) return null;
        return name.substring(0, name.length() - ".class".length()).replace('/', '.');
    }

    private record TargetRuntime(List<String> classpathEntries, String workingDirectory) {}

    private static String parseHistogramClassName(String line) {
        String text = line == null ? "" : line.trim();
        if (text.isEmpty() || !Character.isDigit(text.charAt(0))) return null;
        String[] parts = text.split("\\s+");
        if (parts.length < 4) return null;
        String name = parts[3];
        int moduleSep = name.indexOf('/');
        if (moduleSep >= 0 && moduleSep + 1 < name.length()) {
            name = name.substring(moduleSep + 1);
        }
        name = name.replace('/', '.');
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (!name.contains(".")) return null;
        return name;
    }

    private static boolean isNoiseClass(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.")
                || className.startsWith("com.apple.")
                || className.startsWith("javafx.")
                || className.startsWith("com.agentmonitor.");
    }

    public static void attachAgent(String pid, String packagePrefixes, int port,
                                   String excludeConditions, String excludeMethods) throws Exception {
        attachAgent(pid, packagePrefixes, port, excludeConditions, excludeMethods, AgentOutputConfig.defaults(),
                null, DEFAULT_SAMPLING_RATE_PERCENT, DEFAULT_SLOW_THRESHOLD_MILLIS);
    }

    public static void attachAgent(String pid, String packagePrefixes, int port,
                                   String excludeConditions, String excludeMethods,
                                   AgentOutputConfig outputConfig) throws Exception {
        attachAgent(pid, packagePrefixes, port, excludeConditions, excludeMethods, outputConfig, null,
                DEFAULT_SAMPLING_RATE_PERCENT, DEFAULT_SLOW_THRESHOLD_MILLIS);
    }

    public static void attachAgent(String pid, String packagePrefixes, int port,
                                   String excludeConditions, String excludeMethods,
                                   AgentOutputConfig outputConfig, MonitoringSession session) throws Exception {
        attachAgent(pid, packagePrefixes, port, excludeConditions, excludeMethods, outputConfig, session,
                DEFAULT_SAMPLING_RATE_PERCENT, DEFAULT_SLOW_THRESHOLD_MILLIS);
    }

    /** Writes the YAML sampling policy into the isolated Agent's per-attach properties file. */
    public static void attachAgent(String pid, String packagePrefixes, int port,
                                   String excludeConditions, String excludeMethods,
                                   AgentOutputConfig outputConfig, MonitoringSession session,
                                   int samplingRatePercent, long slowThresholdMillis) throws Exception {
        attachAgent(pid, packagePrefixes, "", port, excludeConditions, excludeMethods, outputConfig, session,
                samplingRatePercent, slowThresholdMillis, MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SPANS,
                MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB, MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY);
    }

    private static void attachAgent(String pid, String packagePrefixes, String includeClasses, int port,
                                    String excludeConditions, String excludeMethods,
                                    AgentOutputConfig outputConfig, MonitoringSession session,
                                    int samplingRatePercent, long slowThresholdMillis,
                                    int tailMaxBufferedSpans, int tailMaxBufferedSizeMb,
                                    MonitoringConfig.TailOverflowPolicy tailOverflowPolicy) throws Exception {
        File jar = getOrExtractAgentJar();
        File cfg = writeAgentConfig(pid, jar, packagePrefixes, includeClasses, port, excludeConditions, excludeMethods, outputConfig,
                session, samplingRatePercent, slowThresholdMillis, tailMaxBufferedSpans, tailMaxBufferedSizeMb,
                tailOverflowPolicy);
        String args = "cfg=" + cfg.getAbsolutePath();
        AppLog.info("[agent-monitor] attach pid=" + pid + " args=" + args);
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(jar.getAbsolutePath(), args);
        } finally {
            vm.detach();
        }
    }

    /** Maps the one validated YAML model into the Agent's private attach properties. */
    public static void attachAgent(String pid, MonitoringConfig config, int port, MonitoringSession session)
            throws Exception {
        if (config == null) throw new IllegalArgumentException("监控配置不能为空");
        config.requireValid();
        attachAgent(pid, config.packagePrefixesArg(), config.includeClassesArg(), port,
                config.excludeConditionsArg(), config.excludeMethodsArg(),
                AgentOutputConfig.from(config), session, config.sampling().ratePercent(),
                config.sampling().tailCaptureThresholdMs(), config.sampling().tailMaxBufferedSpans(),
                config.sampling().tailMaxBufferedSizeMb(), config.sampling().tailOverflowPolicy());
    }

    private static File writeAgentConfig(String pid, File jar, String packagePrefixes, String includeClasses,
                                         int port, String excludeConditions, String excludeMethods,
                                         AgentOutputConfig outputConfig, MonitoringSession session,
                                         int samplingRatePercent, long slowThresholdMillis,
                                         int tailMaxBufferedSpans, int tailMaxBufferedSizeMb,
                                         MonitoringConfig.TailOverflowPolicy tailOverflowPolicy) throws Exception {
        AgentOutputConfig output = outputConfig == null ? AgentOutputConfig.defaults() : outputConfig;
        Properties props = new Properties();
        props.setProperty("pkg", packagePrefixes == null ? "" : packagePrefixes);
        props.setProperty("inclCls", includeClasses == null ? "" : includeClasses);
        props.setProperty("host", "127.0.0.1");
        props.setProperty("port", String.valueOf(port));
        props.setProperty("jar", jar.getAbsolutePath());
        props.setProperty("excl", excludeConditions == null ? "" : excludeConditions);
        props.setProperty("mexcl", excludeMethods == null ? "" : excludeMethods);
        props.setProperty("exporters", output.exportersArg());
        String spanDirectory = session == null ? "" : session.spansDirectory().toString();
        props.setProperty("fileDir", spanDirectory);
        props.setProperty("fileSessionDir", spanDirectory);
        props.setProperty("agentLogDir", session == null ? "" : session.agentLogsDirectory().toString());
        props.setProperty("fileRotateSizeMb", String.valueOf(Math.max(1, output.fileRotateSizeMb())));
        props.setProperty("fileRotateMinutes", String.valueOf(Math.max(1, output.fileRotateMinutes())));
        props.setProperty("fileCompress", String.valueOf(output.fileCompress()));
        props.setProperty("captureArgs", String.valueOf(output.captureArguments()));
        props.setProperty("captureReturnValue", String.valueOf(output.captureReturnValue()));
        props.setProperty("captureSqlParameters", String.valueOf(output.captureSqlParameters()));
        props.setProperty("dependency.jdbc", String.valueOf(output.jdbcEnabled()));
        props.setProperty("dependency.http", String.valueOf(output.httpEnabled()));
        props.setProperty("resource.service.name", output.serviceName() == null ? "" : output.serviceName());
        props.setProperty("resource.service.version", output.serviceVersion() == null ? "" : output.serviceVersion());
        props.setProperty("resource.deployment.environment", output.deploymentEnvironment() == null
                ? "" : output.deploymentEnvironment());
        props.setProperty("samplingRate", String.valueOf(Math.max(0, Math.min(100, samplingRatePercent))));
        props.setProperty("slowThreshold", String.valueOf(Math.max(0, slowThresholdMillis)));
        props.setProperty("sampling.tailMaxBufferedSpans", String.valueOf(Math.max(1, tailMaxBufferedSpans)));
        props.setProperty("sampling.tailMaxBufferedSizeMb", String.valueOf(Math.max(1, tailMaxBufferedSizeMb)));
        props.setProperty("sampling.tailOverflowPolicy", (tailOverflowPolicy == null
                ? MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY : tailOverflowPolicy).configValue());
        Path configDirectory = Path.of(System.getProperty("java.io.tmpdir"), "agent-monitor", "configs")
                .toAbsolutePath().normalize();
        Files.createDirectories(configDirectory);
        // The Agent entry and the isolated core read this file at different points in startup.
        // A unique file prevents another App instance from replacing the arguments in between.
        Path cfgPath = Files.createTempFile(configDirectory, "monitor-agent-config-" + pid + '-', ".properties");
        try (java.io.OutputStream out = Files.newOutputStream(cfgPath)) {
            props.store(out, "agent-monitor runtime config");
        }
        return cfgPath.toFile();
    }

    private static synchronized File getOrExtractAgentJar() throws Exception {
        try (InputStream in = JvmService.class.getResourceAsStream("/agent/monitor-agent.agent")) {
            if (in == null) throw new IllegalStateException("agent jar not found in resources");
            byte[] content = in.readAllBytes();
            String digest = sha256(content);
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "agent-monitor", "agents")
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path artifact = directory.resolve("monitor-agent-" + digest + ".jar");
            if (!Files.isRegularFile(artifact)) writeImmutableAgentArtifact(directory, artifact, content);
            agentJarFile = artifact.toFile();
        }
        return agentJarFile;
    }

    /**
     * A target JVM can keep the Agent and nested Bootstrap JARs open for its whole lifetime.
     * Content-addressed files avoid overwriting a JarFile that another target is still resolving.
     */
    private static void writeImmutableAgentArtifact(Path directory, Path target, byte[] content) throws Exception {
        Path temporary = Files.createTempFile(directory, "monitor-agent-", ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent App instance already wrote this exact content-addressed artifact.
            } catch (AtomicMoveNotSupportedException ignored) {
                try {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException alreadyPresent) {
                    // A concurrent App instance already wrote this exact content-addressed artifact.
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

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

}
