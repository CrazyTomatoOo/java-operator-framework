/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of a Kubernetes resource event.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public record ResourceEvent<S extends HasMetadata>(
        ResourceEventType type,
        S resource,
        Optional<S> previousResource) {
    public ResourceEvent {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        previousResource = Objects.requireNonNull(previousResource, "previousResource must not be null");
    }

    public static <S extends HasMetadata> ResourceEvent<S> added(S resource) {
        return new ResourceEvent<>(ResourceEventType.ADDED, resource, Optional.empty());
    }

    public static <S extends HasMetadata> ResourceEvent<S> updated(S previousResource, S resource) {
        return new ResourceEvent<>(ResourceEventType.UPDATED, resource, Optional.of(previousResource));
    }

    public static <S extends HasMetadata> ResourceEvent<S> deleted(S resource) {
        return new ResourceEvent<>(ResourceEventType.DELETED, resource, Optional.empty());
    }

    public static <S extends HasMetadata> ResourceEvent<S> resync(S resource) {
        return new ResourceEvent<>(ResourceEventType.RESYNC, resource, Optional.empty());
    }
}
