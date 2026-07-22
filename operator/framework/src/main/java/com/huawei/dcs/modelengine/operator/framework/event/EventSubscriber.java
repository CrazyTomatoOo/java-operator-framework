package com.huawei.dcs.modelengine.operator.framework.event;

import com.huawei.dcs.modelengine.operator.framework.SecondaryWatch;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Objects;

/**
 * Subscribes a controller to Kubernetes {@link Event} resources involving its primary resource,
 * so that changes to those Events enqueue a reconcile request for the involved primary resource.
 *
 * <p>Kubernetes Events are best-effort: they may be dropped or TTL-expired by the API server, so
 * they must not be used for correctness-critical or durable workflow state.
 *
 * <p><b>Self-triggering reconcile-loop hazard:</b> a controller that both records Events for its
 * primary resource and subscribes to Events involving that same resource can cause infinite
 * reconciliation loops, because each emitted Event re-triggers reconciliation which emits another
 * Event. If this subscriber is enabled in such a setup, filter the Events by source, reason, or
 * type so that the controller's own Events do not re-trigger it.
 *
 * <p>The namespace scope is inherited from the {@code SharedInformerFactory} already created by
 * the {@code Operator}: the subscriber watches the same namespace as the primary controller, or
 * all namespaces for cluster-scoped operators.
 *
 * @param <P> primary resource type reconciled by the controller
 */
public final class EventSubscriber<P extends HasMetadata> {
    private static final String NAME = "events";

    private final SecondaryWatch<P, Event> secondaryWatch;

    private EventSubscriber(SecondaryWatch<P, Event> secondaryWatch) {
        this.secondaryWatch = secondaryWatch;
    }

    /**
     * Creates a subscriber that watches {@link Event} resources and maps each Event to the primary
     * resource named by its involved object.
     *
     * @param primaryResourceClass the primary resource class reconciled by the controller
     * @param <P> the primary resource type
     * @return a subscriber wrapping the secondary watch for Events
     */
    public static <P extends HasMetadata> EventSubscriber<P> forInvolvedObject(Class<P> primaryResourceClass) {
        Objects.requireNonNull(primaryResourceClass, "primaryResourceClass must not be null");
        SecondaryWatch<P, Event> secondaryWatch = new SecondaryWatch<>(
            NAME, Event.class, EventMapper.involvedObject(primaryResourceClass), false);
        return new EventSubscriber<>(secondaryWatch);
    }

    /**
     * Exposes the underlying secondary watch so the controller builder can consume it.
     *
     * @return the secondary watch for {@link Event} resources
     */
    public SecondaryWatch<P, Event> toSecondaryWatch() {
        return this.secondaryWatch;
    }
}
