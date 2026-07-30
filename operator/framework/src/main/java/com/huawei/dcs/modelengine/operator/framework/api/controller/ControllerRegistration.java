package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import io.fabric8.kubernetes.api.model.HasMetadata;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Immutable controller definition consumed by the operator runtime. */
public final class ControllerRegistration<T extends HasMetadata> {
    private final Class<T> resourceType;
    private final Reconciler<T> reconciler;
    private final Optional<Boolean> generationFilter;
    private final Optional<Duration> resyncPeriod;
    private final List<Class<? extends HasMetadata>> ownedResources;
    private final List<SecondaryWatch<? extends HasMetadata, T>> secondaryWatches;
    private final boolean kubernetesEvents;
    private final Optional<ControllerBuilder.WatchSelector> watchSelector;
    private final Map<String, Function<T, String>> indexFields;

    ControllerRegistration(ControllerBuilder<T> builder) {
        resourceType = builder.resourceType();
        reconciler = builder.reconciler();
        generationFilter = builder.generationFilter();
        resyncPeriod = builder.resyncPeriod();
        ownedResources = List.copyOf(builder.ownedResources());
        secondaryWatches = List.copyOf(builder.secondaryWatches());
        kubernetesEvents = builder.kubernetesEvents();
        watchSelector = Optional.ofNullable(builder.watchSelector());
        indexFields = Map.copyOf(builder.indexFields());
    }

    public Class<T> resourceType() {
        return resourceType;
    }

    public Reconciler<T> reconciler() {
        return reconciler;
    }

    public Optional<Boolean> generationFilter() {
        return generationFilter;
    }

    public Optional<Duration> resyncPeriod() {
        return resyncPeriod;
    }

    public List<Class<? extends HasMetadata>> ownedResources() {
        return ownedResources;
    }

    public List<SecondaryWatch<? extends HasMetadata, T>> secondaryWatches() {
        return secondaryWatches;
    }

    public boolean watchesKubernetesEvents() {
        return kubernetesEvents;
    }

    public Optional<ControllerBuilder.WatchSelector> watchSelector() {
        return watchSelector;
    }

    public Map<String, Function<T, String>> indexFields() {
        return indexFields;
    }

    /** Public, read-only descriptor for a secondary resource watch. */
    public record SecondaryWatch<S extends HasMetadata, T extends HasMetadata>(
            String name,
            Class<S> resourceType,
            ResourceMapper<S, T> mapper) {
        public SecondaryWatch {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            Objects.requireNonNull(resourceType, "resourceType must not be null");
            Objects.requireNonNull(mapper, "mapper must not be null");
        }
    }
}
