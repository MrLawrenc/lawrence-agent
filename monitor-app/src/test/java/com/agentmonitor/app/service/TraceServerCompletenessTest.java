package com.agentmonitor.app.service;

import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.model.protocol.ProtocolMessageType;
import com.agentmonitor.model.protocol.TelemetryProtocol;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TraceServerCompletenessTest {
    @Test void unresolvedChildrenCannotProduceACompletedReportAndRepeatedStopIsStable() throws Exception {
        exercise(false);
    }
    @Test void aCompletedRootKeepsItsSuccessfulResultWhenCleanupRunsAgain() throws Exception {
        exercise(true);
    }
    private void exercise(boolean includeRoot) throws Exception {
        TraceServer server = new TraceServer(0);
        int port = server.start();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            PrintWriter output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            output.println(TelemetryProtocol.message(ProtocolMessageType.HELLO));
            output.println(span("child", "root"));
            if (includeRoot) output.println(span("root", ""));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            long expected = includeRoot ? 2 : 1;
            while (server.getEventCount() != expected && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(expected, server.getEventCount());
            CompletableFuture<Void> acknowledgement = CompletableFuture.runAsync(() -> {
                try {
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    assertEquals(TelemetryProtocol.STOP_COMMAND, input.readLine());
                    output.println(TelemetryProtocol.message(ProtocolMessageType.STOPPED));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            TraceServer.StopResult result = server.stopAndAwaitRestore();
            acknowledgement.get(5, TimeUnit.SECONDS);
            assertTrue(result.restored());
            assertEquals(includeRoot, result.outputDrained());
            if (!includeRoot) assertTrue(result.message().contains("1 条未完成链路"));
            server.stop();
            assertSame(result, server.stopAndAwaitRestore(), "Cleanup must not replace the finalized result");
        } finally { server.stop(); }
    }
    private static String span(String id, String parent) {
        return "{\"type\":\"span\",\"schemaVersion\":1,\"traceId\":\"trace\",\"spanId\":\"" + id
                + "\",\"parentSpanId\":\"" + parent + "\",\"className\":\"example.Service\",\"methodName\":\"work\","
                + "\"signature\":\"()V\",\"threadName\":\"test\",\"depth\":1,\"startedAtEpochMillis\":1,"
                + "\"durationNanos\":1,\"error\":false,\"kind\":\"BUSINESS\"}";
    }
}
