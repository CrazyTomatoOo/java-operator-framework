/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.DependentResource;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds an immutable controller registration.
 *
 * <p>Builder mutations and {@link #build()} are serialized. Each registration is created from
 * immutable collection snapshots, so later builder changes do not affect it.
 *
 * @param <T> primary resource type
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class ControllerBuilder<T extends HasMetadata> {
    private final Class<T> resourceType;
    private final Reconciler<T> reconciler;
    private final Set<Class<? extends HasMetadata>> ownedResources = new LinkedHashSet<>();
    private final Map<String, ControllerRegistration.SecondaryWatch<? extends HasMetadata, T>> secondaryWatches =
            new LinkedHashMap<>();

    private Boolean generationFilter;
    private Duration resyncPeriod;
    private boolean kubernetesEvents;
    private WatchSelector watchSelector;
    private final Map<String, Function<T, String>> indexFields = new LinkedHashMap<>();

    private ControllerBuilder(Class<T> resourceType, Reconciler<T> reconciler) {
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType must not be null");
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler must not be null");
    }

    /**
     * Creates a builder for a controller reconciling the given resource type.
     *
     * @param <T> the primary resource type
     * @param resourceType the primary resource class
     * @param reconciler the reconciler invoked for the primary resource
     * @return a new controller builder
     */
    public static <T extends HasMetadata> ControllerBuilder<T> forResource(
            Class<T> resourceType,
            Reconciler<T> reconciler) {
        return new ControllerBuilder<>(resourceType, reconciler);
    }

    /**
     * Configures whether reconciliation triggers only on generation changes.
     *
     * @param enabled {@code true} to ignore events that do not change the generation
     * @return this builder
     */
    public synchronized ControllerBuilder<T> generationFilter(boolean enabled) {
        generationFilter = enabled;
        return this;
    }

    /**
     * Configures the periodic resync interval for the primary resource.
     *
     * @param period the time between resync events
     * @return this builder
     * @throws NullPointerException if {@code period} is null
     * @throws IllegalArgumentException if the period is negative
     */
    public synchronized ControllerBuilder<T> resyncPeriod(Duration period) {
        Objects.requireNonNull(period, "period must not be null");
        if (period.isNegative()) {
            throw new IllegalArgumentException("period must not be negative");
        }
        resyncPeriod = period;
        return this;
    }

    /**
     * Registers an owned resource type whose changes reconcile the primary resource.
     *
     * @param <S> the owned resource type
     * @param resourceType the owned resource class
     * @return this builder
     * @throws NullPointerException if {@code resourceType} is null
     */
    public synchronized <S extends HasMetadata> ControllerBuilder<T> owns(Class<S> resourceType) {
        ownedResources.add(Objects.requireNonNull(resourceType, "resourceType must not be null"));
        return this;
    }

    /**
     * Registers the dependent's type as an owned resource; submit its desired state via
     * {@code Dependents.apply}.
     *
     * @param dependent the dependent resource whose type is added as owned
     * @return this builder
     * @throws NullPointerException if {@code dependent} is null
     */
    public synchronized ControllerBuilder<T> manages(DependentResource<? extends HasMetadata, T> dependent) {
        Objects.requireNonNull(dependent, "dependent must not be null");
        return owns(dependent.resourceType());
    }

    /**
     * Registers a named watch on a secondary resource type, mapping its events to primary resources.
     *
     * @param <S> the secondary resource type
     * @param name the unique watch name
     * @param resourceType the secondary resource class
     * @param mapper maps a secondary resource event to primary resource keys
     * @return this builder
     * @throws NullPointerException if {@code resourceType} or {@code mapper} is null
     * @throws IllegalArgumentException if the name is blank or already registered
     */
    public synchronized <S extends HasMetadata> ControllerBuilder<T> watches(
            String name,
            Class<S> resourceType,
            ResourceMapper<S, T> mapper) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        var watch = new ControllerRegistration.SecondaryWatch<>(name, resourceType, mapper);
        if (secondaryWatches.putIfAbsent(name, watch) != null) {
            throw new IllegalArgumentException("watch name already registered: " + name);
        }
        return this;
    }

    /**
     * Configures the controller to also watch Kubernetes {@code Event} objects.
     *
     * @return this builder
     */
    public synchronized ControllerBuilder<T> watchesKubernetesEvents() {
        kubernetesEvents = true;
        return this;
    }

    /**
     * Restricts the primary watch to resources matching the given label equality selector.
     *
     * <p>Calling this method again replaces the previous label selector while retaining the current
     * field selector.
     *
     * @param labels the label selector
     * @return this builder
     * @throws NullPointerException if {@code labels} is null
     */
    public synchronized ControllerBuilder<T> labelSelector(Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels must not be null");
        var current = watchSelector == null ? WatchSelector.empty() : watchSelector;
        watchSelector = new WatchSelector(Map.copyOf(labels), current.fields());
        return this;
    }

    /**
     * Restricts the primary watch to resources matching the given field equality selector.
     *
     * <p>Calling this method again replaces the previous field selector while retaining the current
     * label selector.
     *
     * @param fields the field selector
     * @return this builder
     * @throws NullPointerException if {@code fields} is null
     */
    public synchronized ControllerBuilder<T> fieldSelector(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        var current = watchSelector == null ? WatchSelector.empty() : watchSelector;
        watchSelector = new WatchSelector(current.labels(), Map.copyOf(fields));
        return this;
    }

    /**
     * Registers an index on a field extracted from the primary resource.
     *
     * @param key the unique index key
     * @param extractor computes the indexed field value from a resource
     * @return this builder
     * @throws NullPointerException if {@code key} or {@code extractor} is null
     * @throws IllegalArgumentException if the key is already registered
     */
    public synchronized ControllerBuilder<T> indexField(String key, Function<T, String> extractor) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(extractor, "extractor must not be null");
        if (indexFields.putIfAbsent(key, extractor) != null) {
            throw new IllegalArgumentException("index field already registered: " + key);
        }
        return this;
    }

    /**
     * Builds the immutable controller registration.
     *
     * @return the controller registration
     */
    public synchronized ControllerRegistration<T> build() {
        return new ControllerRegistration<>(snapshot());
    }

    private Snapshot<T> snapshot() {
        return new Snapshot<>(
                resourceType,
                reconciler,
                Optional.ofNullable(generationFilter),
                Optional.ofNullable(resyncPeriod),
                List.copyOf(ownedResources),
                List.copyOf(secondaryWatches.values()),
                kubernetesEvents,
                Optional.ofNullable(watchSelector),
                Map.copyOf(indexFields));
    }

    record Snapshot<T extends HasMetadata>(
            Class<T> resourceType,
            Reconciler<T> reconciler,
            Optional<Boolean> generationFilter,
            Optional<Duration> resyncPeriod,
            List<Class<? extends HasMetadata>> ownedResources,
            List<ControllerRegistration.SecondaryWatch<? extends HasMetadata, T>> secondaryWatches,
            boolean kubernetesEvents,
            Optional<WatchSelector> watchSelector,
            Map<String, Function<T, String>> indexFields) {
    }

    /**
     * Equality-match label and field selectors for the primary watch; empty maps mean no filtering.
     *
     * @param labels label equality selectors
     * @param fields field equality selectors
     */
    public record WatchSelector(Map<String, String> labels, Map<String, String> fields) {
        /**
         * Copies nullable selector maps into immutable maps.
         *
         * @param labels label equality selectors, or {@code null}
         * @param fields field equality selectors, or {@code null}
         */
        public WatchSelector {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }

        /**
         * Creates a selector that matches all resources.
         *
         * @return an empty selector with no filtering
         */
        public static WatchSelector empty() {
            return new WatchSelector(Map.of(), Map.of());
        }
    }
}
