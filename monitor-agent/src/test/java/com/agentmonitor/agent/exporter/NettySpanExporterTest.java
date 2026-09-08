package com.agentmonitor.agent.exporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.model.SpanStatus;
import com.agentmonitor.agent.protocol.JsonSpanPayloadSerializer;
import com.agentmonitor.model.protocol.ProtocolMessageType;
import com.agentmonitor.model.protocol.TelemetryProtocol;
import com.agentmonitor.model.span.SpanKind;

class NettySpanExporterTest {

    private static final int IO_TIMEOUT_MILLIS = 3_000;

    @Test
    void reconnectsOnceAndReplaysHelloAndReadyAfterCollectorDisconnect() throws Exception {
        try (ServerSocket collector = new ServerSocket(0)) {
            NettySpanExporter exporter = newExporter(collector.getLocalPort());
            try {
                assertTrue(exporter.start());
                try (Connection first = accept(collector)) {
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.HELLO), first.readLine());
                    exporter.ready();
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.READY), first.readLine());
                    first.socket.close();
                }

                try (Connection reconnected = accept(collector)) {
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.HELLO), reconnected.readLine());
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.READY), reconnected.readLine());
                    assertTrue(exporter.export(span()).delivered());
                    assertTrue(reconnected.readLine().contains("\"type\":\"span\""));

                    // A stale retry task must not establish a second connection after the first
                    // reconnect succeeds.
                    assertNoIncomingConnection(collector, 400);
                }
            } finally {
                exporter.close();
            }
        }
    }

    @Test
    void reconnectHandshakeOrdersHelloBeforeConcurrentReadyAndSpan() throws Exception {
        try (ServerSocket collector = new ServerSocket(0)) {
            NettySpanExporter exporter = newExporter(collector.getLocalPort());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                assertTrue(exporter.start());
                try (Connection first = accept(collector)) {
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.HELLO), first.readLine());
                    first.socket.close();
                }

                try (Connection reconnected = accept(collector)) {
                    CountDownLatch begin = new CountDownLatch(1);
                    Future<?> race = executor.submit(() -> {
                        begin.await();
                        exporter.ready();
                        // Wait until publication catches up if this thread won the race before
                        // reconnect. This exercises the ordering boundary, not delivery retry.
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                        while (!exporter.export(span()).delivered() && System.nanoTime() < deadline) {
                            Thread.onSpinWait();
                        }
                        return null;
                    });
                    begin.countDown();

                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.HELLO), reconnected.readLine());
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.READY), reconnected.readLine());
                    assertTrue(reconnected.readLine().contains("\"type\":\"span\""));
                    race.get(1, TimeUnit.SECONDS);
                }
            } finally {
                exporter.close();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void closeCancelsAReconnectScheduledByDisconnect() throws Exception {
        try (ServerSocket collector = new ServerSocket(0)) {
            NettySpanExporter exporter = newExporter(collector.getLocalPort());
            try {
                assertTrue(exporter.start());
                try (Connection first = accept(collector)) {
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.HELLO), first.readLine());
                    first.socket.close();
                }

                // Local close notification is fast; this is deliberately less than the 100ms
                // first retry delay, so close() must cancel the pending task before it fires.
                Thread.sleep(40);
                exporter.close();
                assertNoIncomingConnection(collector, 500);
            } finally {
                exporter.close();
            }
        }
    }

    @Test
    void drainFailureOnDisconnectDoesNotReconnectOrMoveTheStopBarrier() throws Exception {
        try (ServerSocket collector = new ServerSocket(0)) {
            NettySpanExporter exporter = newExporter(collector.getLocalPort());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                assertTrue(exporter.start());
                try (Connection connection = accept(collector)) {
                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.HELLO), connection.readLine());
                    Future<ExporterDrainResult> drain = executor.submit(() -> exporter.drain(2_000));

                    assertEquals(TelemetryProtocol.message(ProtocolMessageType.DRAIN_REQUEST), connection.readLine());
                    connection.socket.close();

                    ExporterDrainResult result = drain.get(2, TimeUnit.SECONDS);
                    assertFalse(result.drained(), result::summary);
                    assertTrue(result.detail().contains("drain"), result::summary);
                }
                assertNoIncomingConnection(collector, 500);
            } finally {
                exporter.close();
                executor.shutdownNow();
            }
        }
    }

    private static NettySpanExporter newExporter(int port) {
        return new NettySpanExporter("127.0.0.1", port, new JsonSpanPayloadSerializer());
    }

    private static Connection accept(ServerSocket collector) throws IOException {
        collector.setSoTimeout(IO_TIMEOUT_MILLIS);
        Socket socket = collector.accept();
        socket.setSoTimeout(IO_TIMEOUT_MILLIS);
        return new Connection(socket);
    }

    private static void assertNoIncomingConnection(ServerSocket collector, int timeoutMillis) throws IOException {
        collector.setSoTimeout(timeoutMillis);
        assertThrows(SocketTimeoutException.class, collector::accept,
                "no retry connection may be created after the exporter becomes terminal");
    }

    private static SpanData span() {
        return new SpanData("trace", "span", "", "example.Service", "work", "()V", "test", 0,
                1L, 1L, SpanStatus.SUCCESS, "", "", "", SpanKind.BUSINESS, Map.of());
    }

    private static final class Connection implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        @SuppressWarnings("unused")
        private final BufferedWriter writer;

        private Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private String readLine() throws IOException {
            return reader.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
