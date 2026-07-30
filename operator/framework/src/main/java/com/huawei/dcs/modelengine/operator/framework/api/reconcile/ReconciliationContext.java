package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.informers.cache.Indexer;

import java.util.List;
import java.util.Objects;

/**
 * Immutable context for one reconciliation attempt.
 *
 * <p>{@code cache} exposes the primary informer's {@link Indexer} so a reconciler can run
 * reverse lookups ({@code byIndex}, {@code indexKeys}) without a server round-trip. The runtime
 * always supplies a non-null cache; {@link #withoutCache} exists for tests and standalone use
 * where cache access is unnecessary, and leaves cache null — do not call by-index/get-by-key
 * on such a context.
 */
public record ReconciliationContext<T extends HasMetadata>(
        ResourceKey resourceKey,
        List<ReconciliationTrigger> triggers,
        Indexer<T> cache) {
    public ReconciliationContext {
        Objects.requireNonNull(resourceKey, "resourceKey must not be null");
        triggers = List.copyOf(Objects.requireNonNull(triggers, "triggers must not be null"));
    }

    /** Test/standalone factory: cache is null. Do not call byIndex/getByKey on the result. */
    public static <T extends HasMetadata> ReconciliationContext<T> withoutCache(
            ResourceKey resourceKey, List<ReconciliationTrigger> triggers) {
        return new ReconciliationContext<>(resourceKey, triggers, null);
    }
}
