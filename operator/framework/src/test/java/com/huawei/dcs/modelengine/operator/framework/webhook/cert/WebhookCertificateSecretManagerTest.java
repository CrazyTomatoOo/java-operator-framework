package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.mockwebserver.http.RecordedRequest;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnableKubernetesMockClient(crud = true)
class WebhookCertificateSecretManagerTest {
    private static final String SECRET_NAME = "webhook-ca";
    private static final String SECRET_NAMESPACE = "operator-system";
    private static final String SERVICE_NAME = "webhook-service";
    private static final String SERVICE_NAMESPACE = "operator-system";

    @TempDir
    private Path tempDir;

    KubernetesMockServer server;
    KubernetesClient client;

    @Test
    void missingSecretGeneratesAndCreatesSecretWithoutWritingCaKey() throws Exception {
        expectSecretGet(404, new StatusBuilder().withCode(404).build());
        server.expect().post().withPath(secretsPath()).andReturn(201, new SecretBuilder().build()).once();

        GeneratedCertificate generated = manager().resolve();
        RecordedRequest request = server.getLastRequest();
        Secret created = new ObjectMapper().readValue(request.getBody().inputStream(), Secret.class);

        assertLocalFiles(generated);
        assertEquals(Map.of("app.kubernetes.io/managed-by", "operator-framework"), created.getMetadata().getLabels());
        assertEquals("Opaque", created.getType());
        assertEquals(Map.of("ca.crt", Base64.getEncoder().encodeToString(generated.caCertificatePem()),
                "ca.key", Base64.getEncoder().encodeToString(generated.caPrivateKeyPem())), created.getData());
        assertEquals(tempDir.resolve("ca.crt"), generated.caPath());
        assertEquals(tempDir.resolve("tls.crt"), generated.serverCertificatePath());
        assertEquals(tempDir.resolve("tls.key"), generated.serverPrivateKeyPath());
        assertPosixPermissions(tempDir.resolve("ca.crt"), "rw-r--r--");
        assertPosixPermissions(tempDir.resolve("tls.crt"), "rw-r--r--");
        assertPosixPermissions(tempDir.resolve("tls.key"), "rw-------");
        assertFalse(Files.exists(tempDir.resolve("ca.key")));
    }

    @Test
    void existingSecretReusesCaAndRegeneratesOnlyServerCertificate() throws Exception {
        GeneratedCertificate stored = generator().generate();
        expectSecretGet(200, secret(stored));

        GeneratedCertificate resolved = manager().resolve();

        assertArrayEquals(stored.caCertificatePem(), resolved.caCertificatePem());
        assertEquals(stored.caCertificate(), resolved.caCertificate());
        assertNotEquals(stored.serverCertificate().getSerialNumber(), resolved.serverCertificate().getSerialNumber());
        assertArrayEquals(stored.caCertificatePem(), Files.readAllBytes(tempDir.resolve("ca.crt")));
        assertFalse(Files.exists(tempDir.resolve("ca.key")));
    }

    @Test
    void createConflictRereadsSecretAndUsesStableCa() throws Exception {
        GeneratedCertificate winningCertificate = generator().generate();
        expectSecretGet(404, new StatusBuilder().withCode(404).build());
        server.expect().post().withPath(secretsPath()).andReturn(409,
                new StatusBuilder().withCode(409).withMessage("already created").build()).once();
        expectSecretGet(200, secret(winningCertificate));

        GeneratedCertificate resolved = manager().resolve();

        assertArrayEquals(winningCertificate.caCertificatePem(), resolved.caCertificatePem());
        assertEquals(winningCertificate.caCertificate(), readCertificate(tempDir.resolve("ca.crt")));
        assertTrue(Files.exists(tempDir.resolve("tls.crt")));
        assertTrue(Files.exists(tempDir.resolve("tls.key")));
        assertFalse(Files.exists(tempDir.resolve("ca.key")));
    }

    @Test
    void concurrentResolveCallsConvergeOnOneSecret() throws Exception {
        GeneratedCertificate stored = generator().generate();
        server.expect().get().withPath(secretPath()).andReturn(200, secret(stored)).times(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<GeneratedCertificate> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return manager().resolve();
            });
            Future<GeneratedCertificate> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return manager().resolve();
            });

            start.countDown();

            GeneratedCertificate resolvedOne = first.get(30, TimeUnit.SECONDS);
            GeneratedCertificate resolvedTwo = second.get(30, TimeUnit.SECONDS);

            assertArrayEquals(stored.caCertificatePem(), resolvedOne.caCertificatePem());
            assertArrayEquals(stored.caCertificatePem(), resolvedTwo.caCertificatePem());
            assertArrayEquals(resolvedOne.caCertificatePem(), resolvedTwo.caCertificatePem());
            assertArrayEquals(resolvedOne.caCertificatePem(), Files.readAllBytes(tempDir.resolve("ca.crt")));
            byte[] localServerCertificate = Files.readAllBytes(tempDir.resolve("tls.crt"));
            byte[] localServerPrivateKey = Files.readAllBytes(tempDir.resolve("tls.key"));
            boolean firstWriterWon = Arrays.equals(resolvedOne.serverCertificatePem(), localServerCertificate)
                    && Arrays.equals(resolvedOne.serverPrivateKeyPem(), localServerPrivateKey);
            boolean secondWriterWon = Arrays.equals(resolvedTwo.serverCertificatePem(), localServerCertificate)
                    && Arrays.equals(resolvedTwo.serverPrivateKeyPem(), localServerPrivateKey);
            assertTrue(firstWriterWon || secondWriterWon);
            assertFalse(Files.exists(tempDir.resolve("ca.key")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidBase64FailsWithSecretContext() {
        Secret secret = new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SECRET_NAME).withNamespace(SECRET_NAMESPACE).build())
                .withData(Map.of("ca.crt", "not-base64", "ca.key", "also-not-base64"))
                .build();
        expectSecretGet(200, secret);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> manager().resolve());

        assertTrue(exception.getMessage().contains("invalid base64"));
        assertTrue(exception.getMessage().contains(SECRET_NAMESPACE + "/" + SECRET_NAME));
    }

    @Test
    void mismatchedCaKeyFailsClearly() throws Exception {
        GeneratedCertificate certificate = generator().generate();
        GeneratedCertificate other = generator().generate();
        Secret secret = secret(certificate, other.caPrivateKeyPem());
        expectSecretGet(200, secret);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> manager().resolve());

        assertTrue(exception.getMessage().contains("invalid CA certificate or key material"));
        assertFalse(Files.exists(tempDir.resolve("ca.key")));
    }

    private WebhookCertificateSecretManager manager() {
        return new WebhookCertificateSecretManager(client, SECRET_NAME, SECRET_NAMESPACE, SERVICE_NAME,
                SERVICE_NAMESPACE, tempDir);
    }

    private WebhookCertificateGenerator generator() {
        return WebhookCertificateGenerator.builder(SERVICE_NAME, SERVICE_NAMESPACE).build();
    }

    private Secret secret(GeneratedCertificate generated) {
        return secret(generated, generated.caPrivateKeyPem());
    }

    private Secret secret(GeneratedCertificate generated, byte[] privateKeyPem) {
        return new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SECRET_NAME).withNamespace(SECRET_NAMESPACE).build())
                .withType("Opaque")
                .withData(Map.of(
                        "ca.crt", Base64.getEncoder().encodeToString(generated.caCertificatePem()),
                        "ca.key", Base64.getEncoder().encodeToString(privateKeyPem)))
                .build();
    }

    private void expectSecretGet(int status, Object response) {
        server.expect().get().withPath(secretPath()).andReturn(status, response).once();
    }

    private static String secretPath() {
        return "/api/v1/namespaces/" + SECRET_NAMESPACE + "/secrets/" + SECRET_NAME;
    }

    private static String secretsPath() {
        return "/api/v1/namespaces/" + SECRET_NAMESPACE + "/secrets";
    }

    private void assertLocalFiles(GeneratedCertificate generated) throws Exception {
        assertArrayEquals(generated.caCertificatePem(), Files.readAllBytes(tempDir.resolve("ca.crt")));
        assertArrayEquals(generated.serverCertificatePem(), Files.readAllBytes(tempDir.resolve("tls.crt")));
        assertArrayEquals(generated.serverPrivateKeyPem(), Files.readAllBytes(tempDir.resolve("tls.key")));
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (var input = Files.newInputStream(path)) {
            return (X509Certificate) factory.generateCertificate(input);
        }
    }

    private static void assertPosixPermissions(Path path, String expected) throws Exception {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(expected);
        assertEquals(permissions, Files.getPosixFilePermissions(path));
    }

}
