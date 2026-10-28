package com.agentmonitor.core.internal;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded response and whole-request deadline, including a blocked socket write. */
public final class BoundedOtlpClient implements AutoCloseable {
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, task -> { var thread = new Thread(task,"otlp-http"); thread.setDaemon(true); return thread; });
    private final HttpClient client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofMillis(750)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public HttpResponse<byte[]> post(URI endpoint, byte[] bytes) throws Exception {
        if (closed) throw new IllegalStateException("OTLP transport closed");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).header("Content-Type","application/x-protobuf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        var future = client.sendAsync(request, info -> new LimitedBody());
        active.add(future);
        try { if (closed) future.cancel(true); return future.get(2100,TimeUnit.MILLISECONDS); }
        finally { active.remove(future); if (!future.isDone()) future.cancel(true); }
    }
    public void close() { closed = true; active.forEach(future -> future.cancel(true)); executor.shutdownNow(); }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE - bytes.size()) { subscription.cancel(); result.completeExceptionally(new IllegalArgumentException("OTLP response too large")); return; }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
