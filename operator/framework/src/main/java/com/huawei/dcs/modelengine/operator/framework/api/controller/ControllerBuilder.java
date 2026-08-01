/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

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

    public static <T extends HasMetadata> ControllerBuilder<T> forResource(
            Class<T> resourceType,
            Reconciler<T> reconciler) {
        return new ControllerBuilder<>(resourceType, reconciler);
    }

    public ControllerBuilder<T> generationFilter(boolean enabled) {
        generationFilter = enabled;
        return this;
    }

    public ControllerBuilder<T> resyncPeriod(Duration period) {
        Objects.requireNonNull(period, "period must not be null");
        if (period.isNegative()) {
            throw new IllegalArgumentException("period must not be negative");
        }
        resyncPeriod = period;
        return this;
    }

    public <S extends HasMetadata> ControllerBuilder<T> owns(Class<S> resourceType) {
        ownedResources.add(Objects.requireNonNull(resourceType, "resourceType must not be null"));
        return this;
    }

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

    public ControllerBuilder<T> watchesKubernetesEvents() {
        kubernetesEvents = true;
        return this;
    }

    public ControllerBuilder<T> labelSelector(Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels must not be null");
        var current = watchSelector == null ? WatchSelector.empty() : watchSelector;
        watchSelector = new WatchSelector(Map.copyOf(labels), current.fields());
        return this;
    }

    public ControllerBuilder<T> fieldSelector(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        var current = watchSelector == null ? WatchSelector.empty() : watchSelector;
        watchSelector = new WatchSelector(current.labels(), Map.copyOf(fields));
        return this;
    }

    public ControllerBuilder<T> indexField(String key, Function<T, String> extractor) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(extractor, "extractor must not be null");
        if (indexFields.putIfAbsent(key, extractor) != null) {
            throw new IllegalArgumentException("index field already registered: " + key);
        }
        return this;
    }

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

        public static WatchSelector empty() {
            return new WatchSelector(Map.of(), Map.of());
        }
    }
}
