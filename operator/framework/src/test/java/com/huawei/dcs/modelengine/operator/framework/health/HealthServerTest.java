package com.huawei.dcs.modelengine.operator.framework.health;

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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthServerTest {

    private HttpServer server;
    private HealthServer healthServer;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        healthServer = new HealthServer(server);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void livenessReturnsOk() throws Exception {
        int status = get("/healthz");
        assertEquals(200, status);
    }

    @Test
    void readinessReturnsOkWhenNoChecks() throws Exception {
        int status = get("/readyz");
        assertEquals(200, status);
    }

    @Test
    void readinessReturnsOkWhenAllChecksPass() throws Exception {
        healthServer.addReadinessCheck(() -> true);
        healthServer.addReadinessCheck(() -> true);
        int status = get("/readyz");
        assertEquals(200, status);
        assertTrue(healthServer.isReady());
    }

    @Test
    void readinessReturnsUnavailableWhenAnyCheckFails() throws Exception {
        AtomicBoolean ready = new AtomicBoolean(true);
        healthServer.addReadinessCheck(() -> true);
        healthServer.addReadinessCheck(ready::get);

        assertEquals(200, get("/readyz"));

        ready.set(false);
        assertEquals(503, get("/readyz"));
        assertFalse(healthServer.isReady());
    }

    @Test
    void readinessReturnsUnavailableWhenCheckThrows() throws Exception {
        healthServer.addReadinessCheck(() -> {
            throw new RuntimeException("boom");
        });
        assertEquals(503, get("/readyz"));
    }

    private int get(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }
}
