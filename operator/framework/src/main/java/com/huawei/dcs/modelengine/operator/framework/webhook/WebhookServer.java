package com.huawei.dcs.modelengine.operator.framework.webhook;

import com.huawei.dcs.modelengine.operator.framework.webhook.cert.CertWatcher;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * TLS webhook server backed by the JDK {@link HttpsServer}.
 */
public final class WebhookServer implements AutoCloseable {
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_PORT = 8443;

    private final HttpsServer server;
    private final ReloadableSslContext sslContext;
    private final CertWatcher certWatcher;
    private final Object lifecycleLock = new Object();
    private boolean running;

    public WebhookServer(Path certChainPath, Path privateKeyPath) throws IOException {
        this(DEFAULT_HOST, DEFAULT_PORT, certChainPath, privateKeyPath, null);
    }

    public WebhookServer(Path certChainPath, Path privateKeyPath, Path caPath) throws IOException {
        this(DEFAULT_HOST, DEFAULT_PORT, certChainPath, privateKeyPath, caPath);
    }

    public WebhookServer(String host, int port, Path certChainPath, Path privateKeyPath) throws IOException {
        this(host, port, certChainPath, privateKeyPath, null);
    }

    public WebhookServer(String host, int port, Path certChainPath, Path privateKeyPath, Path caPath) throws IOException {
        this(host, port, certChainPath, privateKeyPath, caPath, CertWatcher.DEFAULT_POLLING_INTERVAL);
    }

    public static WebhookServer withCertWatcher(String host, int port, Path certChainPath, Path privateKeyPath,
            Path caPath, Duration pollingInterval) throws IOException {
        return new WebhookServer(host, port, certChainPath, privateKeyPath, caPath, pollingInterval);
    }

    WebhookServer(ReloadableSslContext sslContext, String host, int port) throws IOException {
        this(sslContext, host, port, null);
    }

    private WebhookServer(String host, int port, Path certChainPath, Path privateKeyPath, Path caPath,
            Duration pollingInterval) throws IOException {
        ReloadableSslContext context = new ReloadableSslContext(certChainPath, privateKeyPath, caPath);
        this.sslContext = context;
        this.certWatcher = new CertWatcher(certChainPath, privateKeyPath, caPath, context, pollingInterval);
        this.server = HttpsServer.create(new InetSocketAddress(Objects.requireNonNull(host, "host must not be null"), port), 0);
        this.server.setHttpsConfigurator(new ReloadingHttpsConfigurator(this.sslContext));
    }

    private WebhookServer(ReloadableSslContext sslContext, String host, int port, CertWatcher certWatcher)
            throws IOException {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext must not be null");
        this.certWatcher = certWatcher;
        this.server = HttpsServer.create(new InetSocketAddress(Objects.requireNonNull(host, "host must not be null"), port), 0);
        this.server.setHttpsConfigurator(new ReloadingHttpsConfigurator(this.sslContext));
    }

    public void register(String path, HttpHandler handler) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        synchronized (lifecycleLock) {
            if (running) {
                throw new IllegalStateException("Cannot register webhook handlers after server start");
            }
            server.createContext(path, handler);
        }
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            if (certWatcher != null) {
                certWatcher.start();
            }
            server.start();
            running = true;
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            running = false;
            if (certWatcher != null) {
                certWatcher.stop();
            }
            server.stop(0);
        }
    }

    @Override
    public void close() {
        stop();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    public ReloadableSslContext sslContext() {
        return sslContext;
    }

    private static final class ReloadingHttpsConfigurator extends HttpsConfigurator {
        private final ReloadableSslContext reloadableSslContext;

        private ReloadingHttpsConfigurator(ReloadableSslContext reloadableSslContext) {
            super(reloadableSslContext.sslContext());
            this.reloadableSslContext = reloadableSslContext;
        }

        @Override
        public SSLContext getSSLContext() {
            return reloadableSslContext.sslContext();
        }

        @Override
        public void configure(HttpsParameters parameters) {
            SSLContext currentContext = reloadableSslContext.sslContext();
            SSLParameters defaultParameters = currentContext.getDefaultSSLParameters();
            parameters.setSSLParameters(defaultParameters);
        }
    }
}
