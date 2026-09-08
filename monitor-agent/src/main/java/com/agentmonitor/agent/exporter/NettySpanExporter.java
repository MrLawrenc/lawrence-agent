package com.agentmonitor.agent.exporter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import com.agentmonitor.agent.log.AgentLog;
import com.agentmonitor.agent.model.SpanData;
import com.agentmonitor.agent.protocol.SpanPayloadSerializer;
import com.agentmonitor.bootstrap.bridge.BootstrapBridge;
import com.agentmonitor.model.output.ExporterType;
import com.agentmonitor.model.output.ExporterStatistics;
import com.agentmonitor.model.protocol.ProtocolMessageType;
import com.agentmonitor.model.protocol.TelemetryProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 * Netty client for the local or remote Collector's newline-delimited protocol.
 *
 * <p>After its initial successful connection, this exporter reconnects asynchronously when the
 * Collector disappears. Span frames are deliberately at-most-once: a frame is never buffered or
 * replayed across a reconnect, because replaying an uncertain write could duplicate a trace.
 * The enclosing {@link AsyncSpanExporter} accounts for frames rejected while no channel is live.</p>
 */
final class NettySpanExporter implements SpanExporter {

    private static final int MAX_FRAME_BYTES = 1_048_576;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int CHANNEL_CLOSE_TIMEOUT_MILLIS = 2_000;
    private static final int STOP_ACK_WRITE_TIMEOUT_MILLIS = 1_000;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 2;
    private static final long RECONNECT_INITIAL_DELAY_MILLIS = 100;
    private static final long RECONNECT_MAX_DELAY_MILLIS = 2_000;
    private static final int RECONNECT_MAX_EXPONENT = 5;

    private final String host;
    private final int port;
    private final SpanPayloadSerializer serializer;
    private final Map<String, String> resourceAttributes;
    private final NioEventLoopGroup group = new NioEventLoopGroup(1, agentThreadFactory());
    private final Object drainLock = new Object();
    private final Object reconnectLock = new Object();
    /** Publishes a channel only after its HELLO/READY handshake frames are enqueued. */
    private final Object channelHandshakeLock = new Object();
    private final AtomicReference<CompletableFuture<Void>> drainAcknowledgement = new AtomicReference<>();
    private final AtomicReference<Channel> channel = new AtomicReference<>();
    private final AtomicReference<ChannelFuture> connectingFuture = new AtomicReference<>();
    private final AtomicBoolean startAttempted = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean stopInProgress = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();

    private volatile Bootstrap bootstrap;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile Channel drainChannel;

    NettySpanExporter(String host, int port, SpanPayloadSerializer serializer) {
        this(host, port, serializer, Map.of());
    }

    NettySpanExporter(String host, int port, SpanPayloadSerializer serializer, Map<String, String> resourceAttributes) {
        this.host = host;
        this.port = port;
        this.serializer = serializer;
        this.resourceAttributes = Map.copyOf(resourceAttributes == null ? Map.of() : resourceAttributes);
    }

    private static ThreadFactory agentThreadFactory() {
        ClassLoader runtimeLoader = NettySpanExporter.class.getClassLoader();
        return runnable -> {
            Thread thread = new Thread(runnable, "agent-monitor-netty");
            thread.setDaemon(true);
            thread.setContextClassLoader(runtimeLoader);
            return thread;
        };
    }

    @Override
    public String name() { return ExporterType.NETTY.configValue(); }

    @Override
    public boolean start() {
        if (closed.get() || !startAttempted.compareAndSet(false, true)) return running.get();
        try {
            bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socket) {
                            socket.pipeline().addLast(new LineBasedFrameDecoder(MAX_FRAME_BYTES));
                            socket.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
                            socket.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
                            socket.pipeline().addLast(new CommandHandler());
                        }
                    });

            // Preserve the existing attach contract: an unavailable Collector makes this output
            // unavailable at startup. Reconnect is enabled only after a live monitoring session
            // has successfully started.
            ChannelFuture initialConnect = bootstrap.connect(host, port);
            if (!initialConnect.awaitUninterruptibly(CONNECT_TIMEOUT_MILLIS) || !initialConnect.isSuccess()) {
                AgentLog.error("[agent-monitor] Netty Collector connect failed " + host + ":" + port
                        + ": " + messageOf(initialConnect.cause()));
                return false;
            }
            if (closed.get()) {
                initialConnect.channel().close();
                return false;
            }
            running.set(true);
            // The peer can disappear in the small gap between TCP connect completion and
            // publication of the channel.  Do not claim startup succeeded without either a
            // published channel (whose close listener can reconnect) or a clear startup failure.
            if (!installConnectedChannel(initialConnect.channel())) {
                running.set(false);
                AgentLog.error("[agent-monitor] Netty Collector closed before initial handshake "
                        + host + ":" + port);
                return false;
            }
            return true;
        } catch (Throwable error) {
            AgentLog.error("[agent-monitor] Netty Collector setup failed " + host + ":" + port
                    + ": " + messageOf(error));
            return false;
        }
    }

    @Override
    public ExportResult export(SpanData span) {
        Channel activeChannel = channel.get();
        if (activeChannel == null || !activeChannel.isActive() || !activeChannel.isWritable()) {
            return ExportResult.rejected();
        }
        // writeAndFlush only enqueues work on Netty's event loop; it never waits on an
        // instrumented application thread.
        activeChannel.writeAndFlush(serializer.serialize(span) + TelemetryProtocol.NEWLINE);
        return ExportResult.accepted();
    }

    @Override
    public void ready() {
        // READY is a session state, not an edge-triggered command. Reconnect will replay it
        // exactly once after HELLO on the newly published channel.
        if (!ready.compareAndSet(false, true)) return;
        synchronized (channelHandshakeLock) {
            Channel activeChannel = channel.get();
            if (activeChannel != null && activeChannel.isActive()) {
                writeProtocol(activeChannel, serializer.readyMessage());
            }
        }
    }

    @Override
    public ExporterDrainResult drain(long timeoutMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        synchronized (drainLock) {
            // drain() is the terminal STOP phase for this exporter. Freeze reconnect before
            // taking the channel snapshot so a later reconnect cannot move the drain barrier.
            draining.set(true);
            cancelScheduledReconnect();
            cancelConnectingAttempt();
            Channel activeChannel;
            synchronized (channelHandshakeLock) {
                // Linearize the terminal flag, the channel snapshot, and channel publication:
                // an already-completing reconnect may finish its HELLO, but it cannot publish a
                // new channel after STOP has chosen its drain boundary.
                activeChannel = channel.get();
            }
            if (activeChannel == null || !activeChannel.isActive()) {
                return ExporterDrainResult.failed(0, 0, 0,
                        "Netty 控制连接不可用，无法确认尾部 Span 已送达 Collector");
            }
            CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
            if (!drainAcknowledgement.compareAndSet(null, acknowledgement)) {
                return ExporterDrainResult.failed(0, 0, 0, "已有 Netty drain 正在等待 Collector 回执");
            }
            drainChannel = activeChannel;
            try {
                // This frame is ordered after every write submitted by the async worker. The
                // Collector replies only after its pipeline has processed all preceding spans,
                // giving STOP an end-to-end barrier rather than merely observing an empty queue.
                ChannelFuture write = activeChannel.writeAndFlush(
                        TelemetryProtocol.message(ProtocolMessageType.DRAIN_REQUEST) + TelemetryProtocol.NEWLINE);
                long writeTimeout = remainingMillis(deadlineNanos);
                if (writeTimeout <= 0 || !write.awaitUninterruptibly(writeTimeout) || !write.isSuccess()) {
                    return ExporterDrainResult.failed(0, 0, 0, "Netty drain barrier 未能写入 Collector");
                }
                long acknowledgementTimeout = remainingMillis(deadlineNanos);
                if (acknowledgementTimeout <= 0) {
                    return ExporterDrainResult.failed(0, 0, 0, "等待 Collector drain 回执超时");
                }
                acknowledgement.get(acknowledgementTimeout, TimeUnit.MILLISECONDS);
                return ExporterDrainResult.success();
            } catch (TimeoutException error) {
                return ExporterDrainResult.failed(0, 0, 0, "等待 Collector drain 回执超时");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return ExporterDrainResult.failed(0, 0, 0, "等待 Collector drain 回执时被中断");
            } catch (ExecutionException error) {
                return ExporterDrainResult.failed(0, 0, 0,
                        "Collector drain 回执失败: " + messageOf(error.getCause()));
            } finally {
                drainChannel = null;
                drainAcknowledgement.compareAndSet(acknowledgement, null);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        draining.set(true);
        stopInProgress.set(true);
        cancelScheduledReconnect();
        cancelConnectingAttempt();
        failDrainIfBoundTo(drainChannel, new IllegalStateException("Netty exporter 已关闭"));

        Channel activeChannel;
        synchronized (channelHandshakeLock) {
            activeChannel = channel.getAndSet(null);
        }
        EventLoop eventLoop = group.next();
        boolean onEventLoop = eventLoop.inEventLoop();
        if (activeChannel != null) {
            ChannelFuture closeFuture = activeChannel.close();
            if (!onEventLoop) closeFuture.awaitUninterruptibly(CHANNEL_CLOSE_TIMEOUT_MILLIS);
        }
        var shutdown = group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!onEventLoop) shutdown.awaitUninterruptibly();
    }

    /** Starts one asynchronous reconnect attempt after a previously live session disconnects. */
    private void connectNow() {
        if (!canReconnect() || !connecting.compareAndSet(false, true)) return;
        Channel current = channel.get();
        if (current != null && current.isActive()) {
            connecting.set(false);
            return;
        }
        if (current != null) {
            synchronized (channelHandshakeLock) {
                channel.compareAndSet(current, null);
            }
        }
        Bootstrap currentBootstrap = bootstrap;
        if (currentBootstrap == null) {
            connecting.set(false);
            return;
        }
        try {
            ChannelFuture future = currentBootstrap.connect(host, port);
            connectingFuture.set(future);
            future.addListener((ChannelFutureListener) completed -> onReconnectCompleted(completed, future));
        } catch (Throwable error) {
            connecting.set(false);
            scheduleReconnect("连接 Collector 时发生异常: " + messageOf(error));
        }
    }

    private void onReconnectCompleted(ChannelFuture completed, ChannelFuture expectedFuture) {
        connectingFuture.compareAndSet(expectedFuture, null);
        connecting.set(false);
        if (!completed.isSuccess()) {
            scheduleReconnect("连接 Collector 失败: " + messageOf(completed.cause()));
            return;
        }
        Channel connected = completed.channel();
        if (!canReconnect() || !connected.isActive()) {
            connected.close();
            if (canReconnect()) scheduleReconnect("Collector 连接在握手前关闭");
            return;
        }
        if (!installConnectedChannel(connected) && canReconnect()) {
            scheduleReconnect("Collector 连接在握手期间关闭");
        }
    }

    /**
     * Publishes a live connection only after the handshake order has been queued.  Returning
     * false is important for the initial-connect race: channelInactive may run before a close
     * listener is installed, in which case no retry can be scheduled yet.
     */
    private boolean installConnectedChannel(Channel connected) {
        if (!canReconnect()) {
            connected.close();
            return false;
        }
        Channel previous;
        synchronized (channelHandshakeLock) {
            if (!canReconnect() || !connected.isActive()) {
                connected.close();
                return false;
            }
            // Publish only after protocol ordering is fixed. Business exporters read channel
            // without this lock, so making it visible sooner could put a span before HELLO.
            writeProtocol(connected, TelemetryProtocol.message(ProtocolMessageType.HELLO));
            if (!resourceAttributes.isEmpty()) {
                writeProtocol(connected, TelemetryProtocol.resourceMessage(resourceAttributes));
            }
            if (ready.get()) writeProtocol(connected, serializer.readyMessage());
            previous = channel.getAndSet(connected);
        }
        if (previous != null && previous != connected && previous.isActive()) previous.close();
        connected.closeFuture().addListener(ignored -> onChannelClosed(connected));

        int previousAttempts = reconnectAttempts.getAndSet(0);
        if (previousAttempts > 0) {
            AgentLog.info("[agent-monitor] Netty Collector reconnected " + host + ":" + port
                    + " after " + previousAttempts + " retry attempts");
        }
        return true;
    }

    /** Schedules on the sole Netty event loop, coalescing every disconnect/failure into one task. */
    private void scheduleReconnect(String reason) {
        if (!canReconnect()) return;
        EventLoop eventLoop = group.next();
        if (eventLoop.inEventLoop()) {
            scheduleReconnectOnEventLoop(eventLoop, reason);
            return;
        }
        try {
            eventLoop.execute(() -> scheduleReconnectOnEventLoop(eventLoop, reason));
        } catch (Throwable ignored) {
            // close() may have won the race and shut down the sole event loop.
        }
    }

    private void scheduleReconnectOnEventLoop(EventLoop eventLoop, String reason) {
        if (!canReconnect()) return;
        long delayMillis;
        int attempt;
        synchronized (reconnectLock) {
            if (!canReconnect() || (reconnectTask != null && !reconnectTask.isDone())) return;
            attempt = reconnectAttempts.getAndUpdate(current -> Math.min(current + 1, RECONNECT_MAX_EXPONENT));
            delayMillis = reconnectDelayMillis(attempt);
            try {
                reconnectTask = eventLoop.schedule(() -> {
                    synchronized (reconnectLock) {
                        reconnectTask = null;
                    }
                    connectNow();
                }, delayMillis, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                return;
            }
        }
        // Keep retry diagnostics useful without turning a prolonged Collector outage into log spam.
        if (attempt == 0 || (attempt + 1) % 5 == 0) {
            AgentLog.warn("[agent-monitor] Netty Collector unavailable; retry " + (attempt + 1)
                    + " in " + delayMillis + "ms: " + reason);
        }
    }

    private void onChannelClosed(Channel disconnected) {
        boolean wasCurrent;
        synchronized (channelHandshakeLock) {
            wasCurrent = channel.compareAndSet(disconnected, null);
        }
        failDrainIfBoundTo(disconnected, new IllegalStateException(
                "Netty 控制连接在 drain 回执前断开"));
        if (wasCurrent) scheduleReconnect("Netty 控制连接已断开");
    }

    private boolean canReconnect() {
        return running.get() && !closed.get() && !draining.get() && !stopInProgress.get();
    }

    private void cancelScheduledReconnect() {
        synchronized (reconnectLock) {
            if (reconnectTask != null) {
                reconnectTask.cancel(false);
                reconnectTask = null;
            }
        }
    }

    private void cancelConnectingAttempt() {
        ChannelFuture future = connectingFuture.getAndSet(null);
        if (future != null && !future.isDone()) future.cancel(false);
    }

    private void writeProtocol(Channel target, String payload) {
        if (target == null || !target.isActive()) return;
        target.writeAndFlush(payload + TelemetryProtocol.NEWLINE).addListener(future -> {
            if (!future.isSuccess()) target.close();
        });
    }

    private void failDrainIfBoundTo(Channel disconnected, Throwable cause) {
        if (disconnected == null || drainChannel != disconnected) return;
        CompletableFuture<Void> acknowledgement = drainAcknowledgement.getAndSet(null);
        if (acknowledgement != null) acknowledgement.completeExceptionally(cause);
    }

    private static long reconnectDelayMillis(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt), RECONNECT_MAX_EXPONENT);
        return Math.min(RECONNECT_MAX_DELAY_MILLIS, RECONNECT_INITIAL_DELAY_MILLIS * multiplier);
    }

    private final class CommandHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext context, String message) {
            if (TelemetryProtocol.STOP_COMMAND.equals(message.trim())) {
                // Do not reconnect while the received STOP is restoring bytecode and fixing the
                // final drain boundary. Duplicate STOP frames cannot start duplicate detach work.
                if (!stopInProgress.compareAndSet(false, true)) return;
                cancelScheduledReconnect();
                cancelConnectingAttempt();
                Thread detachThread = new Thread(() -> BootstrapBridge.detachActive(result ->
                        sendDetachResult(context, result)), "agent-monitor-detach");
                detachThread.setDaemon(true);
                detachThread.setContextClassLoader(NettySpanExporter.class.getClassLoader());
                detachThread.start();
            } else if (isProtocolMessage(message, ProtocolMessageType.DRAIN_ACK)) {
                CompletableFuture<Void> acknowledgement = drainAcknowledgement.get();
                if (acknowledgement != null && drainChannel == context.channel()) acknowledgement.complete(null);
            }
        }

        private void sendDetachResult(ChannelHandlerContext context, BootstrapBridge.DetachStatus result) {
            ProtocolMessageType type = !result.restored()
                    ? ProtocolMessageType.RESET_FAILED
                    : result.outputDrained() ? ProtocolMessageType.STOPPED : ProtocolMessageType.DRAIN_FAILED;
            BootstrapBridge.OutputStatistics quality = result.outputStatistics();
            if (quality != null && quality.reported()) {
                ExporterStatistics statistics = new ExporterStatistics(true, quality.enqueuedSpans(),
                        quality.queueDroppedSpans(), quality.deliveryDroppedSpans(),
                        quality.rejectedDestinations(), quality.pendingSpans());
                context.writeAndFlush(TelemetryProtocol.outputQualityMessage(statistics) + TelemetryProtocol.NEWLINE)
                        .awaitUninterruptibly(STOP_ACK_WRITE_TIMEOUT_MILLIS);
            }
            String payload = TelemetryProtocol.message(type, result.message()) + TelemetryProtocol.NEWLINE;
            context.writeAndFlush(payload).awaitUninterruptibly(STOP_ACK_WRITE_TIMEOUT_MILLIS);
            // A reset failure leaves instrumentation active. Resume reconnect eligibility so a
            // concurrent control-channel failure cannot strand the still-running Agent.
            if (!result.restored()) {
                stopInProgress.set(false);
                if (channel.get() == null) scheduleReconnect("STOP 未完成后恢复 Collector 连接");
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            onChannelClosed(context.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            failDrainIfBoundTo(context.channel(), cause);
            context.close();
        }
    }

    private static boolean isProtocolMessage(String message, ProtocolMessageType expectedType) {
        if (message == null || expectedType == null) return false;
        return message.contains("\"" + TelemetryProtocol.TYPE_FIELD + "\":\""
                + expectedType.wireValue() + "\"");
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
}
