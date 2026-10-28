package com.agentmonitor.core.service;

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
    @Test void delayedChildUpdatesPreviewAndIsFinalizedOnlyAtTheStopBarrier() throws Exception {
        TraceServer server = new TraceServer(0);
        var previews = new java.util.concurrent.CopyOnWriteArrayList<com.agentmonitor.model.trace.TraceNode>();
        server.setOnRootNode(previews::add);
        int port=server.start();
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000);
            var output=new PrintWriter(socket.getOutputStream(),true,StandardCharsets.UTF_8);
            output.println(TelemetryProtocol.message(ProtocolMessageType.HELLO)); output.println(span("root",""));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(previews.isEmpty() && System.nanoTime()<deadline) Thread.sleep(10);
            assertEquals(1,previews.size()); assertTrue(previews.get(0).getChildren().isEmpty());
            Thread.sleep(600); // More than a second after root arrival, across export batches.
            output.println(span("child","root"));
            var ack=CompletableFuture.runAsync(() -> {
                try { assertEquals(TelemetryProtocol.STOP_COMMAND,new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine());
                    output.println("{\"type\":\"output_quality\",\"schemaVersion\":1,\"reported\":true,\"enqueuedSpans\":2,\"queueDroppedSpans\":0,\"deliveryDroppedSpans\":0,\"rejectedDestinations\":0,\"pendingSpans\":0}");
                    output.println(TelemetryProtocol.message(ProtocolMessageType.STOPPED));
                } catch(Exception error){throw new RuntimeException(error);}
            });
            var result=server.stopAndAwaitRestore(); ack.get(5,TimeUnit.SECONDS);
            assertTrue(result.outputDrained(),result.message()); assertEquals(2,server.getEventCount());
            assertEquals(1,previews.get(previews.size()-1).getChildren().size());
            assertTrue(previews.get(0).getChildren().isEmpty(),"Published previews remain immutable");
        } finally {server.stop();}
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
                    output.println("{\"type\":\"output_quality\",\"schemaVersion\":1,\"reported\":true,\"enqueuedSpans\":" + expected
                            + ",\"queueDroppedSpans\":0,\"deliveryDroppedSpans\":0,\"rejectedDestinations\":0,\"pendingSpans\":0}");
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
