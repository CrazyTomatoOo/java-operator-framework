package com.huawei.dcs.modelengine.operator.framework.metrics;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsServerTest {

    private HttpServer server;
    private MetricsServer metricsServer;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        metricsServer = new MetricsServer(server);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void metricsEndpointReturnsDefaultMetrics() throws Exception {
        metricsServer.recordReconcile("echo", "success");
        metricsServer.recordReconcileError("echo");
        metricsServer.recordReconcileDuration("echo", Duration.ofMillis(150));

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/metrics"))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("operator_reconcile_total"));
        assertTrue(body.contains("operator_reconcile_errors_total"));
        assertTrue(body.contains("operator_reconcile_duration_seconds"));
        assertTrue(body.contains("controller=\"echo\""));
    }
}
