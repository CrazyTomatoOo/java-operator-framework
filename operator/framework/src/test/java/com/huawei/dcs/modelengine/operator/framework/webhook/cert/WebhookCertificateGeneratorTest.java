package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Signature;
import java.security.GeneralSecurityException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertPemBlock(generated.caPrivateKeyPem(), "PRIVATE KEY");
        assertCaPrivateKeyMatchesCertificate(generated.caCertificate(), generated.caPrivateKey());
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
    void generateServerCertificateUsesExistingCaAndKeepsServerAuthEku() throws Exception {
        WebhookCertificateGenerator generator = WebhookCertificateGenerator.builder("webhook", "operators")
                .validity(Duration.ofDays(30))
                .extraSubjectAlternativeNames(List.of("webhook.internal"))
                .clusterDomain("example.local")
                .build();

        GeneratedCertificate ca = generator.generate();
        GeneratedCertificate generated = generator.generateServerCertificate(ca.caCertificate(), ca.caPrivateKey());

        assertNull(generated.caPath());
        assertNull(generated.serverCertificatePath());
        assertNull(generated.serverPrivateKeyPath());
        assertEquals(ca.caCertificate(), generated.caCertificate());
        assertEquals(ca.caPrivateKey(), generated.caPrivateKey());
        assertCaPrivateKeyMatchesCertificate(generated.caCertificate(), generated.caPrivateKey());
        assertServerCertificate(generated.serverCertificate(), generated.caCertificate());
        assertSubjectAlternativeNames(generated.serverCertificate(), List.of("webhook", "webhook.operators",
                "webhook.operators.svc", "webhook.operators.svc.example.local", "webhook.internal"));
        assertCertificateValidity(generated.serverCertificate(), Duration.ofDays(30));
        assertPemBlock(generated.caCertificatePem(), "CERTIFICATE");
        assertPemBlock(generated.caPrivateKeyPem(), "PRIVATE KEY");
        assertPemBlock(generated.serverCertificatePem(), "CERTIFICATE");
        assertPemBlock(generated.serverPrivateKeyPem(), "PRIVATE KEY");
        assertEquals(2048, assertInstanceOf(RSAPrivateKey.class, generated.serverPrivateKey()).getModulus().bitLength());
    }

    @Test
    void generateServerCertificateRejectsMismatchedCaPrivateKey() throws Exception {
        WebhookCertificateGenerator generator = WebhookCertificateGenerator.builder("webhook", "operators").build();

        GeneratedCertificate ca = generator.generate();
        GeneratedCertificate other = generator.generate();

        assertThrows(GeneralSecurityException.class,
                () -> generator.generateServerCertificate(ca.caCertificate(), other.caPrivateKey()));
    }

    @Test
    void generatedCertificateRejectsNullCaPrivateKey() throws Exception {
        WebhookCertificateGenerator generator = WebhookCertificateGenerator.builder("webhook", "operators")
                .build();
        GeneratedCertificate generated = generator.generate();

        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new GeneratedCertificate(generated.caCertificate(), null, generated.serverCertificate(),
                        generated.serverPrivateKey(), generated.caCertificatePem(), generated.caPrivateKeyPem(),
                        generated.serverCertificatePem(), generated.serverPrivateKeyPem(), null, null, null));

        assertEquals("caPrivateKey must not be null", exception.getMessage());
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

    private static void assertCertificateValidity(X509Certificate certificate, Duration expectedValidity) {
        assertEquals(expectedValidity.toMillis(),
                certificate.getNotAfter().getTime() - certificate.getNotBefore().getTime());
    }

    private static void assertCaPrivateKeyMatchesCertificate(X509Certificate caCertificate,
            java.security.PrivateKey caPrivateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        byte[] data = "certificate-authority-key-match".getBytes(StandardCharsets.US_ASCII);
        signature.initSign(caPrivateKey);
        signature.update(data);
        byte[] signed = signature.sign();

        signature.initVerify(caCertificate.getPublicKey());
        signature.update(data);
        assertTrue(signature.verify(signed));
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
