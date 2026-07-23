package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Objects;

/**
 * Holds a generated webhook certificate authority, server certificate, server private key, PEM payloads, and optional
 * filesystem locations.
 *
 * @param caCertificate the self-signed certificate authority certificate
 * @param caPrivateKey the certificate authority private key
 * @param serverCertificate the server certificate signed by the certificate authority
 * @param serverPrivateKey the server certificate private key
 * @param caCertificatePem the certificate authority certificate in PEM format
 * @param caPrivateKeyPem the certificate authority private key in PEM format
 * @param serverCertificatePem the server certificate in PEM format
 * @param serverPrivateKeyPem the server private key in PEM format
 * @param caPath the written certificate authority PEM path, or {@code null} when generated in memory only
 * @param serverCertificatePath the written server certificate PEM path, or {@code null} when generated in memory only
 * @param serverPrivateKeyPath the written server private key PEM path, or {@code null} when generated in memory only
 */
public record GeneratedCertificate(X509Certificate caCertificate, PrivateKey caPrivateKey,
        X509Certificate serverCertificate, PrivateKey serverPrivateKey, byte[] caCertificatePem,
        byte[] caPrivateKeyPem, byte[] serverCertificatePem, byte[] serverPrivateKeyPem, Path caPath,
        Path serverCertificatePath, Path serverPrivateKeyPath) {
    /**
     * Creates a generated certificate result.
     */
    public GeneratedCertificate {
        Objects.requireNonNull(caCertificate, "caCertificate must not be null");
        Objects.requireNonNull(caPrivateKey, "caPrivateKey must not be null");
        Objects.requireNonNull(serverCertificate, "serverCertificate must not be null");
        Objects.requireNonNull(serverPrivateKey, "serverPrivateKey must not be null");
        caCertificatePem = Objects.requireNonNull(caCertificatePem, "caCertificatePem must not be null").clone();
        caPrivateKeyPem = Objects.requireNonNull(caPrivateKeyPem, "caPrivateKeyPem must not be null").clone();
        serverCertificatePem = Objects.requireNonNull(serverCertificatePem, "serverCertificatePem must not be null")
                .clone();
        serverPrivateKeyPem = Objects.requireNonNull(serverPrivateKeyPem, "serverPrivateKeyPem must not be null")
                .clone();
    }

    /**
     * Returns the certificate authority PEM bytes base64-encoded for Kubernetes WebhookClientConfig.caBundle.
     *
     * @return the base64-encoded CA certificate PEM bytes
     */
    public String caBundleBase64() {
        return Base64.getEncoder().encodeToString(this.caCertificatePem);
    }

    @Override
    public byte[] caCertificatePem() {
        return this.caCertificatePem.clone();
    }

    @Override
    public byte[] caPrivateKeyPem() {
        return this.caPrivateKeyPem.clone();
    }

    @Override
    public byte[] serverCertificatePem() {
        return this.serverCertificatePem.clone();
    }

    @Override
    public byte[] serverPrivateKeyPem() {
        return this.serverPrivateKeyPem.clone();
    }
}
