package com.huawei.dcs.modelengine.operator.framework.metrics;

import com.huawei.dcs.modelengine.operator.framework.health.HealthServer;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Supplier;

/**
 * Combines metrics and health endpoints on a single JDK {@link HttpServer}.
 */
public class MetricsHealthServer implements AutoCloseable {

    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8080;

    private final HttpServer server;
    private final MetricsServer metricsServer;
    private final HealthServer healthServer;

    public MetricsHealthServer() throws IOException {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public MetricsHealthServer(int port) throws IOException {
        this(DEFAULT_HOST, port);
    }

    public MetricsHealthServer(String host, int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.metricsServer = new MetricsServer(this.server);
        this.healthServer = new HealthServer(this.server);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    @Override
    public void close() {
        stop();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    public MeterRegistry metricsRegistry() {
        return metricsServer.registry();
    }

    public MetricsServer metricsServer() {
        return metricsServer;
    }

    public HealthServer healthServer() {
        return healthServer;
    }

    public void addReadinessCheck(Supplier<Boolean> check) {
        healthServer.addReadinessCheck(check);
    }

    public List<Supplier<Boolean>> readinessChecks() {
        return healthServer.readinessChecks();
    }
}
