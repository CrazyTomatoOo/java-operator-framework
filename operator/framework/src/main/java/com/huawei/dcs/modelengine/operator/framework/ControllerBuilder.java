package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.event.EventSubscriber;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.source.Mappers;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventSource;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builder for public controller registrations.
 *
 * @param <P> primary resource type handled by the controller
 */
public final class ControllerBuilder<P extends HasMetadata> implements ControllerSources<P> {
    private final Class<P> resourceClass;
    private final List<SecondaryWatch<P, ?>> secondaryWatches = new ArrayList<>();
    private boolean generationChangeFilter = false;
    private Duration resyncPeriod = Duration.ofMillis(ResourceEventSource.DEFAULT_RESYNC_PERIOD_MS);
    private Reconciler<P> reconciler;

    private ControllerBuilder(Class<P> resourceClass) {
        this.resourceClass = Objects.requireNonNull(resourceClass, "resourceClass must not be null");
    }

    public static <P extends HasMetadata> ControllerBuilder<P> forResource(Class<P> resourceClass) {
        return new ControllerBuilder<>(resourceClass);
    }

    public ControllerBuilder<P> withReconciler(Reconciler<P> reconciler) {
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler must not be null");
        return this;
    }

    public ControllerBuilder<P> withGenerationChangeFilter() {
        return withGenerationChangeFilter(true);
    }

    public ControllerBuilder<P> withGenerationChangeFilter(boolean enabled) {
        this.generationChangeFilter = enabled;
        return this;
    }

    public ControllerBuilder<P> withResyncPeriod(Duration resyncPeriod) {
        this.resyncPeriod = Objects.requireNonNull(resyncPeriod, "resyncPeriod must not be null");
        if (resyncPeriod.isNegative()) {
            throw new IllegalArgumentException("resyncPeriod must not be negative");
        }
        return this;
    }

    @Override
    public <S extends HasMetadata> ControllerBuilder<P> owns(Class<S> resourceClass) {
        Objects.requireNonNull(resourceClass, "resourceClass must not be null");
        secondaryWatches.add(new SecondaryWatch<>(resourceClass.getSimpleName(), resourceClass, Mappers.ownerReferences(), true));
        return this;
    }

    @Override
    public ControllerBuilder<P> withEventSubscriber(EventSubscriber<P> eventSubscriber) {
        Objects.requireNonNull(eventSubscriber, "eventSubscriber must not be null");
        secondaryWatches.add(eventSubscriber.toSecondaryWatch());
        return this;
    }

    @Override
    public <S extends HasMetadata> ControllerBuilder<P> watches(
        String name,
        Class<S> resourceClass,
        ResourceMapper<S, P> mapper) {
        secondaryWatches.add(new SecondaryWatch<>(name, resourceClass, mapper, false));
        return this;
    }

    public ControllerRegistration<P> build() {
        if (reconciler == null) {
            throw new IllegalStateException("reconciler must be configured before build");
        }
        return new ControllerRegistration<>(resourceClass, reconciler, secondaryWatches, generationChangeFilter, resyncPeriod);
    }
}
