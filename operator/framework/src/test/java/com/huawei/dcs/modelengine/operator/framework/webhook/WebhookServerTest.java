package com.huawei.dcs.modelengine.operator.framework.webhook;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookServerTest {
    @TempDir
    private Path tempDir;

    @Test
    void registeredHandlerRespondsOverHttps() throws Exception {
        TestCertificate certificate = generateCertificate();
        WebhookServer server = new WebhookServer("localhost", 0, certificate.certPath(), certificate.keyPath());
        server.register("/hook", exchange -> write(exchange, 201, "accepted"));

        try {
            server.start();
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(trustAllSslContext())
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://localhost:" + server.address().getPort() + "/hook"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(201, response.statusCode());
            assertEquals("accepted", response.body());
            assertNotNull(server.sslContext().sslContext());
        } finally {
            server.stop();
        }
    }

    @Test
    void reloadRebuildsSslContextFromDisk() throws Exception {
        TestCertificate certificate = generateCertificate();
        ReloadableSslContext sslContext = new ReloadableSslContext(certificate.certPath(), certificate.keyPath());
        SSLContext before = sslContext.sslContext();

        sslContext.reload();

        assertNotNull(sslContext.sslContext());
        assertTrue(before != sslContext.sslContext());
    }

    private TestCertificate generateCertificate() throws Exception {
        String password = "changeit" + HexFormat.of().formatHex(new SecureRandom().generateSeed(8));
        Path keyStorePath = tempDir.resolve("webhook.p12");
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
        return new TestCertificate(certPath, keyPath);
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

    private record TestCertificate(Path certPath, Path keyPath) {
    }
}
