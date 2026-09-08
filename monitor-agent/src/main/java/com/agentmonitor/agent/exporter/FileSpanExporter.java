package com.agentmonitor.agent.exporter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.agentmonitor.agent.log.AgentLog;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.protocol.SpanPayloadSerializer;
import com.agentmonitor.model.output.ExporterType;

/**
 * Writes schema-versioned JSON array files for AI analysis. It is only invoked
 * from {@link AsyncSpanExporter}'s background thread.
 */
final class FileSpanExporter implements SpanExporter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final String SESSION_DIRECTORY_PREFIX = "session-";
    private static final String SPAN_FILE_PATTERN = "spans-%05d.json%s";
    private static final String MANIFEST_FILE_NAME = "manifest.json";
    private static final String COMPLETED_MARKER_FILE_NAME = "completed.marker";
    private static final String COMPLETED_MARKER_CONTENT = "completed\n";
    private static final long LIVE_FLUSH_INTERVAL_MILLIS = 1_000;

    private final Path baseDirectory;
    private final Path configuredSessionDirectory;
    private final long rotateBytes;
    private final long rotateMillis;
    private final boolean compress;
    private final SpanPayloadSerializer serializer;
    private final Map<String, String> resourceAttributes;
    private Instant sessionStartedAt;
    private Path sessionDirectory;
    private BufferedWriter writer;
    private long currentBytes;
    private long currentOpenedAt;
    private long lastFlushedAt;
    private int fileIndex;
    private long written;
    private boolean hasEntries;
    private boolean completed;

    FileSpanExporter(String configuredDirectory, String configuredSessionDirectory,
                     long rotateBytes, long rotateMillis, boolean compress,
                     SpanPayloadSerializer serializer) {
        this(configuredDirectory, configuredSessionDirectory, rotateBytes, rotateMillis, compress, serializer, Map.of());
    }

    FileSpanExporter(String configuredDirectory, String configuredSessionDirectory,
                     long rotateBytes, long rotateMillis, boolean compress,
                     SpanPayloadSerializer serializer, Map<String, String> resourceAttributes) {
        this.baseDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
        this.configuredSessionDirectory = configuredSessionDirectory == null || configuredSessionDirectory.isBlank()
                ? null : Path.of(configuredSessionDirectory).toAbsolutePath().normalize();
        this.rotateBytes = rotateBytes;
        this.rotateMillis = rotateMillis;
        this.compress = compress;
        this.serializer = serializer;
        this.resourceAttributes = Map.copyOf(resourceAttributes == null ? Map.of() : resourceAttributes);
    }

    @Override
    public String name() { return ExporterType.FILE.configValue(); }

    @Override
    public boolean start() {
        try {
            sessionStartedAt = Instant.now();
            sessionDirectory = configuredSessionDirectory == null
                    ? baseDirectory.resolve(DAY.format(sessionStartedAt)).resolve(SESSION_DIRECTORY_PREFIX + UUID.randomUUID())
                    : configuredSessionDirectory;
            Files.createDirectories(sessionDirectory);
            writeManifest("running");
            return true;
        } catch (IOException e) {
            AgentLog.error("[agent-monitor] file exporter start failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public ExportResult export(SpanData span) {
        try {
            String serializedSpan = serializer.serialize(span);
            byte[] bytes = serializedSpan.getBytes(StandardCharsets.UTF_8);
            if (writer == null || currentBytes + bytes.length > rotateBytes
                    || System.currentTimeMillis() - currentOpenedAt >= rotateMillis) {
                rotate();
            }
            if (hasEntries) {
                writer.write(',');
                writer.newLine();
                currentBytes += 2;
            }
            writer.write(serializedSpan);
            writer.newLine();
            currentBytes += bytes.length;
            written++;
            hasEntries = true;
            flushLiveOutputIfDue();
            return ExportResult.accepted();
        } catch (IOException e) {
            AgentLog.error("[agent-monitor] file exporter write failed: " + e.getMessage());
            return ExportResult.rejected();
        }
    }

    private void rotate() throws IOException {
        closeWriter();
        String name = String.format(SPAN_FILE_PATTERN, ++fileIndex, compress ? ".gz" : "");
        OutputStream stream = Files.newOutputStream(sessionDirectory.resolve(name),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (compress) stream = new GZIPOutputStream(stream, true);
        writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        writer.write('[');
        writer.newLine();
        currentBytes = 2;
        currentOpenedAt = System.currentTimeMillis();
        lastFlushedAt = currentOpenedAt;
        hasEntries = false;
    }

    /** Makes active sessions observable without forcing synchronous disk I/O for every Span. */
    private void flushLiveOutputIfDue() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastFlushedAt < LIVE_FLUSH_INTERVAL_MILLIS) return;
        writer.flush();
        writeManifest("running");
        lastFlushedAt = now;
    }

    @Override
    public ExporterDrainResult drain(long timeoutMillis) {
        if (sessionDirectory == null || completed) return ExporterDrainResult.success();
        try {
            // The async owner stops submission before invoking this leaf. Finalising here makes
            // STOPPED mean the file session is already complete and readable, not just flushed.
            closeWriter();
            writeManifest("completed");
            Files.writeString(sessionDirectory.resolve(COMPLETED_MARKER_FILE_NAME), COMPLETED_MARKER_CONTENT,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            completed = true;
            return ExporterDrainResult.success();
        } catch (IOException error) {
            AgentLog.error("[agent-monitor] file exporter drain failed: " + error.getMessage());
            return ExporterDrainResult.failed(0, 0, 0, error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        }
    }

    @Override
    public void close() {
        drain(0);
    }

    private void closeWriter() throws IOException {
        if (writer != null) {
            writer.newLine();
            writer.write(']');
            writer.newLine();
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    private void writeManifest(String status) throws IOException {
        String manifest = "{\"schemaVersion\":1,\"format\":\"json-array\",\"status\":\"" + status
                + "\",\"startedAt\":\"" + sessionStartedAt + "\",\"writtenSpans\":" + written
                + ",\"resource\":" + attributesJson(resourceAttributes) + "}\n";
        Files.writeString(sessionDirectory.resolve(MANIFEST_FILE_NAME), manifest, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String attributesJson(Map<String, String> attributes) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            if (!first) json.append(',');
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
            first = false;
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
