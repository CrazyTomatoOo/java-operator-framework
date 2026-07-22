package com.example.echooperator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EchoOperatorMainTest {

    @TempDir
    Path tempDir;

    @Test
    void loadConfigReadsPropertiesAndDefaults() {
        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig();
        assertEquals("test-ns", config.operatorNamespace());
        assertEquals(9090, config.metricsPort());
        assertTrue(config.leaderElectionEnabled());
        assertEquals("test-leader-ns", config.leaderElectionNamespace());
        assertEquals("test-lock", config.leaderElectionLockName());
        assertEquals(Path.of("/etc/echo-operator/certs/ca.crt"), config.webhookCaBundlePath());
        assertTrue(config.webhookCertAutoGenerate());
        assertEquals(Path.of("/tmp/echo-operator/certs"), config.webhookCertDirectory());
        assertEquals(8443, config.webhookPort());
        assertEquals(443, config.webhookServicePort());
    }

    @Test
    void createWiresOperatorMetricsServer() throws IOException {
        KubernetesClient client = client();
        TestCertificate certificate = generateCertificate();
        EchoOperatorMain.OperatorConfig config = new EchoOperatorMain.OperatorConfig("test-ns", 0, false,
                "test-ns", "echo-operator-lock", certificate.caPath(), false, tempDir.resolve("unused-certs"), 0, 443);
        EchoOperatorMain main = EchoOperatorMain.create(client, config);

        assertNotNull(main.operator());
        assertEquals(0, main.operator().eventSources().size());
        assertNotNull(main.metricsHealthServer());
        assertEquals(1, main.metricsHealthServer().readinessChecks().size());
        assertFalse(main.metricsHealthServer().healthServer().isReady());
        assertNotNull(main.webhookServer());
        assertTrue(main.admissionHandler().validatorNames().contains("echo.example.com"));
        assertTrue(main.admissionHandler().mutatorNames().contains("echo.example.com"));
        assertNotNull(main.conversionHandler());
        assertNotNull(main.webhookSelfRegistration());

        main.stop();
    }

    @Test
    void createGeneratesWebhookCertificatesWhenAutoGenerateEnabled() throws IOException {
        Path certDirectory = tempDir.resolve("generated-certs");
        EchoOperatorMain.OperatorConfig config = new EchoOperatorMain.OperatorConfig("test-ns", 0, false,
                "test-ns", "echo-operator-lock", tempDir.resolve("fallback-ca.crt"), true, certDirectory, 0, 443);
        EchoOperatorMain main = EchoOperatorMain.create(client(), config);

        assertTrue(Files.exists(certDirectory.resolve("ca.crt")));
        assertTrue(Files.exists(certDirectory.resolve("tls.crt")));
        assertTrue(Files.exists(certDirectory.resolve("tls.key")));

        main.stop();
    }

    private KubernetesClient client() {
        KubernetesClient client = mock(KubernetesClient.class);
        when(client.getKubernetesSerialization()).thenReturn(new KubernetesSerialization());
        return client;
    }

    private TestCertificate generateCertificate() throws IOException {
        try {
            String password = "changeit" + HexFormat.of().formatHex(new SecureRandom().generateSeed(8));
            Path keyStorePath = tempDir.resolve("webhook-" + password + ".p12");
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
            Path caPath = tempDir.resolve("ca.crt");
            writePem(certPath, "CERTIFICATE", certificate.getEncoded());
            writePem(keyPath, "PRIVATE KEY", privateKey.getEncoded());
            writePem(caPath, "CERTIFICATE", certificate.getEncoded());
            return new TestCertificate(certPath, keyPath, caPath);
        } catch (Exception exception) {
            throw new IOException("Failed to generate test certificate", exception);
        }
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
        List<String> command = new ArrayList<>();
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

    private record TestCertificate(Path certPath, Path keyPath, Path caPath) {
    }
}
