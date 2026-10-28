package demo.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Small, dependency-free local JVM target for desktop monitoring checks. */
public final class TestHttpService {
    private final BusinessWork work = new BusinessWork();

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be 0–65535");
        var service = new TestHttpService();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", exchange -> service.reply(exchange, 200, "OK\n"));
        server.createContext("/work", exchange -> service.reply(exchange, 200, service.work.calculate() + "\n"));
        server.createContext("/slow", exchange -> {
            try {
                service.work.pause();
                service.reply(exchange, 200, service.work.calculate() + "\n");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                service.reply(exchange, 503, "interrupted\n");
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("READY pid=" + ProcessHandle.current().pid() + " port=" + server.getAddress().getPort());
        System.out.flush();
    }

    private void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    public static final class BusinessWork {
        public long calculate() {
            long result = 0;
            for (int i = 0; i < 100_000; i++) result += (i * 31L) % 97;
            return result;
        }

        public void pause() throws InterruptedException { Thread.sleep(250); }
    }
}
