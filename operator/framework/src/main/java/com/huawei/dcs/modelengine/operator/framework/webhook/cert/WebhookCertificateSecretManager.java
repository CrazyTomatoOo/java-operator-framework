package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Persists a webhook certificate authority in a Kubernetes Secret and writes only the runtime server certificate
 * material to the local certificate directory.
 */
public final class WebhookCertificateSecretManager {
    private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";
    private static final String MANAGED_BY_VALUE = "operator-framework";
    private static final String CA_CERTIFICATE_KEY = "ca.crt";
    private static final String CA_PRIVATE_KEY_KEY = "ca.key";
    private static final String SERVER_CERTIFICATE_KEY = "tls.crt";
    private static final String SERVER_PRIVATE_KEY_KEY = "tls.key";

    private final KubernetesClient client;
    private final String secretName;
    private final String secretNamespace;
    private final WebhookCertificateGenerator generator;
    private final Path certDirectory;

    /**
     * Creates a manager for a webhook certificate authority Secret.
     *
     * @param client the Kubernetes client
     * @param secretName the Secret name
     * @param secretNamespace the Secret namespace
     * @param serviceName the webhook Service name
     * @param serviceNamespace the webhook Service namespace
     * @param certDirectory the directory receiving {@code ca.crt}, {@code tls.crt}, and {@code tls.key}
     */
    public WebhookCertificateSecretManager(KubernetesClient client, String secretName, String secretNamespace,
            String serviceName, String serviceNamespace, Path certDirectory) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.secretName = requireText(secretName, "secretName");
        this.secretNamespace = requireText(secretNamespace, "secretNamespace");
        this.generator = WebhookCertificateGenerator.builder(serviceName, serviceNamespace).build();
        this.certDirectory = Objects.requireNonNull(certDirectory, "certDirectory must not be null");
    }

    /**
     * Resolves the CA Secret, creating it when necessary, and writes the current server certificate files locally.
     *
     * @return the resolved certificate material
     * @throws IOException if certificate encoding or local file persistence fails
     * @throws GeneralSecurityException if certificate generation fails
     */
    public GeneratedCertificate resolve() throws IOException, GeneralSecurityException {
        Secret existing = readSecret();
        if (existing != null) {
            return resolveExisting(existing);
        }
        return generateAndCreate();
    }

    private GeneratedCertificate resolveExisting(Secret secret) throws IOException, GeneralSecurityException {
        CaMaterial ca = readCaMaterial(secret);
        GeneratedCertificate generated = this.generator.generateServerCertificate(ca.certificate(), ca.privateKey());
        writeLocalFiles(generated);
        return withLocalPaths(generated);
    }

    private GeneratedCertificate generateAndCreate() throws IOException, GeneralSecurityException {
        GeneratedCertificate generated = this.generator.generate();
        StagedFiles stagedFiles = stageLocalFiles(generated);
        try {
            createSecret(generated);
        } catch (KubernetesClientException exception) {
            if (exception.getCode() != 409) {
                deleteStagedFiles(stagedFiles);
                throw exception;
            }
            deleteStagedFiles(stagedFiles);
            Secret existing = readSecret();
            if (existing == null) {
                throw new IllegalStateException("Secret " + secretNamespace + "/" + secretName
                        + " was not found after a create conflict", exception);
            }
            return resolveExisting(existing);
        }
        promote(stagedFiles);
        return withLocalPaths(generated);
    }

    private Secret readSecret() {
        return this.client.secrets().inNamespace(this.secretNamespace).withName(this.secretName).get();
    }

    private void createSecret(GeneratedCertificate generated) {
        Map<String, String> data = Map.of(
                CA_CERTIFICATE_KEY, encode(generated.caCertificatePem()),
                CA_PRIVATE_KEY_KEY, encode(generated.caPrivateKeyPem()));
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(this.secretName)
                .withNamespace(this.secretNamespace)
                .withLabels(Map.of(MANAGED_BY_LABEL, MANAGED_BY_VALUE))
                .endMetadata()
                .withType("Opaque")
                .withData(data)
                .build();
        this.client.secrets().inNamespace(this.secretNamespace).resource(secret).create();
    }

    private CaMaterial readCaMaterial(Secret secret) {
        Map<String, String> data = secret.getData();
        if (data == null) {
            throw invalidSecret("does not contain data", null);
        }
        byte[] certificatePem = decode(data, CA_CERTIFICATE_KEY);
        byte[] privateKeyPem = decode(data, CA_PRIVATE_KEY_KEY);
        try {
            List<X509Certificate> certificates = PemCertificateUtils.readCertificates(certificatePem);
            if (certificates.size() != 1) {
                throw new IOException("expected exactly one X.509 certificate");
            }
            X509Certificate certificate = certificates.getFirst();
            PrivateKey privateKey = PemCertificateUtils.readPrivateKey(privateKeyPem);
            validateKeyMatchesCertificate(certificate, privateKey);
            return new CaMaterial(certificate, privateKey);
        } catch (IOException | GeneralSecurityException exception) {
            throw invalidSecret("contains invalid CA certificate or key material", exception);
        }
    }

    private byte[] decode(Map<String, String> data, String key) {
        String encoded = data.get(key);
        if (encoded == null) {
            throw invalidSecret("is missing data entry " + key, null);
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw invalidSecret("contains invalid base64 for " + key, exception);
        }
    }

    private static void validateKeyMatchesCertificate(X509Certificate certificate, PrivateKey privateKey)
            throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withRSA");
        byte[] challenge = "operator-framework-webhook-ca-key-match".getBytes(StandardCharsets.US_ASCII);
        signature.initSign(privateKey);
        signature.update(challenge);
        byte[] signed = signature.sign();
        signature.initVerify(certificate.getPublicKey());
        signature.update(challenge);
        if (!signature.verify(signed)) {
            throw new GeneralSecurityException("CA private key does not match CA certificate");
        }
    }

    private void writeLocalFiles(GeneratedCertificate generated) throws IOException {
        StagedFiles stagedFiles = stageLocalFiles(generated);
        promote(stagedFiles);
    }

    private GeneratedCertificate withLocalPaths(GeneratedCertificate generated) {
        return new GeneratedCertificate(generated.caCertificate(), generated.caPrivateKey(),
                generated.serverCertificate(), generated.serverPrivateKey(), generated.caCertificatePem(),
                generated.caPrivateKeyPem(), generated.serverCertificatePem(), generated.serverPrivateKeyPem(),
                this.certDirectory.resolve(CA_CERTIFICATE_KEY), this.certDirectory.resolve(SERVER_CERTIFICATE_KEY),
                this.certDirectory.resolve(SERVER_PRIVATE_KEY_KEY));
    }

    private StagedFiles stageLocalFiles(GeneratedCertificate generated) throws IOException {
        Files.createDirectories(this.certDirectory);
        Path caPath = this.certDirectory.resolve(CA_CERTIFICATE_KEY);
        Path serverCertificatePath = this.certDirectory.resolve(SERVER_CERTIFICATE_KEY);
        Path serverPrivateKeyPath = this.certDirectory.resolve(SERVER_PRIVATE_KEY_KEY);
        Path caTemporaryPath = null;
        Path serverCertificateTemporaryPath = null;
        Path serverPrivateKeyTemporaryPath = null;
        try {
            caTemporaryPath = writeTemporary(caPath, generated.caCertificatePem());
            serverCertificateTemporaryPath = writeTemporary(serverCertificatePath, generated.serverCertificatePem());
            serverPrivateKeyTemporaryPath = writeTemporary(serverPrivateKeyPath, generated.serverPrivateKeyPem());
            return new StagedFiles(caTemporaryPath, serverCertificateTemporaryPath, serverPrivateKeyTemporaryPath);
        } catch (IOException exception) {
            deleteIfPresent(caTemporaryPath);
            deleteIfPresent(serverCertificateTemporaryPath);
            deleteIfPresent(serverPrivateKeyTemporaryPath);
            throw exception;
        }
    }

    private static Path writeTemporary(Path target, byte[] bytes) throws IOException {
        Path temporaryPath = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporaryPath, bytes);
            return temporaryPath;
        } catch (IOException exception) {
            Files.deleteIfExists(temporaryPath);
            throw exception;
        }
    }

    private void promote(StagedFiles stagedFiles) throws IOException {
        try {
            moveAtomically(stagedFiles.caTemporaryPath(), this.certDirectory.resolve(CA_CERTIFICATE_KEY));
            setPermissions(this.certDirectory.resolve(CA_CERTIFICATE_KEY), "rw-r--r--");
            moveAtomically(stagedFiles.serverCertificateTemporaryPath(),
                    this.certDirectory.resolve(SERVER_CERTIFICATE_KEY));
            setPermissions(this.certDirectory.resolve(SERVER_CERTIFICATE_KEY), "rw-r--r--");
            moveAtomically(stagedFiles.serverPrivateKeyTemporaryPath(),
                    this.certDirectory.resolve(SERVER_PRIVATE_KEY_KEY));
            setPermissions(this.certDirectory.resolve(SERVER_PRIVATE_KEY_KEY), "rw-------");
        } finally {
            deleteStagedFiles(stagedFiles);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setPermissions(Path path, String permissions) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> filePermissions = PosixFilePermissions.fromString(permissions);
            Files.setPosixFilePermissions(path, filePermissions);
        }
    }

    private static void deleteStagedFiles(StagedFiles stagedFiles) {
        deleteIfPresent(stagedFiles.caTemporaryPath());
        deleteIfPresent(stagedFiles.serverCertificateTemporaryPath());
        deleteIfPresent(stagedFiles.serverPrivateKeyTemporaryPath());
    }

    private static void deleteIfPresent(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original operation's exception is more useful than cleanup failure.
        }
    }

    private IllegalStateException invalidSecret(String reason, Exception cause) {
        return new IllegalStateException("Secret " + secretNamespace + "/" + secretName + " " + reason, cause);
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record CaMaterial(X509Certificate certificate, PrivateKey privateKey) {
    }

    private record StagedFiles(Path caTemporaryPath, Path serverCertificateTemporaryPath,
            Path serverPrivateKeyTemporaryPath) {
    }
}
