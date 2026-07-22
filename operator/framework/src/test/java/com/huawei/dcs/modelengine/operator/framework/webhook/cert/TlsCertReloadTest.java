package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import com.sun.net.httpserver.HttpExchange;
import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsCertReloadTest {
    @TempDir
    private Path tempDir;

    @Test
    void reloadsTlsCertificateFilesWithoutRestartAndKeepsPreviousContextWhenReplacementIsInvalid() throws Exception {
        TestCertificate initial = generateCertificate("initial");
        WebhookServer server = WebhookServer.withCertWatcher("localhost", 0, initial.certPath(), initial.keyPath(), null,
                Duration.ofMillis(200));
        server.register("/hook", exchange -> write(exchange, 200, "ok"));

        try {
            server.start();
            assertHttpsRequestSucceeds(server);
            assertEquals(initial.serialNumber(), peerSerial(server));

            TestCertificate rotated = generateCertificate("rotated");

            BigInteger activeSerial = waitForSerial(server, rotated.serialNumber());

            assertEquals(rotated.serialNumber(), activeSerial);
            assertNotEquals(initial.serialNumber(), activeSerial);
            assertHttpsRequestSucceeds(server);

            Files.writeString(rotated.certPath(), "not a certificate", StandardCharsets.US_ASCII);
            TimeUnit.MILLISECONDS.sleep(800);

            assertEquals(rotated.serialNumber(), peerSerial(server));
            assertHttpsRequestSucceeds(server);
        } finally {
            server.stop();
        }
    }

    private BigInteger waitForSerial(WebhookServer server, BigInteger expectedSerial) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Exception lastException = null;
        while (System.nanoTime() < deadline) {
            try {
                BigInteger serial = peerSerial(server);
                if (expectedSerial.equals(serial)) {
                    return serial;
                }
            } catch (Exception e) {
                lastException = e;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        if (lastException != null) {
            throw lastException;
        }
        return peerSerial(server);
    }

    private void assertHttpsRequestSucceeds(WebhookServer server) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(trustAllSslContext())
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + server.address().getPort() + "/hook"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    private BigInteger peerSerial(WebhookServer server) throws Exception {
        try (SSLSocket socket = (SSLSocket) trustAllSslContext().getSocketFactory()
                .createSocket("localhost", server.address().getPort())) {
            socket.startHandshake();
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSerialNumber();
        }
    }

    private TestCertificate generateCertificate(String name) throws Exception {
        String password = "changeit" + HexFormat.of().formatHex(new SecureRandom().generateSeed(8));
        Path keyStorePath = tempDir.resolve(name + ".p12");
        runKeytool(List.of("-genkeypair", "-alias", "webhook", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "7", "-storetype", "PKCS12", "-keystore", keyStorePath.toString(), "-storepass",
                password, "-keypass", password, "-dname", "CN=localhost", "-ext",
                "SAN=dns:localhost,ip:127.0.0.1"));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, password.toCharArray());
        }

        X509Certificate certificate = (X509Certificate) keyStore.getCertificate("webhook");
        Key privateKey = keyStore.getKey("webhook", password.toCharArray());
        Path certPath = tempDir.resolve("tls.crt");
        Path keyPath = tempDir.resolve("tls.key");
        writePem(certPath, "CERTIFICATE", certificate.getEncoded());
        writePem(keyPath, "PRIVATE KEY", privateKey.getEncoded());
        return new TestCertificate(certPath, keyPath, certificate.getSerialNumber());
    }

    private static void runKeytool(List<String> arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(arguments));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("keytool failed with exit code " + exitCode + ": " + output);
        }
    }

    private static List<String> command(List<String> arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", keytoolName()).toString());
        command.addAll(arguments);
        return command;
    }

    private static String keytoolName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
    }

    private static void writePem(Path path, String label, byte[] der) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        Files.writeString(path, "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n",
                StandardCharsets.US_ASCII);
    }

    private static SSLContext trustAllSslContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        return context;
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private record TestCertificate(Path certPath, Path keyPath, BigInteger serialNumber) {
    }
}
