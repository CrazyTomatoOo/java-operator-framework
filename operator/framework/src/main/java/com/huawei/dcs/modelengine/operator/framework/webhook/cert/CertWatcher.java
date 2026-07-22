package com.huawei.dcs.modelengine.operator.framework.webhook.cert;

import com.huawei.dcs.modelengine.operator.framework.webhook.ReloadableSslContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Watches TLS certificate files and reloads an SSL context when they change. */
public final class CertWatcher implements AutoCloseable {
    public static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofSeconds(5);
    private static final Logger LOGGER = Logger.getLogger(CertWatcher.class.getName());

    private final ReloadableSslContext sslContext;
    private final List<Path> watchedFiles;
    private final Duration pollingInterval;
    private final boolean watchServiceEnabled;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile WatchService watchService;
    private volatile Thread watcherThread;
    private volatile Map<Path, FileFingerprint> lastFingerprint = Map.of();

    public CertWatcher(Path certChainPath, Path privateKeyPath, Path caPath, ReloadableSslContext sslContext) {
        this(certChainPath, privateKeyPath, caPath, sslContext, DEFAULT_POLLING_INTERVAL, true);
    }

    public CertWatcher(Path certChainPath, Path privateKeyPath, ReloadableSslContext sslContext) {
        this(certChainPath, privateKeyPath, null, sslContext);
    }

    public CertWatcher(Path certChainPath, Path privateKeyPath, Path caPath, ReloadableSslContext sslContext,
            Duration pollingInterval) {
        this(certChainPath, privateKeyPath, caPath, sslContext, pollingInterval, true);
    }

    CertWatcher(Path certChainPath, Path privateKeyPath, Path caPath, ReloadableSslContext sslContext,
            Duration pollingInterval, boolean watchServiceEnabled) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext must not be null");
        this.watchedFiles = watchedFiles(certChainPath, privateKeyPath, caPath);
        this.pollingInterval = requirePositive(pollingInterval);
        this.watchServiceEnabled = watchServiceEnabled;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        lastFingerprint = fingerprint();
        watcherThread = new Thread(this::watchLoop, "operator-cert-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeWatchService();
        Thread thread = watcherThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void watchLoop() {
        WatchService service = openWatchService();
        long nextPollNanos = System.nanoTime() + pollingInterval.toNanos();
        while (running.get()) {
            if (service != null) {
                drainWatchService(service);
            }
            long now = System.nanoTime();
            if (now >= nextPollNanos) {
                reloadWhenFingerprintChanged();
                nextPollNanos = now + pollingInterval.toNanos();
            }
            sleepBriefly();
        }
    }

    private WatchService openWatchService() {
        if (!watchServiceEnabled) {
            return null;
        }
        try {
            WatchService service = watchedFiles.get(0).getFileSystem().newWatchService();
            for (Path parent : parentDirectories()) {
                parent.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
            }
            watchService = service;
            return service;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Falling back to polling because cert WatchService could not start", e);
            return null;
        }
    }

    private void drainWatchService(WatchService service) {
        WatchKey key;
        while ((key = service.poll()) != null) {
            boolean changed = false;
            Path parent = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    changed = true;
                    continue;
                }
                Object context = event.context();
                if (context instanceof Path changedPath && isWatchedFile(parent.resolve(changedPath))) {
                    changed = true;
                }
            }
            if (changed) {
                reloadWhenFingerprintChanged();
            }
            if (!key.reset()) {
                closeWatchService();
                watchService = null;
                break;
            }
        }
    }

    private void reloadWhenFingerprintChanged() {
        Map<Path, FileFingerprint> current = fingerprint();
        if (current.equals(lastFingerprint)) {
            return;
        }
        lastFingerprint = current;
        try {
            sslContext.reload();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Ignoring invalid TLS certificate files and keeping previous SSL context", e);
        }
    }

    private Map<Path, FileFingerprint> fingerprint() {
        Map<Path, FileFingerprint> fingerprints = new LinkedHashMap<>();
        for (Path path : watchedFiles) {
            fingerprints.put(path, fingerprint(path));
        }
        return fingerprints;
    }

    private FileFingerprint fingerprint(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return FileFingerprint.missing();
            }
            return new FileFingerprint(true, Files.getLastModifiedTime(path).toMillis(), Files.size(path), sha256(path));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read TLS certificate file metadata: " + path, e);
            return FileFingerprint.missing();
        }
    }

    private List<Path> parentDirectories() {
        List<Path> parents = new ArrayList<>();
        for (Path path : watchedFiles) {
            Path parent = path.getParent();
            if (parent != null && !parents.contains(parent)) {
                parents.add(parent);
            }
        }
        return parents;
    }

    private boolean isWatchedFile(Path changedPath) {
        Path absolutePath = changedPath.toAbsolutePath().normalize();
        for (Path path : watchedFiles) {
            if (path.equals(absolutePath)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> watchedFiles(Path certChainPath, Path privateKeyPath, Path caPath) {
        List<Path> paths = new ArrayList<>();
        paths.add(Objects.requireNonNull(certChainPath, "certChainPath must not be null").toAbsolutePath().normalize());
        paths.add(Objects.requireNonNull(privateKeyPath, "privateKeyPath must not be null").toAbsolutePath().normalize());
        if (caPath != null) {
            paths.add(caPath.toAbsolutePath().normalize());
        }
        return List.copyOf(paths);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    private void closeWatchService() {
        WatchService service = watchService;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not close cert WatchService", e);
            }
        }
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "pollingInterval must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("pollingInterval must be positive");
        }
        return duration;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record FileFingerprint(boolean exists, long lastModifiedMillis, long size, String sha256) {
        private static FileFingerprint missing() {
            return new FileFingerprint(false, 0L, 0L, "");
        }
    }
}
