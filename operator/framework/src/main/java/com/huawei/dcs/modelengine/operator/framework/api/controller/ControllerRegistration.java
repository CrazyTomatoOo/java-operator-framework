/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Immutable controller definition consumed by the operator runtime.
 *
 * @param <T> primary resource type
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
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

    ControllerRegistration(ControllerBuilder.Snapshot<T> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        resourceType = snapshot.resourceType();
        reconciler = snapshot.reconciler();
        generationFilter = snapshot.generationFilter();
        resyncPeriod = snapshot.resyncPeriod();
        ownedResources = List.copyOf(snapshot.ownedResources());
        secondaryWatches = List.copyOf(snapshot.secondaryWatches());
        kubernetesEvents = snapshot.kubernetesEvents();
        watchSelector = snapshot.watchSelector();
        indexFields = Map.copyOf(snapshot.indexFields());
    }

    /**
     * Returns the primary resource type reconciled by this controller.
     *
     * @return the primary resource class
     */
    public Class<T> resourceType() {
        return resourceType;
    }

    /**
     * Returns the reconciler invoked for the primary resource.
     *
     * @return the reconciler
     */
    public Reconciler<T> reconciler() {
        return reconciler;
    }

    /**
     * Returns whether reconciliation triggers only on generation changes, when configured.
     *
     * @return the generation filter setting, or empty when not configured
     */
    public Optional<Boolean> generationFilter() {
        return generationFilter;
    }

    /**
     * Returns the periodic resync interval, when configured.
     *
     * @return the resync period, or empty when not configured
     */
    public Optional<Duration> resyncPeriod() {
        return resyncPeriod;
    }

    /**
     * Returns the owned resource types whose changes reconcile the primary resource.
     *
     * @return the owned resource classes
     */
    public List<Class<? extends HasMetadata>> ownedResources() {
        return ownedResources;
    }

    /**
     * Returns the registered secondary resource watches.
     *
     * @return the secondary watches
     */
    public List<SecondaryWatch<? extends HasMetadata, T>> secondaryWatches() {
        return secondaryWatches;
    }

    /**
     * Checks whether the controller also watches Kubernetes {@code Event} objects.
     *
     * @return {@code true} when Kubernetes events are watched
     */
    public boolean watchesKubernetesEvents() {
        return kubernetesEvents;
    }

    /**
     * Returns the label and field selector restricting the primary watch, when configured.
     *
     * @return the watch selector, or empty when not configured
     */
    public Optional<ControllerBuilder.WatchSelector> watchSelector() {
        return watchSelector;
    }

    /**
     * Returns the registered field indexes keyed by index name.
     *
     * @return the index field extractors
     */
    public Map<String, Function<T, String>> indexFields() {
        return indexFields;
    }

    /**
     * Public, read-only descriptor for a secondary resource watch.
     *
     * @param <S> secondary resource type
     * @param <T> primary resource type
     * @param name unique watch name
     * @param resourceType secondary resource class
     * @param mapper maps secondary events to primary resource keys
     */
    public record SecondaryWatch<S extends HasMetadata, T extends HasMetadata>(String name, Class<S> resourceType,
        ResourceMapper<S, T> mapper) {
        /**
         * Validates the watch descriptor.
         *
         * @param name unique watch name
         * @param resourceType secondary resource class
         * @param mapper maps secondary events to primary resource keys
         * @throws IllegalArgumentException if the name is null or blank
         * @throws NullPointerException if the resource type or mapper is null
         */
        public SecondaryWatch {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            Objects.requireNonNull(resourceType, "resourceType must not be null");
            Objects.requireNonNull(mapper, "mapper must not be null");
        }
    }
}
