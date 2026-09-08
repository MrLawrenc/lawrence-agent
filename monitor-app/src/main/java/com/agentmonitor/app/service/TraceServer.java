package com.agentmonitor.app.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.agentmonitor.app.model.CallNode;
import com.agentmonitor.app.model.MonitoringSession;
import com.agentmonitor.app.model.SpanEvent;
import com.agentmonitor.app.report.analysis.PerformanceReportCollector;
import com.agentmonitor.app.report.model.AnalysisConfig;
import com.agentmonitor.app.report.model.CaptureQuality;
import com.agentmonitor.app.report.model.ReportStatus;
import com.agentmonitor.model.output.ExporterStatistics;
import com.agentmonitor.model.protocol.ProtocolEnvelope;
import com.agentmonitor.model.protocol.ProtocolMessageType;
import com.agentmonitor.model.protocol.TelemetryProtocol;
import com.agentmonitor.model.span.SpanKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 * Local Netty Collector for schema-v1 completed spans.  The protocol is
 * intentionally independent from the JavaFX UI: future WebSocket/API clients
 * can subscribe here without changing the Agent.
 */
public class TraceServer {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final String STOP_COMMAND = TelemetryProtocol.STOP_COMMAND + TelemetryProtocol.NEWLINE;
    /**
     * The Agent reconnects with a short bounded backoff.  A stop request must leave enough time
     * for the next connection to arrive, but it must never leave the UI waiting indefinitely.
     */
    private static final long AGENT_RECONNECT_GRACE_MILLIS = 3_000;
    private static final long STOP_COMMAND_WRITE_TIMEOUT_MILLIS = 1_000;
    private static final long STOP_ACK_TIMEOUT_MILLIS = 8_000;
    private static final long SERVER_CLOSE_TIMEOUT_MILLIS = 2_000;
    private static final ObjectMapper PROTOCOL_MAPPER = new ObjectMapper();

    private final int port;
    private final MonitoringSession monitoringSession;
    private final PerformanceReportCollector performanceReportCollector;
    private final Map<String, TraceState> traceStates = new HashMap<>();
    private final Object stopLock = new Object();
    /** Serializes publication of the one canonical Agent control connection. */
    private final Object controlChannelLock = new Object();
    private CompletableFuture<Channel> nextControlChannel = new CompletableFuture<>();
    private volatile CompletableFuture<StopResult> stopAcknowledgement;
    private volatile Channel controlChannel;
    /** The exact connection to which STOP was successfully enqueued. */
    private Channel stopCommandChannel;
    /**
     * An acknowledgement may arrive before the outbound write future completes, so this flag is
     * set immediately before enqueuing STOP.  A disconnect is terminal only after the write was
     * confirmed; before then the caller is still allowed to wait for a reconnect.
     */
    private boolean stopCommandWriteStarted;
    private boolean stopCommandDispatched;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile NioEventLoopGroup bossGroup;
    private volatile NioEventLoopGroup workerGroup;
    private volatile Channel serverChannel;
    private Consumer<CallNode> onRootNode;
    private Consumer<String> onStatusChange;
    private Runnable onReady;
    private long eventCount;
    private long duplicateSpanCount;
    private long discardedSpanCount;
    private volatile ExporterStatistics agentExporterStatistics = ExporterStatistics.unavailable();
    private volatile CaptureQuality lastCaptureQuality = CaptureQuality.unavailable();

    public TraceServer(int port) {
        this(port, AnalysisConfig.disabled());
    }

    public TraceServer(int port, AnalysisConfig analysisConfig) {
        this(port, analysisConfig, null);
    }

    public TraceServer(int port, AnalysisConfig analysisConfig, MonitoringSession session) {
        this.port = port;
        this.monitoringSession = session;
        this.performanceReportCollector = new PerformanceReportCollector(analysisConfig, session);
    }

    public void setOnRootNode(Consumer<CallNode> handler) { this.onRootNode = handler; }
    public void setOnStatusChange(Consumer<String> handler) { this.onStatusChange = handler; }
    public void setOnReady(Runnable handler) { this.onReady = handler; }

    public int start() throws Exception {
        performanceReportCollector.start();
        stopping = false;
        stopAcknowledgement = null;
        resetCaptureQuality();
        resetControlChannelState();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socket) {
                            socket.pipeline().addLast(new LineBasedFrameDecoder(MAX_FRAME_BYTES));
                            socket.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                            socket.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                            socket.pipeline().addLast(new CollectorHandler());
                        }
                    });
            serverChannel = bootstrap.bind(port == 0 ? 0 : port).sync().channel();
            running = true;
            notifyStatus("Netty Collector 已监听: " + serverChannel.localAddress());
            return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        } catch (Exception e) {
            stop();
            throw e;
        }
    }

    /**
     * Requests a controlled detach and waits only for the bounded control protocol timeout.
     * A non-restored result means the App must not claim the target bytecode was restored.
     */
    public StopResult stopAndAwaitRestore() {
        synchronized (stopLock) {
            if (serverChannel == null) {
                return finishSession(StopResult.notConfirmed("Collector 未启动，无法确认 Agent 状态"));
            }
            stopping = true;
            running = false;
            CompletableFuture<StopResult> acknowledgement = new CompletableFuture<>();
            stopAcknowledgement = acknowledgement;
            clearStopCommandState();
            StopResult result;
            try {
                if (currentControlChannel() == null) {
                    notifyStatus("等待 Agent 控制连接重连以发送 STOP");
                }
                boolean commandSent = dispatchStopToCanonicalChannel(acknowledgement);
                result = commandSent
                        ? null
                        : StopResult.notConfirmed("3 秒内未等到可用 Agent 控制连接，无法发送 STOP");
                if (result == null) result = acknowledgement.get(STOP_ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                result = StopResult.notConfirmed("8 秒内未收到 Agent 的停止回执（字节码还原和输出 drain 状态均未确认）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = StopResult.notConfirmed("等待 Agent 停止回执时被中断");
            } catch (ExecutionException e) {
                result = StopResult.notConfirmed("Agent 停止回执异常: " + messageOf(e.getCause()));
            }
            return finishSession(result);
        }
    }

    /** Releases the Collector without waiting for a target-Agent acknowledgement. */
    public void stop() {
        running = false;
        stopping = false;
        finishSession(StopResult.notConfirmed("Collector 在未确认 Agent 状态时关闭"));
    }

    public java.nio.file.Path getPerformanceReportPath() {
        return performanceReportCollector.reportPath();
    }

    /**
     * Sends STOP to exactly one connection: whichever registered Agent channel is canonical at
     * the instant the write is enqueued.  If that channel vanished before the write was
     * confirmed, it is safe to wait for the Agent reconnect grace window and try the new
     * canonical channel.  Once the write succeeds we must not send STOP again: the Agent may
     * already be restoring bytecode and its control response is tied to that connection.
     */
    private boolean dispatchStopToCanonicalChannel(CompletableFuture<StopResult> acknowledgement) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AGENT_RECONNECT_GRACE_MILLIS);
        while (remainingMillis(deadlineNanos) > 0) {
            Channel candidate = awaitCanonicalControlChannel(deadlineNanos);
            if (candidate == null) return acknowledgement.isDone();

            ChannelFuture write;
            synchronized (controlChannelLock) {
                if (controlChannel != candidate || !candidate.isActive()) continue;
                stopCommandChannel = candidate;
                stopCommandWriteStarted = true;
                stopCommandDispatched = false;
                write = candidate.writeAndFlush(STOP_COMMAND);
            }

            long writeTimeoutMillis = Math.min(STOP_COMMAND_WRITE_TIMEOUT_MILLIS, remainingMillis(deadlineNanos));
            if (writeTimeoutMillis > 0 && write.awaitUninterruptibly(writeTimeoutMillis) && write.isSuccess()) {
                boolean disconnectedAfterDispatch;
                synchronized (controlChannelLock) {
                    stopCommandDispatched = true;
                    disconnectedAfterDispatch = !candidate.isActive();
                }
                if (disconnectedAfterDispatch && !acknowledgement.isDone()) {
                    acknowledgement.complete(StopResult.notConfirmed(
                            "Agent 在 STOP 命令写入后、还原回执前断开连接"));
                }
                return true;
            }

            // A response proves the Agent received STOP even if the local write future raced
            // with channel teardown.  Otherwise abandon this pre-dispatch attempt and wait for
            // a replacement canonical connection.
            if (acknowledgement.isDone()) return true;
            abandonUnconfirmedStopCommand(candidate);
            if (candidate.isActive()) candidate.close();
        }
        return acknowledgement.isDone();
    }

    private Channel awaitCanonicalControlChannel(long deadlineNanos) {
        while (remainingMillis(deadlineNanos) > 0) {
            CompletableFuture<Channel> available;
            synchronized (controlChannelLock) {
                Channel current = controlChannel;
                if (current != null && current.isActive()) return current;
                // channelInactive is dispatched asynchronously.  Do not repeatedly await an
                // already-completed future for a channel which has already gone inactive.
                if (nextControlChannel.isDone()) nextControlChannel = new CompletableFuture<>();
                available = nextControlChannel;
            }
            try {
                available.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException | TimeoutException ignored) {
                return null;
            }
        }
        return null;
    }

    private void closeInfrastructure() {
        Channel currentServer = serverChannel;
        if (currentServer != null) currentServer.close().awaitUninterruptibly(SERVER_CLOSE_TIMEOUT_MILLIS);
        closeGroup(workerGroup);
        closeGroup(bossGroup);
        serverChannel = null;
        workerGroup = null;
        bossGroup = null;
        synchronized (traceStates) { traceStates.clear(); }
        resetControlChannelState();
    }

    public long getEventCount() { return eventCount; }

    /** Final snapshot remains available after the Collector releases its Netty resources. */
    public CaptureQuality getLastCaptureQuality() { return lastCaptureQuality; }

    private void accept(SpanEvent span) {
        // SQL is dependency evidence and must be attached to another trace span, never a root.
        if (span.kind() == SpanKind.SQL && span.parentSpanId().isBlank()) {
            recordDiscardedSpan();
            return;
        }
        CallNode completedRoot = null;
        String completedTraceId = null;
        synchronized (traceStates) {
            TraceState trace = traceStates.computeIfAbsent(span.traceId(), ignored -> new TraceState());
            if (trace.nodes.containsKey(span.spanId())) {
                duplicateSpanCount++;
                return;
            }
            CallNode node = new CallNode(span.className(), span.methodName(), span.threadName(),
                    span.durationNanos(), span.error(), span.depth(), span.signature(),
                    span.arguments(), span.returnValue(), span.stackTrace(), span.kind(), span.attributes());
            trace.nodes.put(span.spanId(), node);

            List<CallNode> children = trace.waitingChildren.remove(span.spanId());
            if (children != null) node.getChildren().addAll(children);

            if (span.parentSpanId().isBlank()) {
                completedRoot = node;
                completedTraceId = span.traceId();
                traceStates.remove(span.traceId());
            } else {
                CallNode parent = trace.nodes.get(span.parentSpanId());
                if (parent != null) {
                    parent.getChildren().add(node);
                } else {
                    trace.waitingChildren.computeIfAbsent(span.parentSpanId(), ignored -> new ArrayList<>()).add(node);
                }
            }
            eventCount++;
        }
        if (completedRoot != null) performanceReportCollector.accept(completedTraceId, completedRoot);
        if (completedRoot != null && onRootNode != null) onRootNode.accept(completedRoot);
        if (eventCount > 0 && eventCount % 50 == 0) notifyStatus("已捕获 " + eventCount + " 个完成 Span");
    }

    /** Persists the session's stable resource even when raw file export is disabled. */
    private void acceptResource(String message) {
        if (monitoringSession == null || message == null || message.isBlank()) return;
        try {
            JsonNode incoming = PROTOCOL_MAPPER.readTree(message);
            JsonNode attributes = incoming.path("attributes");
            if (!attributes.isObject()) return;
            com.fasterxml.jackson.databind.node.ObjectNode document = PROTOCOL_MAPPER.createObjectNode();
            document.put("schemaVersion", TelemetryProtocol.SCHEMA_VERSION);
            document.set("attributes", attributes);
            Path target = monitoringSession.resourcePath();
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, PROTOCOL_MAPPER.writeValueAsString(document) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            notifyStatus("无法写入 Agent Resource: " + messageOf(error));
        }
    }

    /** Keeps final Agent-side loss and sink rejection counters as structured report data. */
    private void acceptOutputQuality(Channel channel, String message) {
        if (stopping && channel != stopCommandChannel) return;
        try {
            JsonNode incoming = PROTOCOL_MAPPER.readTree(message);
            if (!incoming.path("reported").asBoolean(false)) return;
            agentExporterStatistics = new ExporterStatistics(true,
                    incoming.path("enqueuedSpans").asLong(0),
                    incoming.path("queueDroppedSpans").asLong(0),
                    incoming.path("deliveryDroppedSpans").asLong(0),
                    incoming.path("rejectedDestinations").asLong(0),
                    incoming.path("pendingSpans").asLong(0));
        } catch (Exception error) {
            notifyStatus("无法解析 Agent 采集质量数据: " + messageOf(error));
        }
    }

    private void onAgentConnected(Channel channel) {
        synchronized (controlChannelLock) {
            controlChannel = channel;
            nextControlChannel.complete(channel);
        }
        notifyStatus("Agent 已连接: " + channel.remoteAddress());
    }

    private void onAgentDisconnected(Channel channel) {
        boolean wasCanonical;
        boolean commandWasDispatched;
        synchronized (controlChannelLock) {
            wasCanonical = controlChannel == channel;
            if (wasCanonical) {
                controlChannel = null;
                if (nextControlChannel.isDone()) nextControlChannel = new CompletableFuture<>();
            }
            commandWasDispatched = stopCommandChannel == channel && stopCommandDispatched;
        }
        CompletableFuture<StopResult> acknowledgement = stopAcknowledgement;
        if (stopping && acknowledgement != null && !acknowledgement.isDone()) {
            if (commandWasDispatched) {
                acknowledgement.complete(StopResult.notConfirmed("Agent 在发送还原回执前断开连接"));
            } else if (wasCanonical) {
                notifyStatus("Agent 控制连接已断开，等待重连以发送 STOP");
            }
        }
        if (running && wasCanonical) notifyStatus("Agent 连接已断开");
    }

    private void onStopAcknowledgement(Channel channel, StopResult result) {
        CompletableFuture<StopResult> acknowledgement = stopAcknowledgement;
        boolean belongsToStopCommand;
        synchronized (controlChannelLock) {
            belongsToStopCommand = stopCommandWriteStarted && stopCommandChannel == channel;
        }
        if (stopping && acknowledgement != null && belongsToStopCommand) acknowledgement.complete(result);
    }

    private Channel currentControlChannel() {
        synchronized (controlChannelLock) {
            return controlChannel != null && controlChannel.isActive() ? controlChannel : null;
        }
    }

    private void abandonUnconfirmedStopCommand(Channel channel) {
        synchronized (controlChannelLock) {
            if (stopCommandChannel == channel && !stopCommandDispatched) {
                stopCommandChannel = null;
                stopCommandWriteStarted = false;
            }
        }
    }

    private void clearStopCommandState() {
        synchronized (controlChannelLock) {
            stopCommandChannel = null;
            stopCommandWriteStarted = false;
            stopCommandDispatched = false;
        }
    }

    private void resetControlChannelState() {
        synchronized (controlChannelLock) {
            controlChannel = null;
            nextControlChannel = new CompletableFuture<>();
            stopCommandChannel = null;
            stopCommandWriteStarted = false;
            stopCommandDispatched = false;
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private static String messageOf(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) return "未知错误";
        return error.getMessage();
    }

    private StopResult finishSession(StopResult result) {
        // A reset-only stop is safe for the target JVM but its final telemetry tail is not a
        // complete capture. Keep that distinction in the durable report consumed by AI tooling.
        ReportStatus reportStatus = result.restored() && result.outputDrained()
                ? ReportStatus.COMPLETED : ReportStatus.INCOMPLETE;
        CaptureQuality quality = captureQuality();
        lastCaptureQuality = quality;
        performanceReportCollector.finish(reportStatus, result.message(), quality);
        closeInfrastructure();
        return result.withCaptureQuality(quality);
    }

    private CaptureQuality captureQuality() {
        synchronized (traceStates) {
            return new CaptureQuality(agentExporterStatistics, eventCount, duplicateSpanCount, discardedSpanCount);
        }
    }

    private void resetCaptureQuality() {
        synchronized (traceStates) {
            eventCount = 0;
            duplicateSpanCount = 0;
            discardedSpanCount = 0;
        }
        agentExporterStatistics = ExporterStatistics.unavailable();
        lastCaptureQuality = CaptureQuality.unavailable();
    }

    private void recordDiscardedSpan() {
        synchronized (traceStates) {
            discardedSpanCount++;
        }
    }

    private void notifyStatus(String message) {
        Consumer<String> handler = onStatusChange;
        if (handler != null) handler.accept(message);
    }

    private static void closeGroup(NioEventLoopGroup group) {
        if (group != null) group.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private final class CollectorHandler extends SimpleChannelInboundHandler<String> {
        private boolean registered;

        @Override
        protected void channelRead0(ChannelHandlerContext context, String message) {
            ProtocolEnvelope envelope = parseEnvelope(message);
            if (!envelope.isCurrentSchema()) return;
            switch (envelope.type()) {
                case HELLO -> register(context.channel());
                case RESOURCE -> acceptResource(message);
                case OUTPUT_QUALITY -> acceptOutputQuality(context.channel(), message);
                case READY -> notifyReady(context.channel());
                case DRAIN_REQUEST -> acknowledgeDrain(context);
                case STOPPED -> onStopAcknowledgement(context.channel(), StopResult.success(envelope.message()));
                case DRAIN_FAILED -> onStopAcknowledgement(context.channel(), StopResult.drainFailed(envelope.message()));
                case RESET_FAILED -> onStopAcknowledgement(context.channel(), StopResult.failed(envelope.message()));
                case SPAN -> acceptSpan(message);
                case UNKNOWN -> notifyStatus("忽略未知 Agent 协议消息");
            }
        }

        private void register(Channel channel) {
            if (registered) return;
            registered = true;
            onAgentConnected(channel);
        }

        private void notifyReady(Channel channel) {
            if (channel == currentControlChannel() && onReady != null) onReady.run();
        }

        /**
         * This response is an end-to-end queue barrier. Netty invokes this handler after all
         * earlier span frames from the same Agent connection have passed through acceptSpan().
         */
        private void acknowledgeDrain(ChannelHandlerContext context) {
            ChannelFuture write = context.writeAndFlush(
                    TelemetryProtocol.message(ProtocolMessageType.DRAIN_ACK) + TelemetryProtocol.NEWLINE);
            write.addListener(future -> {
                if (!future.isSuccess()) notifyStatus("无法回写 Agent drain 回执");
            });
        }

        private void acceptSpan(String message) {
            SpanEvent span = SpanEvent.parse(message);
            if (span != null) accept(span);
            else recordDiscardedSpan();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            if (registered) onAgentDisconnected(context.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            context.close();
        }
    }

    private static ProtocolEnvelope parseEnvelope(String payload) {
        try {
            JsonNode node = PROTOCOL_MAPPER.readTree(payload);
            return new ProtocolEnvelope(ProtocolMessageType.fromWireValue(
                    node.path(TelemetryProtocol.TYPE_FIELD).asText()),
                    node.path(TelemetryProtocol.SCHEMA_VERSION_FIELD).asInt(),
                    node.path(TelemetryProtocol.MESSAGE_FIELD).asText(""));
        } catch (Exception ignored) {
            return new ProtocolEnvelope(ProtocolMessageType.UNKNOWN, 0, "");
        }
    }

    public enum RestoreStatus {
        RESTORED,
        DRAIN_FAILED,
        RESET_FAILED,
        NOT_CONFIRMED
    }

    public record StopResult(RestoreStatus status, String message, CaptureQuality captureQuality) {
        public StopResult {
            captureQuality = captureQuality == null ? CaptureQuality.unavailable() : captureQuality;
        }

        public static StopResult success() {
            return success("字节码已还原且尾部输出已 drain/flush");
        }

        public static StopResult success(String message) {
            return new StopResult(RestoreStatus.RESTORED, messageOf(message), CaptureQuality.unavailable());
        }

        /** Bytecode is safe to re-instrument, but the final exporter drain was incomplete. */
        public static StopResult drainFailed(String message) {
            return new StopResult(RestoreStatus.DRAIN_FAILED, messageOf(message), CaptureQuality.unavailable());
        }

        public static StopResult failed(String message) {
            return new StopResult(RestoreStatus.RESET_FAILED, messageOf(message), CaptureQuality.unavailable());
        }

        public static StopResult notConfirmed(String message) {
            return new StopResult(RestoreStatus.NOT_CONFIRMED, messageOf(message), CaptureQuality.unavailable());
        }

        public StopResult withCaptureQuality(CaptureQuality quality) {
            return new StopResult(status, message, quality);
        }

        public boolean restored() {
            return status == RestoreStatus.RESTORED || status == RestoreStatus.DRAIN_FAILED;
        }

        public boolean outputDrained() {
            return status == RestoreStatus.RESTORED;
        }

        private static String messageOf(String message) {
            return message == null || message.isBlank() ? "未知原因" : message;
        }
    }

    private static final class TraceState {
        private final Map<String, CallNode> nodes = new LinkedHashMap<>();
        private final Map<String, List<CallNode>> waitingChildren = new HashMap<>();
    }
}
