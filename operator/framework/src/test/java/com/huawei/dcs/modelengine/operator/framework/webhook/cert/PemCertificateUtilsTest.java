package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PemCertificateUtilsTest {
    @Test
    void readsCertificatesFromPemBytesWithoutDiskAccess() throws Exception {
        GeneratedCertificate generated = generateCertificate();

        List<X509Certificate> certificates = PemCertificateUtils.readCertificates(generated.serverCertificatePem());

        assertEquals(List.of(generated.serverCertificate()), certificates);
    }

    @Test
    void readsPrivateKeyFromPemBytesWithoutDiskAccess() throws Exception {
        GeneratedCertificate generated = generateCertificate();

        PrivateKey privateKey = PemCertificateUtils.readPrivateKey(generated.serverPrivateKeyPem());

        assertArrayEquals(generated.serverPrivateKey().getEncoded(), privateKey.getEncoded());
    }

    @Test
    void rejectsEncryptedPrivateKeyPem() {
        byte[] encryptedPem = "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,0123456789ABCDEF\n\ninvalid\n-----END RSA PRIVATE KEY-----\n"
                .getBytes(StandardCharsets.US_ASCII);

        assertThrows(IOException.class, () -> PemCertificateUtils.readPrivateKey(encryptedPem));
    }

    @Test
    void rejectsInvalidPrivateKeyBase64() {
        byte[] invalidPem = "-----BEGIN PRIVATE KEY-----\nnot-base64!\n-----END PRIVATE KEY-----\n"
                .getBytes(StandardCharsets.US_ASCII);

        assertThrows(IOException.class, () -> PemCertificateUtils.readPrivateKey(invalidPem));
    }

    private static GeneratedCertificate generateCertificate() throws Exception {
        return WebhookCertificateGenerator.builder("webhook", "operators").build().generate();
    }
}
