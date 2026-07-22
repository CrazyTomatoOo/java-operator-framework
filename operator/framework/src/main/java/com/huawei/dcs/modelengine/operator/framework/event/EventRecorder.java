package com.huawei.dcs.modelengine.operator.framework.event;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.MicroTime;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Publishes and pre-aggregates Kubernetes {@code core/v1} Events for involved objects.
 * The operator service account requires {@code create}, {@code get}, and {@code patch}
 * permissions on {@code events} in every namespace where this recorder publishes.
 */
public class EventRecorder implements AutoCloseable {
    private static final Duration DEFAULT_SUPPRESSION_INTERVAL = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_CACHE_ENTRIES = 1000;
    private static final String NORMAL = "Normal";
    private static final String WARNING = "Warning";

    private final KubernetesClient client;
    private final String componentName;
    private final String defaultNamespace;
    private final Duration suppressionInterval;
    private final Supplier<Instant> clock;
    private final ScheduledExecutorService flushExecutor;
    private final boolean ownsFlushExecutor;
    private final int maxCacheEntries;
    private final Map<String, CacheEntry> cache = new LinkedHashMap<>();
    private final ScheduledFuture<?> flushTask;
    private boolean closed;

    /**
     * Creates a recorder using the client's namespace and a five-minute suppression interval.
     *
     * @param client Kubernetes client
     * @param componentName event source component
     */
    public EventRecorder(KubernetesClient client, String componentName) {
        this(client, componentName, clientNamespace(client), DEFAULT_SUPPRESSION_INTERVAL);
    }

    /**
     * Creates a recorder using a five-minute suppression interval.
     *
     * @param client Kubernetes client
     * @param componentName event source component
     * @param defaultNamespace namespace used for objects without a namespace
     */
    public EventRecorder(KubernetesClient client, String componentName, String defaultNamespace) {
        this(client, componentName, defaultNamespace, DEFAULT_SUPPRESSION_INTERVAL);
    }

    /**
     * Creates a recorder with an explicit namespace and suppression interval.
     *
     * @param client Kubernetes client
     * @param componentName event source component
     * @param defaultNamespace namespace used for objects without a namespace
     * @param suppressionInterval duplicate suppression interval
     */
    public EventRecorder(KubernetesClient client, String componentName, String defaultNamespace,
            Duration suppressionInterval) {
        this(client, componentName, defaultNamespace, suppressionInterval, Instant::now,
                newDaemonExecutor(), DEFAULT_MAX_CACHE_ENTRIES, true);
    }

    EventRecorder(KubernetesClient client, String componentName, String defaultNamespace,
            Duration suppressionInterval, Supplier<Instant> clock, ScheduledExecutorService flushExecutor,
            int maxCacheEntries) {
        this(client, componentName, defaultNamespace, suppressionInterval, clock, flushExecutor,
                maxCacheEntries, false);
    }

    private EventRecorder(KubernetesClient client, String componentName, String defaultNamespace,
            Duration suppressionInterval, Supplier<Instant> clock, ScheduledExecutorService flushExecutor,
            int maxCacheEntries, boolean ownsFlushExecutor) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.componentName = Objects.requireNonNull(componentName, "componentName must not be null");
        this.defaultNamespace = namespaceOrDefaultValue(defaultNamespace);
        this.suppressionInterval = Objects.requireNonNull(suppressionInterval,
                "suppressionInterval must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.flushExecutor = Objects.requireNonNull(flushExecutor, "flushExecutor must not be null");
        if (suppressionInterval.isZero() || suppressionInterval.isNegative()) {
            throw new IllegalArgumentException("suppressionInterval must be positive");
        }
        if (maxCacheEntries <= 0) {
            throw new IllegalArgumentException("maxCacheEntries must be positive");
        }
        this.maxCacheEntries = maxCacheEntries;
        this.ownsFlushExecutor = ownsFlushExecutor;
        long periodMillis = Math.max(1, suppressionInterval.toMillis());
        this.flushTask = flushExecutor.scheduleAtFixedRate(this::flushExpired,
                periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Records a Normal event.
     *
     * @param involvedObject object the event concerns
     * @param reason short machine-readable reason
     * @param message human-readable message; {@code null} becomes an empty string
     */
    public void normal(HasMetadata involvedObject, String reason, String message) {
        event(involvedObject, NORMAL, reason, message);
    }

    /**
     * Records a Warning event.
     *
     * @param involvedObject object the event concerns
     * @param reason short machine-readable reason
     * @param message human-readable message; {@code null} becomes an empty string
     */
    public void warning(HasMetadata involvedObject, String reason, String message) {
        event(involvedObject, WARNING, reason, message);
    }

    /**
     * Records an event of the supplied type.
     *
     * @param involvedObject object the event concerns
     * @param type Kubernetes event type
     * @param reason short machine-readable reason
     * @param message human-readable message; {@code null} becomes an empty string
     */
    public synchronized void event(HasMetadata involvedObject, String type, String reason, String message) {
        ensureOpen();
        Objects.requireNonNull(involvedObject, "involvedObject must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        ObjectMeta metadata = involvedObject.getMetadata();
        String uid = metadata == null ? null : metadata.getUid();
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("involvedObject metadata uid must not be blank");
        }
        String safeMessage = message == null ? "" : message;
        Instant now = clock.get();
        evictExpired(now);
        String key = componentName + "|" + uid + "|" + type + "|" + reason + "|" + safeMessage;
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            ensureCapacity(now);
            entry = new CacheEntry(newEvent(involvedObject, type, reason, safeMessage, key, now), now);
            Event created = createOrAggregate(entry, 1, now);
            updateFromServer(entry, created, 1);
            cache.put(key, entry);
            return;
        }
        if (elapsed(entry.lastEmitTime, now).compareTo(suppressionInterval) < 0) {
            entry.pendingCount++;
            return;
        }
        int additions = entry.pendingCount + 1;
        Event written = patchOrRecreate(entry, additions, now);
        updateFromServer(entry, written, entry.serverCount + additions);
        entry.pendingCount = 0;
        entry.lastEmitTime = now;
    }

    /**
     * Flushes pending counts and stops recorder-owned scheduling resources.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            Instant now = clock.get();
            for (CacheEntry entry : cache.values()) {
                try {
                    flushPending(entry, now);
                } catch (RuntimeException exception) {
                    if (failure == null) {
                        failure = exception;
                    }
                }
            }
        } finally {
            flushTask.cancel(false);
            if (ownsFlushExecutor) {
                flushExecutor.shutdown();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    synchronized int cacheSize() {
        return cache.size();
    }

    synchronized int pendingCount() {
        return cache.values().stream().mapToInt(entry -> entry.pendingCount).sum();
    }

    Event createEvent(String namespace, Event event) {
        return client.v1().events().inNamespace(namespace).resource(event).create();
    }

    Event getEvent(String namespace, String name) {
        return client.v1().events().inNamespace(namespace).withName(name).get();
    }

    Event patchEvent(String namespace, String name, Event patch) {
        // Fabric8 CRUD mocks reject strategic merge (415); JSON merge equivalently replaces scalar count/lastTimestamp and checks metadata.resourceVersion.
        return client.v1().events().inNamespace(namespace).withName(name)
                .patch(PatchContext.of(PatchType.JSON_MERGE), patch);
    }

    private synchronized void flushExpired() {
        if (closed) {
            return;
        }
        Instant now = clock.get();
        for (CacheEntry entry : cache.values()) {
            if (entry.pendingCount > 0
                    && elapsed(entry.lastEmitTime, now).compareTo(suppressionInterval) >= 0) {
                flushPending(entry, now);
            }
        }
    }

    private void flushPending(CacheEntry entry, Instant now) {
        if (entry.pendingCount == 0) {
            return;
        }
        int additions = entry.pendingCount;
        Event written = patchOrRecreate(entry, additions, now);
        updateFromServer(entry, written, entry.serverCount + additions);
        entry.pendingCount = 0;
        entry.lastEmitTime = now;
    }

    private Event patchOrRecreate(CacheEntry entry, int additions, Instant now) {
        Event patch = patch(entry, entry.resourceVersion, entry.serverCount + additions, now);
        try {
            return patchEvent(entry.namespace(), entry.name(), patch);
        } catch (KubernetesClientException exception) {
            if (exception.getCode() == 404) {
                return createOrAggregate(entry, additions, now);
            }
            if (exception.getCode() != 409) {
                throw exception;
            }
            Event latest = getEvent(entry.namespace(), entry.name());
            if (latest == null) {
                return createOrAggregate(entry, additions, now);
            }
            int latestCount = count(latest);
            Event retry = patch(entry, latest.getMetadata().getResourceVersion(), latestCount + additions, now);
            return patchEvent(entry.namespace(), entry.name(), retry);
        }
    }

    private Event createOrAggregate(CacheEntry entry, int count, Instant now) {
        Event event = new EventBuilder(entry.template)
                .withCount(count)
                .withEventTime(new MicroTime(now.toString()))
                .withFirstTimestamp(now.toString())
                .withLastTimestamp(now.toString())
                .build();
        try {
            return createEvent(entry.namespace(), event);
        } catch (KubernetesClientException exception) {
            if (exception.getCode() != 409) {
                throw exception;
            }
            Event latest = getEvent(entry.namespace(), entry.name());
            if (latest == null) {
                throw exception;
            }
            Event patch = patch(entry, latest.getMetadata().getResourceVersion(), count(latest) + count, now);
            try {
                return patchEvent(entry.namespace(), entry.name(), patch);
            } catch (KubernetesClientException patchException) {
                if (patchException.getCode() != 409) {
                    throw patchException;
                }
                Event fresh = getEvent(entry.namespace(), entry.name());
                if (fresh == null) {
                    throw patchException;
                }
                return patchEvent(entry.namespace(), entry.name(), patch(entry,
                        fresh.getMetadata().getResourceVersion(), count(fresh) + count, now));
            }
        }
    }

    private Event patch(CacheEntry entry, String resourceVersion, int count, Instant now) {
        return new EventBuilder(entry.template)
                .editMetadata().withResourceVersion(resourceVersion).endMetadata()
                .withCount(count)
                .withLastTimestamp(now.toString())
                .build();
    }

    private Event newEvent(HasMetadata involvedObject, String type, String reason, String message,
            String key, Instant now) {
        ObjectMeta metadata = involvedObject.getMetadata();
        String namespace = namespaceOrDefault(metadata.getNamespace());
        String timestamp = now.toString();
        return new EventBuilder()
                .withApiVersion("v1")
                .withKind("Event")
                .withNewMetadata()
                    .withName(eventName(metadata.getName(), key))
                    .withNamespace(namespace)
                .endMetadata()
                .withInvolvedObject(new ObjectReferenceBuilder()
                        .withApiVersion(involvedObject.getApiVersion())
                        .withKind(involvedObject.getKind())
                        .withName(metadata.getName())
                        .withNamespace(metadata.getNamespace())
                        .withUid(metadata.getUid())
                        .build())
                .withNewSource().withComponent(componentName).endSource()
                .withType(type)
                .withReason(reason)
                .withMessage(message)
                .withCount(1)
                .withEventTime(new MicroTime(timestamp))
                .withFirstTimestamp(timestamp)
                .withLastTimestamp(timestamp)
                .build();
    }

    private void evictExpired(Instant now) {
        Duration ttl = suppressionInterval.multipliedBy(2);
        cache.entrySet().removeIf(item -> {
            CacheEntry entry = item.getValue();
            if (elapsed(entry.lastEmitTime, now).compareTo(ttl) <= 0) {
                return false;
            }
            flushPending(entry, now);
            return true;
        });
    }

    private void ensureCapacity(Instant now) {
        if (cache.size() < maxCacheEntries) {
            return;
        }
        Map.Entry<String, CacheEntry> oldest = cache.entrySet().stream()
                .min(Comparator.comparing(item -> item.getValue().lastEmitTime))
                .orElseThrow();
        flushPending(oldest.getValue(), now);
        cache.remove(oldest.getKey());
    }

    private void updateFromServer(CacheEntry entry, Event serverEvent, int fallbackCount) {
        entry.resourceVersion = serverEvent.getMetadata().getResourceVersion();
        entry.serverCount = serverEvent.getCount() == null ? fallbackCount : serverEvent.getCount();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("EventRecorder is closed");
        }
    }

    private String namespaceOrDefault(String namespace) {
        return namespace == null || namespace.isBlank() ? defaultNamespace : namespace;
    }

    private static String clientNamespace(KubernetesClient client) {
        Objects.requireNonNull(client, "client must not be null");
        return namespaceOrDefaultValue(client.getNamespace());
    }

    private static String namespaceOrDefaultValue(String namespace) {
        return namespace == null || namespace.isBlank() ? "default" : namespace;
    }

    private static ScheduledExecutorService newDaemonExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-recorder-flush");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Duration elapsed(Instant from, Instant to) {
        Duration duration = Duration.between(from, to);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static int count(Event event) {
        return event.getCount() == null ? 0 : event.getCount();
    }

    private static String eventName(String involvedObjectName, String key) {
        String base = involvedObjectName == null ? "event" : involvedObjectName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) {
            base = "event";
        }
        String suffix = sha256(key).substring(0, 8);
        base = base.substring(0, Math.min(base.length(), 54)).replaceAll("-+$", "");
        if (base.isEmpty()) {
            base = "event";
        }
        return base + "-" + suffix;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static final class CacheEntry {
        private final Event template;
        private Instant lastEmitTime;
        private int pendingCount;
        private String resourceVersion;
        private int serverCount;

        private CacheEntry(Event template, Instant lastEmitTime) {
            this.template = template;
            this.lastEmitTime = lastEmitTime;
        }

        private String namespace() {
            return template.getMetadata().getNamespace();
        }

        private String name() {
            return template.getMetadata().getName();
        }
    }
}
