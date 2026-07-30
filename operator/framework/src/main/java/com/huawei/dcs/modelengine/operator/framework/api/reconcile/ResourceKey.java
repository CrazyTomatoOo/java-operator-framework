package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

/** Namespace and name of a Kubernetes resource. Namespace is null for cluster-scoped resources. */
public record ResourceKey(String namespace, String name) {
    public ResourceKey {
        if (namespace != null && namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must be null or non-blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
