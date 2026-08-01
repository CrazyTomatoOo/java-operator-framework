/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import java.util.Objects;

/**
 * Stable identity details for a Kubernetes resource involved in reconciliation.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public record ResourceReference(String apiVersion, String kind, String namespace, String name, String uid) {
    public ResourceReference {
        requireText(apiVersion, "apiVersion");
        requireText(kind, "kind");
        requireText(name, "name");
        if (namespace != null && namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must be null or non-blank");
        }
        if (uid != null && uid.isBlank()) {
            throw new IllegalArgumentException("uid must be null or non-blank");
        }
    }

    public static ResourceReference from(HasMetadata resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        var metadata = Objects.requireNonNull(resource.getMetadata(), "resource metadata must not be null");
        return new ResourceReference(resource.getApiVersion(), resource.getKind(), metadata.getNamespace(),
                metadata.getName(), metadata.getUid());
    }

    public ResourceKey key() {
        return new ResourceKey(namespace, name);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
