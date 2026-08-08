/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of a Kubernetes resource event observed by a controller.
 *
 * <p>A {@code ResourceEvent} is the informer-level event supplied to a {@link ResourceMapper} before
 * the controller converts it into a {@link ReconciliationTrigger}. It carries the current
 * resource and, for update events, the previous state. The resulting trigger is the
 * normalized resource-identity event queued for reconciliation.
 *
 * @param <S> resource type
 * @param type event type
 * @param resource current resource state
 * @param previousResource previous resource state, when available
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public record ResourceEvent<S extends HasMetadata>(ResourceEventType type, S resource, Optional<S> previousResource) {
    /**
     * Validates the event details.
     *
     * @param type event type
     * @param resource current resource state
     * @param previousResource previous resource state, when available
     * @throws NullPointerException if any argument is null
     */
    public ResourceEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        previousResource = Objects.requireNonNull(previousResource, "previousResource must not be null");
    }

    /**
     * Creates an event for a newly added resource.
     *
     * @param <S> the resource type
     * @param resource the added resource
     * @return an {@code ADDED} event
     */
    public static <S extends HasMetadata> ResourceEvent<S> added(S resource) {
        return new ResourceEvent<>(ResourceEventType.ADDED, resource, Optional.empty());
    }

    /**
     * Creates an event for an updated resource.
     *
     * @param <S> the resource type
     * @param previousResource the resource state before the update, or {@code null} if unavailable
     * @param resource the current resource state
     * @return an {@code UPDATED} event with an empty previous-resource value when unavailable
     */
    public static <S extends HasMetadata> ResourceEvent<S> updated(S previousResource, S resource) {
        return new ResourceEvent<>(ResourceEventType.UPDATED, resource, Optional.ofNullable(previousResource));
    }

    /**
     * Creates an event for a deleted resource.
     *
     * @param <S> the resource type
     * @param resource the deleted resource
     * @return a {@code DELETED} event
     */
    public static <S extends HasMetadata> ResourceEvent<S> deleted(S resource) {
        return new ResourceEvent<>(ResourceEventType.DELETED, resource, Optional.empty());
    }

    /**
     * Creates an event for a periodic resync of a resource.
     *
     * @param <S> the resource type
     * @param resource the resynced resource
     * @return a {@code RESYNC} event
     */
    public static <S extends HasMetadata> ResourceEvent<S> resync(S resource) {
        return new ResourceEvent<>(ResourceEventType.RESYNC, resource, Optional.empty());
    }
}
