package com.agentmonitor.app.service;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OfflineTraceReplayLoaderTest {
    @TempDir Path directory;
    private static String span(String trace, String id, String parent) {
        return "{\"type\":\"span\",\"schemaVersion\":1,\"traceId\":\"" + trace + "\",\"spanId\":\"" + id + "\",\"parentSpanId\":\"" + parent
                + "\",\"className\":\"example.Service\",\"methodName\":\"work\",\"durationNanos\":42}";
    }
    @Test void reconstructsOutOfOrderParentsAcrossGzipAndJsonFilesAndDeduplicates() throws Exception {
        Files.writeString(directory.resolve("spans-00001.json"), "[" + span("t", "child", "root") + "]");
        try (var out = new GZIPOutputStream(Files.newOutputStream(directory.resolve("spans-00002.json.gz")))) {
            out.write(("[" + span("t", "root", "") + "," + span("t", "root", "") + "]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var replay = new OfflineTraceReplayLoader().load(directory);
        assertEquals(2, replay.acceptedSpans()); assertEquals(1, replay.skippedSpans());
        assertEquals(1, replay.roots().size()); assertEquals(1, replay.roots().get(0).getChildren().size());
        assertEquals(0, replay.orphanSpans()); assertEquals(0, replay.cyclicSpans());
    }
    @Test void separatesTraceIdsAndReportsOrphansAndCyclesWithoutAttachingThemToRoots() throws Exception {
        Files.writeString(directory.resolve("spans-1.json"), "[" + span("a", "root", "") + "," + span("b", "child", "root")
                + "," + span("c", "x", "y") + "," + span("c", "y", "x") + "]");
        var replay = new OfflineTraceReplayLoader().load(directory);
        assertEquals(1, replay.roots().size()); assertEquals(1, replay.orphanSpans()); assertEquals(2, replay.cyclicSpans());
        assertTrue(replay.roots().get(0).getChildren().isEmpty()); assertTrue(replay.hasDegradation());
    }
    @Test void preservesValidPrefixButWarnsAboutAnUnclosedActiveFile() throws Exception {
        Files.writeString(directory.resolve("spans-1.json"), "[" + span("t", "root", "") + ",");
        var replay = new OfflineTraceReplayLoader().load(directory);
        assertEquals(1, replay.roots().size()); assertEquals(1, replay.warnings().size()); assertTrue(replay.hasDegradation());
    }
}
