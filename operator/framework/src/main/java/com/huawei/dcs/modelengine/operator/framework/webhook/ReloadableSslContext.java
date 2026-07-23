package com.huawei.dcs.modelengine.operator.framework.webhook;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.security.Principal;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import com.huawei.dcs.modelengine.operator.framework.webhook.cert.PemCertificateUtils;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for an {@link SSLContext} loaded from PEM certificate files.
 */
public final class ReloadableSslContext {
    private static final char[] KEYSTORE_PASSWORD = new char[0];
    private static final String TLS_PROTOCOL = "TLS";

    private final Path certChainPath;
    private final Path privateKeyPath;
    private final Path caPath;
    private final ReloadingKeyManager keyManager = new ReloadingKeyManager();
    private final ReloadingTrustManager trustManager = new ReloadingTrustManager();
    private final AtomicReference<SSLContext> sslContext = new AtomicReference<>();

    public ReloadableSslContext(Path certChainPath, Path privateKeyPath) throws IOException {
        this(certChainPath, privateKeyPath, null);
    }

    public ReloadableSslContext(Path certChainPath, Path privateKeyPath, Path caPath) throws IOException {
        this.certChainPath = Objects.requireNonNull(certChainPath, "certChainPath must not be null");
        this.privateKeyPath = Objects.requireNonNull(privateKeyPath, "privateKeyPath must not be null");
        this.caPath = caPath;
        reload();
    }

    public void reload() throws IOException {
        sslContext.set(buildSslContext());
    }

    public SSLContext sslContext() {
        return sslContext.get();
    }

    private SSLContext buildSslContext() throws IOException {
        try {
            keyManager.setDelegate(loadKeyManager());
            if (caPath != null) {
                trustManager.setDelegate(loadTrustManager());
            }
            SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
            TrustManager[] trustManagers = caPath == null ? null : new TrustManager[] {trustManager};
            context.init(new KeyManager[] {keyManager}, trustManagers, new SecureRandom());
            return context;
        } catch (Exception exception) {
            throw new IOException("Failed to load TLS certificate files", exception);
        }
    }

    private X509ExtendedKeyManager loadKeyManager() throws Exception {
        List<X509Certificate> certificates = PemCertificateUtils.readCertificates(certChainPath);
        if (certificates.isEmpty()) {
            throw new IOException("Certificate chain file does not contain certificates: " + certChainPath);
        }
        PrivateKey privateKey = PemCertificateUtils.readPrivateKey(privateKeyPath);
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, KEYSTORE_PASSWORD);
        keyStore.setKeyEntry("webhook", privateKey, KEYSTORE_PASSWORD, certificates.toArray(Certificate[]::new));

        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, KEYSTORE_PASSWORD);
        for (KeyManager manager : factory.getKeyManagers()) {
            if (manager instanceof X509ExtendedKeyManager x509Manager) {
                return x509Manager;
            }
        }
        throw new IOException("No X509 key manager available for TLS certificate files");
    }

    private X509TrustManager loadTrustManager() throws Exception {
        List<X509Certificate> certificates = PemCertificateUtils.readCertificates(caPath);
        if (certificates.isEmpty()) {
            throw new IOException("CA file does not contain certificates: " + caPath);
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, KEYSTORE_PASSWORD);
        for (int i = 0; i < certificates.size(); i++) {
            trustStore.setCertificateEntry("ca-" + i, certificates.get(i));
        }

        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509Manager) {
                return x509Manager;
            }
        }
        throw new IOException("No X509 trust manager available for TLS CA file");
    }

    private static final class ReloadingKeyManager extends X509ExtendedKeyManager {
        private final AtomicReference<X509ExtendedKeyManager> delegate = new AtomicReference<>();

        private void setDelegate(X509ExtendedKeyManager keyManager) {
            delegate.set(Objects.requireNonNull(keyManager, "keyManager must not be null"));
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return current().getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return current().chooseClientAlias(keyType, issuers, socket);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return current().getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return current().chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return current().getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return current().getPrivateKey(alias);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return current().chooseEngineClientAlias(keyType, issuers, engine);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return current().chooseEngineServerAlias(keyType, issuers, engine);
        }

        private X509ExtendedKeyManager current() {
            return Objects.requireNonNull(delegate.get(), "TLS key manager has not been loaded");
        }
    }

    private static final class ReloadingTrustManager implements X509TrustManager {
        private final AtomicReference<X509TrustManager> delegate = new AtomicReference<>();

        private void setDelegate(X509TrustManager trustManager) {
            delegate.set(Objects.requireNonNull(trustManager, "trustManager must not be null"));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            current().checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            current().checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return current().getAcceptedIssuers();
        }

        private X509TrustManager current() {
            return Objects.requireNonNull(delegate.get(), "TLS trust manager has not been loaded");
        }
    }
}
