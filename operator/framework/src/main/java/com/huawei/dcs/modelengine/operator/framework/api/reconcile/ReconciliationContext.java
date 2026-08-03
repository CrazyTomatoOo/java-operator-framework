/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.cache.Indexer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable context for one reconciliation attempt.
 *
 * <p>{@code cache} exposes the primary informer's {@link Indexer} so a reconciler can run
 * reverse lookups ({@code byIndex}, {@code indexKeys}) without a server round-trip. The runtime
 * always supplies a non-null cache; {@link #withoutCache} exists for tests and standalone use
 * where cache access is unnecessary, and leaves cache null — do not call by-index/get-by-key
 * on such a context.
 *
 * <p>{@link #cacheFor} exposes the informer caches of owned/watched secondary types (and the
 * primary type), so reads of related resources stay off the API server too.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public record ReconciliationContext<T extends HasMetadata>(
        ResourceKey resourceKey,
        List<ReconciliationTrigger> triggers,
        Indexer<T> cache,
        Map<Class<? extends HasMetadata>, Indexer<?>> caches) {
    public ReconciliationContext {
        Objects.requireNonNull(resourceKey, "resourceKey must not be null");
        triggers = List.copyOf(Objects.requireNonNull(triggers, "triggers must not be null"));
        // ponytail: no defensive copy — the runtime map is populated once in configureInformers,
        // before any context is created, and never mutated afterwards
        caches = Objects.requireNonNull(caches, "caches must not be null");
    }

    /**
     * Backward-compatible factory: no secondary caches.
     *
     * @param resourceKey the namespace and name of the primary resource
     * @param triggers the events that triggered this reconciliation
     * @param cache the primary-type informer cache
     */
    public ReconciliationContext(ResourceKey resourceKey, List<ReconciliationTrigger> triggers, Indexer<T> cache) {
        this(resourceKey, triggers, cache, Map.of());
    }

    /**
     * Informer cache for an owned/watched secondary type (or the primary type), avoiding a server
     * round-trip. Throws when the type has no informer — declare it via owns()/watches(). When the
     * same type is both primary and owned, the owned (unfiltered) informer wins.
     *
     * @param type the owned/watched secondary type (or the primary type)
     * @return the informer cache for the type
     */
    @SuppressWarnings("unchecked")
    public <S extends HasMetadata> Indexer<S> cacheFor(Class<S> type) {
        var indexer = caches.get(Objects.requireNonNull(type, "type must not be null"));
        if (indexer == null) {
            throw new IllegalStateException("no informer cache for " + type.getSimpleName()
                    + "; declare it via owns()/watches() on the controller builder");
        }
        return (Indexer<S>) indexer;
    }

    /**
     * Test/standalone factory: cache is null. Do not call byIndex/getByKey on the result.
     *
     * @param resourceKey the namespace and name of the primary resource
     * @param triggers the events that triggered this reconciliation
     * @return a context with no informer cache
     */
    public static <T extends HasMetadata> ReconciliationContext<T> withoutCache(
            ResourceKey resourceKey, List<ReconciliationTrigger> triggers) {
        return new ReconciliationContext<>(resourceKey, triggers, null, Map.of());
    }
}
