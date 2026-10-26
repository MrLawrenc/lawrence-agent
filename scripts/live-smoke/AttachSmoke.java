package com.agentmonitor.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.tools.attach.VirtualMachine;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Real, bounded Attach/Netty-protocol smoke check, independent of the desktop services. */
public final class AttachSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final class Control {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final CompletableFuture<JsonNode> stopped = new CompletableFuture<>();
        final AtomicLong spans = new AtomicLong();
        volatile PrintWriter commands;
        volatile JsonNode quality;
        synchronized void send(String message) {
            if (commands != null) commands.println(message);
        }
        JsonNode stop() throws Exception {
            send("STOP");
            return stopped.get(10, TimeUnit.SECONDS);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: attachSmoke <pid> <exact-class> [seconds=30]");
        long pid = Long.parseLong(args[0]);
        if (pid == ProcessHandle.current().pid() || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            throw new IllegalArgumentException("Target must be a live external JVM");
        }
        String selectedClass = args[1];
        if (!selectedClass.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) throw new IllegalArgumentException("Expected one exact JVM class name");
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("Duration must be 1–120 seconds");
        Path agent = Path.of(System.getProperty("agent.monitor.smoke.jar")).toAbsolutePath();
        Path directory = Path.of("build", "live-smoke", Instant.now().toString().replace(':', '-') + "-pid-" + pid).toAbsolutePath();
        Files.createDirectories(directory);
        Control control = new Control();
        ObjectNode result = JSON.createObjectNode();
        result.put("targetPid", pid); result.put("exactClass", selectedClass); result.put("startedAt", Instant.now().toString());
        result.put("sessionDirectory", directory.toString());
        Thread hook = new Thread(() -> {
            try { if (!control.stopped.isDone()) control.stop(); }
            catch (Exception error) { System.err.println("Shutdown could not confirm restore: " + error.getMessage()); }
        }, "smoke-stop-on-exit");
        Runtime.getRuntime().addShutdownHook(hook);
        boolean attachAttempted = false;
        boolean restoreConfirmed = false;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(15_000);
            Thread receiver = new Thread(() -> receive(server, directory, control), "smoke-collector");
            receiver.setDaemon(true); receiver.start();
            String config = "pkg=,inclCls=" + selectedClass + ",jar=" + agent + ",host=127.0.0.1,port=" + server.getLocalPort()
                    + ",exporters=netty|file,fileDir=" + directory + ",fileSessionDir=" + directory.resolve("spans")
                    + ",agentLogDir=" + directory.resolve("agent-logs")
                    + ",captureArgs=false,captureReturnValue=false,captureSqlParameters=false"
                    + ",dependency.jdbc=false,dependency.http=false,samplingRate=100"
                    + ",mexcl=get*|is*|set*|toString|hashCode|equals";
            System.out.println("ATTACH target=" + pid + " class=" + selectedClass);
            System.out.println("SESSION " + directory);
            VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
            try { attachAttempted = true; vm.loadAgent(agent.toString(), config); }
            finally { vm.detach(); }
            control.ready.get(20, TimeUnit.SECONDS);
            System.out.println("READY · real JVM connected; arguments/returns disabled");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline && !Files.exists(directory.resolve("stop.request"))) Thread.sleep(100);
            JsonNode acknowledgement = control.stop();
            String type = acknowledgement.path("type").asText();
            restoreConfirmed = type.equals("stopped") || type.equals("drain_failed");
            result.set("stopAcknowledgement", acknowledgement);
            result.put("restored", restoreConfirmed);
            result.put("captureComplete", type.equals("stopped") && control.quality != null
                    && control.quality.path("reported").asBoolean(false)
                    && control.quality.path("queueDroppedSpans").asLong() == 0
                    && control.quality.path("deliveryDroppedSpans").asLong() == 0
                    && control.quality.path("rejectedDestinations").asLong() == 0
                    && control.quality.path("pendingSpans").asLong() == 0);
            if (control.quality != null) result.set("captureQuality", control.quality);
            result.put("receivedSpans", control.spans.get());
            result.put("targetStillAlive", ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            validateFiles(directory, result);
            System.out.println("STOP type=" + type + " restored=" + restoreConfirmed + " spans=" + control.spans.get());
            if (!restoreConfirmed) throw new IllegalStateException("Bytecode restoration was not confirmed: " + acknowledgement);
            if (control.spans.get() == 0) throw new IllegalStateException("No spans observed; this smoke check did not verify collection");
            if (!result.path("captureComplete").asBoolean()) throw new IllegalStateException("Restored, but capture quality is incomplete");
        } finally {
            if (attachAttempted && !restoreConfirmed && control.commands != null && !control.stopped.isDone()) {
                try {
                    JsonNode acknowledgement = control.stop();
                    String type = acknowledgement.path("type").asText();
                    restoreConfirmed = type.equals("stopped") || type.equals("drain_failed");
                    result.set("stopAcknowledgement", acknowledgement);
                } catch (Exception error) { result.put("restoreError", error.toString()); }
            }
            result.put("restored", restoreConfirmed);
            result.put("analysisReady", restoreConfirmed && result.path("captureComplete").asBoolean(false)
                    && result.path("receivedSpans").asLong() > 0
                    && result.path("receivedSpans").asLong() == result.path("fileSpans").asLong()
                    && result.path("missingParentSpans").asLong(-1) == 0);
            result.put("finishedAt", Instant.now().toString());
            Files.writeString(directory.resolve("smoke-result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            Files.writeString(directory.resolve("README.md"), "# Real JVM attach smoke check\n\n"
                    + "Target PID: " + pid + "; exact class: " + selectedClass + ". No arguments or return values captured.\n\n"
                    + "Read smoke-result.json for the restore acknowledgement and capture quality, then spans/manifest.json.\n"
                    + "protocol.jsonl contains the received frames; spans/spans-*.json contains Agent file output.\n"
                    + "This diagnostic does not generate the desktop performance report.\n");
            Runtime.getRuntime().removeShutdownHook(hook);
            System.out.println("RESULT " + directory.resolve("smoke-result.json"));
        }
    }
    private static void receive(ServerSocket server, Path directory, Control control) {
        try (Socket socket = server.accept();
             BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter recording = Files.newBufferedWriter(directory.resolve("protocol.jsonl"), StandardCharsets.UTF_8)) {
            control.commands = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            for (String line; (line = input.readLine()) != null;) {
                JsonNode frame = JSON.readTree(line);
                recording.write(line); recording.newLine();
                if (!frame.path("type").asText().equals("span")) recording.flush();
                switch (frame.path("type").asText()) {
                    case "span" -> control.spans.incrementAndGet();
                    case "ready" -> control.ready.complete(null);
                    case "drain_request" -> control.send("{\"type\":\"drain_ack\",\"schemaVersion\":1}");
                    case "output_quality" -> control.quality = frame;
                    case "stopped", "drain_failed", "reset_failed" -> control.stopped.complete(frame);
                    default -> { }
                }
            }
        } catch (Exception error) {
            control.ready.completeExceptionally(error);
            control.stopped.completeExceptionally(error);
        }
    }
    private static void validateFiles(Path directory, ObjectNode result) throws Exception {
        var ids = new HashSet<String>(); var parents = new java.util.ArrayList<String>();
        long fileSpans = 0;
        try (var files = Files.list(directory.resolve("spans"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("spans-.*\\.json")).toList()) {
                for (JsonNode span : JSON.readTree(file.toFile())) {
                    fileSpans++; ids.add(span.path("spanId").asText());
                    String parent = span.path("parentSpanId").asText(); if (!parent.isEmpty()) parents.add(parent);
                }
            }
        }
        long orphans = parents.stream().filter(parent -> !ids.contains(parent)).count();
        result.put("fileSpans", fileSpans); result.put("missingParentSpans", orphans);
        result.set("fileManifest", JSON.readTree(directory.resolve("spans/manifest.json").toFile()));
        if (fileSpans != result.path("receivedSpans").asLong() || orphans > 0) {
            throw new IllegalStateException("File/network evidence mismatch or missing parent spans");
        }
    }
}
