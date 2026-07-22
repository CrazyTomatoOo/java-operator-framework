package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookCertificateGeneratorTest {
    @TempDir
    private Path tempDir;

    @Test
    void generateCreatesCaAndServerCertificateForWebhookService() throws Exception {
        WebhookCertificateGenerator generator = WebhookCertificateGenerator.builder("webhook", "operators")
                .validity(Duration.ofDays(30))
                .keySize(2048)
                .signatureAlgorithm("SHA256withRSA")
                .extraSubjectAlternativeNames(List.of("webhook.internal"))
                .clusterDomain("example.local")
                .build();

        GeneratedCertificate generated = generator.generate();

        assertNull(generated.caPath());
        assertNull(generated.serverCertificatePath());
        assertNull(generated.serverPrivateKeyPath());
        assertEquals(Base64.getEncoder().encodeToString(generated.caCertificatePem()), generated.caBundleBase64());
        assertCaCertificate(generated.caCertificate());
        assertServerCertificate(generated.serverCertificate(), generated.caCertificate());
        assertSubjectAlternativeNames(generated.serverCertificate(), List.of("webhook", "webhook.operators",
                "webhook.operators.svc", "webhook.operators.svc.example.local", "webhook.internal"));
        assertPemBlock(generated.caCertificatePem(), "CERTIFICATE");
        assertPemBlock(generated.serverCertificatePem(), "CERTIFICATE");
        assertPemBlock(generated.serverPrivateKeyPem(), "PRIVATE KEY");
        assertEquals(2048, assertInstanceOf(RSAPrivateKey.class, generated.serverPrivateKey()).getModulus().bitLength());
    }

    @Test
    void generateWritesPemFilesAndReturnsWrittenPaths() throws Exception {
        WebhookCertificateGenerator generator = WebhookCertificateGenerator.builder("hooks", "default").build();

        GeneratedCertificate generated = generator.generate(tempDir);

        Path caPath = tempDir.resolve("ca.crt");
        Path serverCertificatePath = tempDir.resolve("tls.crt");
        Path serverPrivateKeyPath = tempDir.resolve("tls.key");
        assertEquals(caPath, generated.caPath());
        assertEquals(serverCertificatePath, generated.serverCertificatePath());
        assertEquals(serverPrivateKeyPath, generated.serverPrivateKeyPath());
        assertArrayEquals(generated.caCertificatePem(), Files.readAllBytes(caPath));
        assertArrayEquals(generated.serverCertificatePem(), Files.readAllBytes(serverCertificatePath));
        assertArrayEquals(generated.serverPrivateKeyPem(), Files.readAllBytes(serverPrivateKeyPath));
        assertEquals(generated.caCertificate(), readCertificate(caPath));
        assertEquals(generated.serverCertificate(), readCertificate(serverCertificatePath));
        assertPosixPermissions(caPath, "rw-r--r--");
        assertPosixPermissions(serverCertificatePath, "rw-r--r--");
        assertPosixPermissions(serverPrivateKeyPath, "rw-------");
    }

    private static void assertCaCertificate(X509Certificate certificate) throws Exception {
        certificate.verify(certificate.getPublicKey());
        assertEquals(0, certificate.getBasicConstraints());
        boolean[] keyUsage = certificate.getKeyUsage();
        assertTrue(keyUsage[5]);
        assertTrue(keyUsage[6]);
        assertNotNull(certificate.getExtensionValue("2.5.29.14"));
    }

    private static void assertServerCertificate(X509Certificate serverCertificate, X509Certificate caCertificate)
            throws Exception {
        serverCertificate.verify(caCertificate.getPublicKey());
        assertEquals(-1, serverCertificate.getBasicConstraints());
        boolean[] keyUsage = serverCertificate.getKeyUsage();
        assertTrue(keyUsage[0]);
        assertTrue(keyUsage[2]);
        assertEquals(List.of("1.3.6.1.5.5.7.3.1"), serverCertificate.getExtendedKeyUsage());
        assertNotNull(serverCertificate.getExtensionValue("2.5.29.35"));
    }

    private static void assertSubjectAlternativeNames(X509Certificate certificate, List<String> expectedNames)
            throws Exception {
        Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
        assertNotNull(subjectAlternativeNames);
        List<String> dnsNames = subjectAlternativeNames.stream()
                .filter(name -> Integer.valueOf(2).equals(name.get(0)))
                .map(name -> (String) name.get(1))
                .toList();
        assertEquals(expectedNames, dnsNames);
    }

    private static void assertPosixPermissions(Path path, String expected) throws Exception {
        if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(expected);
        assertEquals(permissions, Files.getPosixFilePermissions(path));
    }

    private static void assertPemBlock(byte[] pemBytes, String label) {
        String pem = new String(pemBytes, StandardCharsets.US_ASCII);
        assertTrue(pem.startsWith("-----BEGIN " + label + "-----\n"));
        assertTrue(pem.endsWith("-----END " + label + "-----\n"));
    }

    private static X509Certificate readCertificate(Path path) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (var input = Files.newInputStream(path)) {
            return (X509Certificate) factory.generateCertificate(input);
        }
    }
}
