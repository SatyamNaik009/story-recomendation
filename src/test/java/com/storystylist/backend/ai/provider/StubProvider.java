package com.storystylist.backend.ai.provider;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import static com.storystylist.backend.ai.AiTestData.JSON;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
/** Loopback-only fake provider; SDKs never receive real keys or external URLs in these tests. */
final class StubProvider implements AutoCloseable {
    final HttpServer server;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String response = "{}";
    volatile long delayMillis;
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "stub-provider"); thread.setDaemon(true); return thread;
    });
    StubProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                if (delayMillis > 0) {
                    try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) { /* A timeout test intentionally closes its socket. */ }
        });
        server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
