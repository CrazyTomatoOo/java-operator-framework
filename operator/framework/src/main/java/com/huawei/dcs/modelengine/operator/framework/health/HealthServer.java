package com.huawei.dcs.modelengine.operator.framework.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Exposes liveness and readiness endpoints on a shared JDK {@link HttpServer}.
 */
public class HealthServer {

    public static final String LIVENESS_PATH = "/healthz";
    public static final String READINESS_PATH = "/readyz";

    private final List<Supplier<Boolean>> readinessChecks = new CopyOnWriteArrayList<>();

    public HealthServer(HttpServer server) {
        server.createContext(LIVENESS_PATH, this::handleLiveness);
        server.createContext(READINESS_PATH, this::handleReadiness);
    }

    private void handleLiveness(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 200, "OK");
    }

    private void handleReadiness(HttpExchange exchange) throws IOException {
        boolean ready = evaluateReadiness();
        if (ready) {
            sendResponse(exchange, 200, "OK");
        } else {
            sendResponse(exchange, 503, "Service Unavailable");
        }
    }

    private boolean evaluateReadiness() {
        for (Supplier<Boolean> check : readinessChecks) {
            try {
                if (!Boolean.TRUE.equals(check.get())) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public void addReadinessCheck(Supplier<Boolean> check) {
        readinessChecks.add(check);
    }

    public List<Supplier<Boolean>> readinessChecks() {
        return List.copyOf(readinessChecks);
    }

    public boolean isReady() {
        return evaluateReadiness();
    }
}
