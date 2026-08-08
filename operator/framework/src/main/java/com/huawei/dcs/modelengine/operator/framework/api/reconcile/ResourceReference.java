/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Objects;

/**
 * Stable identity details for a Kubernetes resource involved in reconciliation.
 *
 * @param apiVersion resource API version
 * @param kind resource kind
 * @param namespace resource namespace, or {@code null} for a cluster-scoped resource
 * @param name resource name
 * @param uid resource UID, or {@code null} when unavailable
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public record ResourceReference(String apiVersion, String kind, String namespace, String name, String uid) {
    /**
     * Validates the resource identity details.
     *
     * @param apiVersion resource API version
     * @param kind resource kind
     * @param namespace resource namespace, or {@code null} for a cluster-scoped resource
     * @param name resource name
     * @param uid resource UID, or {@code null} when unavailable
     * @throws IllegalArgumentException if a required value is null or blank, or an optional value is blank
     */
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

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    /**
     * Creates a reference from a Kubernetes resource and its metadata.
     *
     * @param resource the resource to describe
     * @return a reference holding the resource's identity details
     * @throws NullPointerException if {@code resource} or its metadata is null
     */
    public static ResourceReference from(HasMetadata resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        var metadata = Objects.requireNonNull(resource.getMetadata(), "resource metadata must not be null");
        return new ResourceReference(resource.getApiVersion(), resource.getKind(), metadata.getNamespace(),
            metadata.getName(), metadata.getUid());
    }

    /**
     * Returns the namespace and name key of this reference.
     *
     * @return the resource key
     */
    public ResourceKey key() {
        return new ResourceKey(namespace, name);
    }
}
