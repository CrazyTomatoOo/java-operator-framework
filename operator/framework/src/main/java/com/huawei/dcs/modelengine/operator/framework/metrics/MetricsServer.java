package com.huawei.dcs.modelengine.operator.framework.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exposes a Prometheus metrics endpoint on a shared or dedicated JDK {@link HttpServer}.
 */
public class MetricsServer implements AutoCloseable {

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8080;

    public static final String RECONCILE_TOTAL = "operator_reconcile_total";
    public static final String RECONCILE_ERRORS_TOTAL = "operator_reconcile_errors_total";
    public static final String RECONCILE_DURATION_SECONDS = "operator_reconcile_duration_seconds";

    public static final String TAG_CONTROLLER = "controller";
    public static final String TAG_RESULT = "result";

    private final PrometheusMeterRegistry registry;
    private final HttpServer server;
    private final boolean ownServer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public MetricsServer(String host, int port) throws IOException {
        this(createServer(host, port), true);
    }

    public MetricsServer(HttpServer server) {
        this(server, false);
    }

    private MetricsServer(HttpServer server, boolean ownServer) {
        this.server = server;
        this.ownServer = ownServer;
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        server.createContext("/metrics", this::handleMetrics);
    }

    private static HttpServer createServer(String host, int port) throws IOException {
        return HttpServer.create(new InetSocketAddress(host, port), 0);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        String body;
        int status;
        try {
            body = registry.scrape();
            status = 200;
        } catch (RuntimeException e) {
            body = "Failed to scrape metrics: " + e.getMessage();
            status = 500;
        }
        sendResponse(exchange, status, body, status == 200 ? "text/plain; version=0.0.4; charset=utf-8" : "text/plain; charset=utf-8");
    }

    private void sendResponse(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public MeterRegistry registry() {
        return registry;
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    public void recordReconcile(String controller, String result) {
        Counter.builder(RECONCILE_TOTAL)
            .description("Total number of reconcile operations")
            .tags(TAG_CONTROLLER, controller, TAG_RESULT, result)
            .register(registry)
            .increment();
    }

    public void recordReconcileError(String controller) {
        Counter.builder(RECONCILE_ERRORS_TOTAL)
            .description("Total number of reconcile errors")
            .tags(TAG_CONTROLLER, controller)
            .register(registry)
            .increment();
    }

    public void recordReconcileDuration(String controller, Duration duration) {
        Timer.builder(RECONCILE_DURATION_SECONDS)
            .description("Duration of reconcile operations in seconds")
            .tags(TAG_CONTROLLER, controller)
            .register(registry)
            .record(duration);
    }

    public void start() {
        if (ownServer) {
            server.start();
        }
    }

    public void stop() {
        if (ownServer && !closed.getAndSet(true)) {
            server.stop(0);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
