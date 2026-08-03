/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.DependentResource;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds an immutable controller registration.
 *
 * @author z00919064 zhangshjie
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
    public ControllerBuilder<T> generationFilter(boolean enabled) {
        generationFilter = enabled;
        return this;
    }

    /**
     * Configures the periodic resync interval for the primary resource.
     *
     * @param period the time between resync events
     * @return this builder
     * @throws IllegalArgumentException if the period is negative
     */
    public ControllerBuilder<T> resyncPeriod(Duration period) {
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
     */
    public <S extends HasMetadata> ControllerBuilder<T> owns(Class<S> resourceType) {
        ownedResources.add(Objects.requireNonNull(resourceType, "resourceType must not be null"));
        return this;
    }

    /**
     * Registers the dependent's type as an owned resource; submit its desired state via
     * {@code Dependents.apply}.
     *
     * @param dependent the dependent resource whose type is added as owned
     * @return this builder
     */
    public ControllerBuilder<T> manages(DependentResource<? extends HasMetadata, T> dependent) {
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
     * @throws IllegalArgumentException if the name is blank or already registered
     */
    public <S extends HasMetadata> ControllerBuilder<T> watches(
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
    public ControllerBuilder<T> watchesKubernetesEvents() {
        kubernetesEvents = true;
        return this;
    }

    /**
     * Restricts the primary watch to resources matching the given label equality selector.
     *
     * @param labels the label selector
     * @return this builder
     */
    public ControllerBuilder<T> labelSelector(Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels must not be null");
        var current = watchSelector == null ? WatchSelector.empty() : watchSelector;
        watchSelector = new WatchSelector(Map.copyOf(labels), current.fields());
        return this;
    }

    /**
     * Restricts the primary watch to resources matching the given field equality selector.
     *
     * @param fields the field selector
     * @return this builder
     */
    public ControllerBuilder<T> fieldSelector(Map<String, String> fields) {
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
     * @throws IllegalArgumentException if the key is already registered
     */
    public ControllerBuilder<T> indexField(String key, Function<T, String> extractor) {
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
    public ControllerRegistration<T> build() {
        return new ControllerRegistration<>(this);
    }

    Class<T> resourceType() {
        return resourceType;
    }

    Reconciler<T> reconciler() {
        return reconciler;
    }

    Optional<Boolean> generationFilter() {
        return Optional.ofNullable(generationFilter);
    }

    Optional<Duration> resyncPeriod() {
        return Optional.ofNullable(resyncPeriod);
    }

    Set<Class<? extends HasMetadata>> ownedResources() {
        return ownedResources;
    }

    Collection<ControllerRegistration.SecondaryWatch<? extends HasMetadata, T>> secondaryWatches() {
        return secondaryWatches.values();
    }

    boolean kubernetesEvents() {
        return kubernetesEvents;
    }

    WatchSelector watchSelector() {
        return watchSelector;
    }

    Map<String, Function<T, String>> indexFields() {
        return indexFields;
    }

    /** Equality-match label and field selectors for the primary watch; empty maps mean no filtering. */
    public record WatchSelector(Map<String, String> labels, Map<String, String> fields) {
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
