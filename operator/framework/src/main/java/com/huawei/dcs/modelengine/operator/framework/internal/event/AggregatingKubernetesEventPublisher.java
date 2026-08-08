/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.event;

import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventSourceBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes and aggregates core/v1 Kubernetes Events within deterministic time windows.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@Slf4j
public final class AggregatingKubernetesEventPublisher implements KubernetesEventPublisher, AutoCloseable {

    private static final int HTTP_CONFLICT = 409;

    private static final int MAX_EVENT_NAME_LENGTH = 40;

    private static final int DIGEST_SUFFIX_LENGTH = 16;

    private static final int CACHE_INITIAL_CAPACITY = 16;

    private static final float CACHE_LOAD_FACTOR = 0.75f;

    private final KubernetesClient client;

    private final OperatorFrameworkProperties.Events properties;

    private final Clock clock;

    private final String component;

    private final Map<String, CachedEvent> cache;

    private final OperatorFrameworkMetrics metrics;

    AggregatingKubernetesEventPublisher(KubernetesClient client, OperatorFrameworkProperties properties,
        Environment environment, Clock clock) {
        this(client, properties, environment, clock, new OperatorFrameworkMetrics(null));
    }

    /**
     * Creates the aggregating event publisher.
     *
     * @param client the Kubernetes client used to create and update Events
     * @param properties the operator framework configuration
     * @param environment the Spring environment used to derive the reporting component
     * @param clock the clock used for aggregation windows and timestamps
     * @param metrics the metrics sink for publication outcomes
     */
    public AggregatingKubernetesEventPublisher(KubernetesClient client, OperatorFrameworkProperties properties,
        Environment environment, Clock clock, OperatorFrameworkMetrics metrics) {
        this.client = client;
        this.properties = properties.getEvents();
        this.clock = clock;
        component = component(environment);
        cache = boundedCache(this.properties.getMaxCacheEntries());
        this.metrics = metrics;
    }

    private String component(Environment environment) {
        var configured = properties.getComponent();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        var application = environment.getProperty("spring.application.name", "operator-framework");
        return application == null || application.isBlank() ? "operator-framework" : application;
    }

    private Map<String, CachedEvent> boundedCache(int maximum) {
        return new LinkedHashMap<>(CACHE_INITIAL_CAPACITY, CACHE_LOAD_FACTOR, true) {
            /**
             * Evicts the eldest entry once the cache exceeds its maximum size.
             *
             * @param eldest the least recently accessed entry
             * @return {@code true} when the eldest entry should be removed
             */
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedEvent> eldest) {
                return size() > maximum;
            }
        };
    }

    /**
     * Publishes a Normal event for the involved object, aggregating repeats within a time window.
     *
     * @param involvedObject the resource the event is about
     * @param reason the short machine-readable reason of the event
     * @param message the human-readable description of the event
     */
    @Override
    public void normal(HasMetadata involvedObject, String reason, String message) {
        publish("Normal", involvedObject, reason, message);
    }

    /**
     * Publishes a Warning event for the involved object, aggregating repeats within a time window.
     *
     * @param involvedObject the resource the event is about
     * @param reason the short machine-readable reason of the event
     * @param message the human-readable description of the event
     */
    @Override
    public void warning(HasMetadata involvedObject, String reason, String message) {
        publish("Warning", involvedObject, reason, message);
    }

    /** Clears the aggregation cache; already persisted Kubernetes Events are left untouched. */
    @Override
    public synchronized void close() {
        cache.clear();
    }

    private void publish(String type, HasMetadata involvedObject, String reason, String message) {
        validate(involvedObject, reason, message);
        var now = clock.instant();
        var request = new EventRequest(type, involvedObject, reason, message);
        var name = eventName(involvedObject, type, reason, message, now);
        // hold the cache lock only for get/put, not for the network create/update;
        // concurrent creates of the same name reconcile via the 409 -> mergeExisting path
        Event existing;
        synchronized (this) {
            var cached = cache.get(name);
            if (cached != null) {
                metrics.event("suppressed");
            }
            existing = cached == null ? null : cached.event();
        }
        try {
            var persisted = existing == null ? create(newEvent(name, request, now)) : update(existing, now);
            synchronized (this) {
                cache.put(name, new CachedEvent(persisted));
            }
            metrics.event("published");
        } catch (RuntimeException exception) {
            metrics.event("failed");
            log.warn("Kubernetes Event publication failed");
            log.debug("Kubernetes Event publication failure detail", exception);
        }
    }

    private Event create(Event event) {
        try {
            return operation(event).resource(event).create();
        } catch (KubernetesClientException exception) {
            return exception.getCode() == HTTP_CONFLICT ? mergeExisting(event) : failed(exception);
        }
    }

    private Event mergeExisting(Event event) {
        var existing = operation(event).withName(event.getMetadata().getName()).get();
        if (existing == null) {
            throw new IllegalStateException("conflicting Kubernetes Event disappeared before aggregation");
        }
        return update(existing, clock.instant());
    }

    private Event update(Event event, Instant now) {
        var updated = increment(event, now);
        try {
            return operation(updated).resource(updated).update();
        } catch (KubernetesClientException exception) {
            return exception.getCode() == HTTP_CONFLICT ? retryUpdate(updated, now) : failed(exception);
        }
    }

    private Event retryUpdate(Event event, Instant now) {
        var latest = operation(event).withName(event.getMetadata().getName()).get();
        if (latest == null) {
            throw new IllegalStateException("Kubernetes Event disappeared during conflict retry");
        }
        try {
            var merged = increment(latest, now);
            return operation(merged).resource(merged).update();
        } catch (KubernetesClientException exception) {
            return failed(exception);
        }
    }

    private Event failed(KubernetesClientException exception) {
        throw exception;
    }

    private Event newEvent(String name, EventRequest request, Instant now) {
        var involvedObject = request.involvedObject();
        var metadata = involvedObject.getMetadata();
        var reference = new ObjectReferenceBuilder().withApiVersion(involvedObject.getApiVersion())
            .withKind(involvedObject.getKind())
            .withNamespace(metadata.getNamespace())
            .withName(metadata.getName())
            .withUid(metadata.getUid())
            .build();
        return new EventBuilder().withApiVersion("v1")
            .withKind("Event")
            .withNewMetadata()
            .withNamespace(namespace(involvedObject))
            .withName(name)
            .endMetadata()
            .withInvolvedObject(reference)
            .withReason(request.reason())
            .withMessage(request.message())
            .withType(request.type())
            .withCount(1)
            .withFirstTimestamp(now.toString())
            .withLastTimestamp(now.toString())
            .withReportingComponent(component)
            .withSource(new EventSourceBuilder().withComponent(component).build())
            .build();
    }

    private Event increment(Event event, Instant now) {
        var count = event.getCount() == null ? 1 : event.getCount();
        return new EventBuilder(event).withCount(count + 1).withLastTimestamp(now.toString()).build();
    }

    private io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<Event,
            io.fabric8.kubernetes.api.model.EventList,
            io.fabric8.kubernetes.client.dsl.Resource<Event>> operation(
        Event event) {
        return client.v1().events().inNamespace(event.getMetadata().getNamespace());
    }

    private String eventName(HasMetadata involvedObject, String type, String reason, String message, Instant now) {
        var metadata = involvedObject.getMetadata();
        var window = now.toEpochMilli() / properties.getAggregationWindow().toMillis();
        var seed = String.join("|", Objects.toString(metadata.getUid(), ""), type, reason, message, component,
            Long.toString(window));
        var base = metadata.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-");
        base = base.substring(0, Math.min(base.length(), MAX_EVENT_NAME_LENGTH));
        return base + "." + digest(seed).substring(0, DIGEST_SUFFIX_LENGTH);
    }

    private String digest(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String namespace(HasMetadata resource) {
        var namespace = resource.getMetadata().getNamespace();
        return namespace == null || namespace.isBlank() ? "default" : namespace;
    }

    private void validate(HasMetadata resource, String reason, String message) {
        Objects.requireNonNull(resource, "involvedObject must not be null");
        Objects.requireNonNull(resource.getMetadata(), "involvedObject metadata must not be null");
        requireText(resource.getMetadata().getName(), "involvedObject name");
        requireText(reason, "reason");
        requireText(message, "message");
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private record CachedEvent(Event event) {}

    private record EventRequest(String type, HasMetadata involvedObject, String reason, String message) {}
}
