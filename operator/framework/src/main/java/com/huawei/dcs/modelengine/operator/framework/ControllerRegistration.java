package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventSource;
import io.fabric8.kubernetes.api.model.HasMetadata;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Public description of one controller's primary resource, reconciler, and secondary watches.
 *
 * @param <P> primary resource type handled by the controller
 * @param resourceClass primary resource class
 * @param reconciler reconciliation logic for the primary resource
 * @param secondaryWatches secondary resource watches that can enqueue primary reconciliations
 * @param generationChangeFilter when true, primary update events are enqueued only on generation, deletion, or finalizer changes
 * @param resyncPeriod informer resync period; {@link Duration#ZERO} disables periodic resync
 */
public record ControllerRegistration<P extends HasMetadata>(
    Class<P> resourceClass,
    Reconciler<P> reconciler,
    List<SecondaryWatch<P, ?>> secondaryWatches,
    boolean generationChangeFilter,
    Duration resyncPeriod) {

    public ControllerRegistration {
        Objects.requireNonNull(resourceClass, "resourceClass must not be null");
        Objects.requireNonNull(reconciler, "reconciler must not be null");
        secondaryWatches = List.copyOf(Objects.requireNonNull(secondaryWatches, "secondaryWatches must not be null"));
        Objects.requireNonNull(resyncPeriod, "resyncPeriod must not be null");
        if (resyncPeriod.isNegative()) {
            throw new IllegalArgumentException("resyncPeriod must not be negative");
        }
    }

    public ControllerRegistration(Class<P> resourceClass, Reconciler<P> reconciler, List<SecondaryWatch<P, ?>> secondaryWatches) {
        this(resourceClass, reconciler, secondaryWatches, false, Duration.ofMillis(ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS));
    }
}
