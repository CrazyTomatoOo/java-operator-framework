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

/** Publishes and aggregates core/v1 Kubernetes Events within deterministic time windows. */
@Slf4j
public final class AggregatingKubernetesEventPublisher implements KubernetesEventPublisher, AutoCloseable {

    private final KubernetesClient client;
    private final OperatorFrameworkProperties.Events properties;
    private final Clock clock;
    private final String component;
    private final Map<String, CachedEvent> cache;
    private final OperatorFrameworkMetrics metrics;

    public AggregatingKubernetesEventPublisher(
            KubernetesClient client,
            OperatorFrameworkProperties properties,
            Environment environment,
            Clock clock,
            OperatorFrameworkMetrics metrics) {
        this.client = client;
        this.properties = properties.getEvents();
        this.clock = clock;
        component = component(environment);
        cache = boundedCache(this.properties.getMaxCacheEntries());
        this.metrics = metrics;
    }

    AggregatingKubernetesEventPublisher(
            KubernetesClient client,
            OperatorFrameworkProperties properties,
            Environment environment,
            Clock clock) {
        this(client, properties, environment, clock, new OperatorFrameworkMetrics(null));
    }

    @Override
    public void normal(HasMetadata involvedObject, String reason, String message) {
        publish("Normal", involvedObject, reason, message);
    }

    @Override
    public void warning(HasMetadata involvedObject, String reason, String message) {
        publish("Warning", involvedObject, reason, message);
    }

    @Override
    public synchronized void close() {
        cache.clear();
    }

    private void publish(String type, HasMetadata involvedObject, String reason, String message) {
        validate(involvedObject, reason, message);
        var now = clock.instant();
        var request = new EventRequest(type, involvedObject, reason, message);
        var name = eventName(involvedObject, type, reason, message, now);
        // ponytail: hold the cache lock only for get/put, not for the network create/update;
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
            var persisted = existing == null
                    ? create(newEvent(name, request, now))
                    : update(existing, now);
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
            return exception.getCode() == 409 ? mergeExisting(event) : failed(exception);
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
            return exception.getCode() == 409 ? retryUpdate(updated, now) : failed(exception);
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
        var reference = new ObjectReferenceBuilder()
                .withApiVersion(involvedObject.getApiVersion())
                .withKind(involvedObject.getKind())
                .withNamespace(metadata.getNamespace())
                .withName(metadata.getName())
                .withUid(metadata.getUid())
                .build();
        return new EventBuilder()
                .withApiVersion("v1")
                .withKind("Event")
                .withNewMetadata().withNamespace(namespace(involvedObject)).withName(name).endMetadata()
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

    private io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<
            Event, io.fabric8.kubernetes.api.model.EventList, io.fabric8.kubernetes.client.dsl.Resource<Event>>
            operation(Event event) {
        return client.v1().events().inNamespace(event.getMetadata().getNamespace());
    }

    private String eventName(
            HasMetadata involvedObject,
            String type,
            String reason,
            String message,
            Instant now) {
        var metadata = involvedObject.getMetadata();
        var window = now.toEpochMilli() / properties.getAggregationWindow().toMillis();
        var seed = String.join("|", Objects.toString(metadata.getUid(), ""), type, reason, message,
                component, Long.toString(window));
        var base = metadata.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "-");
        base = base.substring(0, Math.min(base.length(), 40));
        return base + "." + digest(seed).substring(0, 16);
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

    private String component(Environment environment) {
        var configured = properties.getComponent();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        var application = environment.getProperty("spring.application.name", "operator-framework");
        return application == null || application.isBlank() ? "operator-framework" : application;
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

    private Map<String, CachedEvent> boundedCache(int maximum) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedEvent> eldest) {
                return size() > maximum;
            }
        };
    }

    private record CachedEvent(Event event) {
    }

    private record EventRequest(String type, HasMetadata involvedObject, String reason, String message) {
    }
}
