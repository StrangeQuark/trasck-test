package com.strangequark.trascktest.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalHttpReceiver implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final URI publicBaseUrl;
    private final CopyOnWriteArrayList<ReceivedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger responseStatus = new AtomicInteger(202);

    private LocalHttpReceiver(HttpServer server, ExecutorService executor, URI publicBaseUrl) {
        this.server = server;
        this.executor = executor;
        this.publicBaseUrl = publicBaseUrl;
    }

    public static LocalHttpReceiver start(String bindHost, int port, URI publicBaseUrl) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            LocalHttpReceiver receiver = new LocalHttpReceiver(server, executor, publicBaseUrl);
            server.createContext("/", receiver::handle);
            server.setExecutor(executor);
            server.start();
            return receiver;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to start local HTTP receiver on " + bindHost + ":" + port, ex);
        }
    }

    public URI url(String path) {
        return publicBaseUrl.resolve(path.startsWith("/") ? path : "/" + path);
    }

    public void setResponseStatus(int status) {
        responseStatus.set(status);
    }

    public List<ReceivedRequest> requests() {
        return new ArrayList<>(requests);
    }

    public void clear() {
        requests.clear();
    }

    public boolean awaitRequestCount(int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (requests.size() >= expected) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return requests.size() >= expected;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        requests.add(new ReceivedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(),
                new String(requestBody, StandardCharsets.UTF_8)
        ));
        byte[] response = "{\"received\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public record ReceivedRequest(
            String method,
            String path,
            Map<String, List<String>> headers,
            String body
    ) {
        public String firstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return "";
        }
    }
}
