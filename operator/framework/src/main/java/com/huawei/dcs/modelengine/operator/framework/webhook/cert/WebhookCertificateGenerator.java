package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Generates a runtime self-signed certificate authority and a webhook server certificate signed by that authority.
 */
public final class WebhookCertificateGenerator {
    private static final String PROVIDER_NAME = BouncyCastleProvider.PROVIDER_NAME;
    private static final String KEY_ALGORITHM = "RSA";
    private static final String DEFAULT_SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String DEFAULT_CLUSTER_DOMAIN = "cluster.local";
    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(365);
    private static final int DEFAULT_KEY_SIZE = 2048;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String serviceName;
    private final String serviceNamespace;
    private final Duration validity;
    private final int keySize;
    private final String signatureAlgorithm;
    private final List<String> extraSubjectAlternativeNames;
    private final String clusterDomain;

    private WebhookCertificateGenerator(Builder builder) {
        this.serviceName = builder.serviceName;
        this.serviceNamespace = builder.serviceNamespace;
        this.validity = builder.validity;
        this.keySize = builder.keySize;
        this.signatureAlgorithm = builder.signatureAlgorithm;
        this.extraSubjectAlternativeNames = List.copyOf(builder.extraSubjectAlternativeNames);
        this.clusterDomain = builder.clusterDomain;
    }

    /**
     * Creates a builder for a webhook service certificate generator.
     *
     * @param serviceName the Kubernetes Service name
     * @param serviceNamespace the Kubernetes Service namespace
     * @return a configured builder with default cryptographic settings
     */
    public static Builder builder(String serviceName, String serviceNamespace) {
        return new Builder(serviceName, serviceNamespace);
    }

    /**
     * Generates certificates and PEM payloads without writing files.
     *
     * @return the generated certificates, private key, and PEM payloads
     * @throws GeneralSecurityException if key or certificate generation fails
     * @throws IOException if PEM encoding fails
     */
    public GeneratedCertificate generate() throws GeneralSecurityException, IOException {
        return generateCertificate(null, null, null);
    }

    /**
     * Generates certificates and writes {@code ca.crt}, {@code tls.crt}, and {@code tls.key} PEM files atomically.
     *
     * @param directory the directory that receives the generated PEM files
     * @return the generated certificates, private key, PEM payloads, and written paths
     * @throws GeneralSecurityException if key or certificate generation fails
     * @throws IOException if PEM encoding or file writing fails
     */
    public GeneratedCertificate generate(Path directory) throws GeneralSecurityException, IOException {
        Objects.requireNonNull(directory, "directory must not be null");
        Files.createDirectories(directory);
        Path caPath = directory.resolve("ca.crt");
        Path serverCertificatePath = directory.resolve("tls.crt");
        Path serverPrivateKeyPath = directory.resolve("tls.key");
        return generateCertificate(caPath, serverCertificatePath, serverPrivateKeyPath);
    }

    /**
     * Generates a server certificate from an existing certificate authority without writing files.
     *
     * @param caCertificate the existing certificate authority certificate
     * @param caPrivateKey the matching certificate authority private key
     * @return the generated certificate authority and server certificate payloads in memory
     * @throws GeneralSecurityException if key or certificate generation fails
     * @throws IOException if PEM encoding fails
     */
    public GeneratedCertificate generateServerCertificate(X509Certificate caCertificate, java.security.PrivateKey caPrivateKey)
            throws GeneralSecurityException, IOException {
        Objects.requireNonNull(caCertificate, "caCertificate must not be null");
        Objects.requireNonNull(caPrivateKey, "caPrivateKey must not be null");

        ensureBouncyCastleProvider();

        KeyPair caKeyPair = new KeyPair(caCertificate.getPublicKey(), caPrivateKey);
        KeyPair serverKeyPair = generateKeyPair();
        Instant notBefore = Instant.now();
        Instant notAfter = notBefore.plus(this.validity);

        X509Certificate serverCertificate = buildServerCertificate(caCertificate, caKeyPair, serverKeyPair, notBefore,
                notAfter);
        byte[] caPem = writePem(caCertificate);
        byte[] caPrivateKeyPem = writePrivateKeyPem(caPrivateKey);
        byte[] serverCertificatePem = writePem(serverCertificate);
        byte[] serverPrivateKeyPem = writePrivateKeyPem(serverKeyPair.getPrivate());

        return new GeneratedCertificate(caCertificate, caPrivateKey, serverCertificate, serverKeyPair.getPrivate(),
                caPem, caPrivateKeyPem, serverCertificatePem, serverPrivateKeyPem, null, null, null);
    }

    private GeneratedCertificate generateCertificate(Path caPath, Path serverCertificatePath, Path serverPrivateKeyPath)
            throws GeneralSecurityException, IOException {
        ensureBouncyCastleProvider();

        KeyPair caKeyPair = generateKeyPair();
        KeyPair serverKeyPair = generateKeyPair();
        Instant notBefore = Instant.now();
        Instant notAfter = notBefore.plus(this.validity);

        X509Certificate caCertificate = buildCaCertificate(caKeyPair, notBefore, notAfter);
        X509Certificate serverCertificate = buildServerCertificate(caCertificate, caKeyPair, serverKeyPair, notBefore,
                notAfter);
        byte[] caPem = writePem(caCertificate);
        byte[] caPrivateKeyPem = writePrivateKeyPem(caKeyPair.getPrivate());
        byte[] serverCertificatePem = writePem(serverCertificate);
        byte[] serverPrivateKeyPem = writePrivateKeyPem(serverKeyPair.getPrivate());

        if (caPath != null) {
            writeAtomically(caPath, caPem, certificatePermissions());
            writeAtomically(serverCertificatePath, serverCertificatePem, certificatePermissions());
            writeAtomically(serverPrivateKeyPath, serverPrivateKeyPem, privateKeyPermissions());
        }

        return new GeneratedCertificate(caCertificate, caKeyPair.getPrivate(), serverCertificate,
                serverKeyPair.getPrivate(), caPem, caPrivateKeyPem, serverCertificatePem, serverPrivateKeyPem, caPath,
                serverCertificatePath, serverPrivateKeyPath);
    }

    private KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        generator.initialize(this.keySize, SECURE_RANDOM);
        return generator.generateKeyPair();
    }

    private X509Certificate buildCaCertificate(KeyPair caKeyPair, Instant notBefore, Instant notAfter)
            throws GeneralSecurityException, IOException {
        X500Name issuer = new X500Name("CN=" + this.serviceName + " webhook CA");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serialNumber(),
                java.util.Date.from(notBefore), java.util.Date.from(notAfter), issuer, caKeyPair.getPublic());
        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                extensionUtils.createSubjectKeyIdentifier(caKeyPair.getPublic()));
        return convert(builder, caKeyPair.getPrivate(), caKeyPair.getPublic());
    }

    private X509Certificate buildServerCertificate(X509Certificate caCertificate, KeyPair caKeyPair, KeyPair serverKeyPair,
            Instant notBefore, Instant notAfter) throws GeneralSecurityException, IOException {
        X500Name issuer = new X500Name(caCertificate.getSubjectX500Principal().getName());
        X500Name subject = new X500Name("CN=" + primaryDnsName());
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serialNumber(),
                java.util.Date.from(notBefore), java.util.Date.from(notAfter), subject, serverKeyPair.getPublic());
        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                extensionUtils.createAuthorityKeyIdentifier(caCertificate.getPublicKey()));
        builder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNames());
        return convert(builder, caKeyPair.getPrivate(), caCertificate.getPublicKey());
    }

    private X509Certificate convert(X509v3CertificateBuilder builder, java.security.PrivateKey signingKey,
            java.security.PublicKey verificationKey)
            throws GeneralSecurityException, IOException {
        try {
            ContentSigner signer = new JcaContentSignerBuilder(this.signatureAlgorithm).setProvider(PROVIDER_NAME)
                    .build(signingKey);
            X509CertificateHolder holder = builder.build(signer);
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider(PROVIDER_NAME)
                    .getCertificate(holder);
            certificate.verify(verificationKey, PROVIDER_NAME);
            return certificate;
        } catch (CertificateException exception) {
            throw new GeneralSecurityException("Failed to convert generated certificate", exception);
        } catch (org.bouncycastle.operator.OperatorCreationException exception) {
            throw new GeneralSecurityException("Failed to create certificate signer", exception);
        }
    }

    private GeneralNames subjectAlternativeNames() {
        List<GeneralName> names = subjectAlternativeNameValues().stream()
                .map(name -> new GeneralName(GeneralName.dNSName, name))
                .toList();
        return new GeneralNames(names.toArray(GeneralName[]::new));
    }

    private List<String> subjectAlternativeNameValues() {
        Set<String> names = new LinkedHashSet<>();
        names.add(this.serviceName);
        names.add(this.serviceName + "." + this.serviceNamespace);
        names.add(this.serviceName + "." + this.serviceNamespace + ".svc");
        names.add(primaryDnsName());
        names.addAll(this.extraSubjectAlternativeNames);
        return List.copyOf(names);
    }

    private String primaryDnsName() {
        return this.serviceName + "." + this.serviceNamespace + ".svc." + this.clusterDomain;
    }

    private static BigInteger serialNumber() {
        return new BigInteger(160, SECURE_RANDOM).abs().add(BigInteger.ONE);
    }

    private static byte[] writePem(Object object) throws IOException {
        StringWriter stringWriter = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
            pemWriter.writeObject(object);
        }
        return stringWriter.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] writePrivateKeyPem(java.security.PrivateKey privateKey) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(privateKey.getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static void writeAtomically(Path path, byte[] bytes, Set<PosixFilePermission> permissions) throws IOException {
        Path temporaryPath = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.write(temporaryPath, bytes);
            try {
                Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (permissions != null && FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, permissions);
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }

    private static Set<PosixFilePermission> certificatePermissions() {
        return PosixFilePermissions.fromString("rw-r--r--");
    }

    private static Set<PosixFilePermission> privateKeyPermissions() {
        return PosixFilePermissions.fromString("rw-------");
    }

    private static void ensureBouncyCastleProvider() {
        if (Security.getProvider(PROVIDER_NAME) == null) {
            synchronized (WebhookCertificateGenerator.class) {
                if (Security.getProvider(PROVIDER_NAME) == null) {
                    Security.addProvider(new BouncyCastleProvider());
                }
            }
        }
    }

    /**
     * Builds a {@link WebhookCertificateGenerator}.
     */
    public static final class Builder {
        private final String serviceName;
        private final String serviceNamespace;
        private Duration validity = DEFAULT_VALIDITY;
        private int keySize = DEFAULT_KEY_SIZE;
        private String signatureAlgorithm = DEFAULT_SIGNATURE_ALGORITHM;
        private List<String> extraSubjectAlternativeNames = List.of();
        private String clusterDomain = DEFAULT_CLUSTER_DOMAIN;

        private Builder(String serviceName, String serviceNamespace) {
            this.serviceName = requireText(serviceName, "serviceName");
            this.serviceNamespace = requireText(serviceNamespace, "serviceNamespace");
        }

        /**
         * Sets the certificate validity duration.
         *
         * @param validity the positive validity duration
         * @return this builder
         */
        public Builder validity(Duration validity) {
            this.validity = Objects.requireNonNull(validity, "validity must not be null");
            if (this.validity.isZero() || this.validity.isNegative()) {
                throw new IllegalArgumentException("validity must be positive");
            }
            return this;
        }

        /**
         * Sets the RSA key size.
         *
         * @param keySize the RSA key size in bits
         * @return this builder
         */
        public Builder keySize(int keySize) {
            if (keySize <= 0) {
                throw new IllegalArgumentException("keySize must be positive");
            }
            this.keySize = keySize;
            return this;
        }

        /**
         * Sets the certificate signature algorithm.
         *
         * @param signatureAlgorithm the JCA signature algorithm name
         * @return this builder
         */
        public Builder signatureAlgorithm(String signatureAlgorithm) {
            this.signatureAlgorithm = requireText(signatureAlgorithm, "signatureAlgorithm");
            return this;
        }

        /**
         * Adds extra DNS Subject Alternative Names after the default Kubernetes service names.
         *
         * @param extraSubjectAlternativeNames additional DNS Subject Alternative Names
         * @return this builder
         */
        public Builder extraSubjectAlternativeNames(List<String> extraSubjectAlternativeNames) {
            Objects.requireNonNull(extraSubjectAlternativeNames, "extraSubjectAlternativeNames must not be null");
            List<String> names = new ArrayList<>();
            for (String name : extraSubjectAlternativeNames) {
                names.add(requireText(name, "extraSubjectAlternativeNames entry"));
            }
            this.extraSubjectAlternativeNames = List.copyOf(names);
            return this;
        }

        /**
         * Sets the Kubernetes cluster DNS domain.
         *
         * @param clusterDomain the cluster DNS domain
         * @return this builder
         */
        public Builder clusterDomain(String clusterDomain) {
            this.clusterDomain = requireText(clusterDomain, "clusterDomain");
            return this;
        }

        /**
         * Builds the generator.
         *
         * @return the generator
         */
        public WebhookCertificateGenerator build() {
            return new WebhookCertificateGenerator(this);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
