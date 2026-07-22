package com.huawei.dcs.modelengine.operator.framework.event;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceMapper;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectReference;

import java.util.List;
import java.util.Objects;

/**
 * Built-in mappers from Kubernetes {@link Event} resources to primary reconcile requests.
 *
 * <p>Kubernetes Events are best-effort: they may be dropped or TTL-expired by the API server, so
 * they must not be used for correctness-critical or durable workflow state.
 *
 * <p>Beware the self-triggering reconcile-loop hazard: a controller that both records Events for
 * its primary resource and maps Events involving that same resource back to reconcile requests can
 * loop infinitely. If Events are consumed this way, filter them by source, reason, or type.
 */
public final class EventMapper {
    private EventMapper() {
    }

    /**
     * Maps an {@link Event} to a reconcile request for the resource named by its involved object,
     * but only when the involved object refers to the primary resource type.
     *
     * @param primaryResourceClass the primary resource class reconciled by the controller
     * @param <P> the primary resource type
     * @return a mapper that yields one request for matching Events and an empty list otherwise
     */
    public static <P extends HasMetadata> ResourceMapper<Event, P> involvedObject(Class<P> primaryResourceClass) {
        Objects.requireNonNull(primaryResourceClass, "primaryResourceClass must not be null");
        String primaryKind = HasMetadata.getKind(primaryResourceClass);
        String primaryApiVersion = HasMetadata.getApiVersion(primaryResourceClass);
        return (resource, event) -> {
            Objects.requireNonNull(resource, "resource must not be null");
            ObjectReference involvedObject = resource.getInvolvedObject();
            if (involvedObject == null) {
                return List.of();
            }
            if (!primaryKind.equals(involvedObject.getKind())
                || !primaryApiVersion.equals(involvedObject.getApiVersion())) {
                return List.of();
            }
            return List.of(new Request(involvedObject.getNamespace(), involvedObject.getName()));
        };
    }
}
