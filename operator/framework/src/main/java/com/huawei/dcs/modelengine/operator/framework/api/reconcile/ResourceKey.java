/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

/**
 * Namespace and name of a Kubernetes resource. Namespace is null for cluster-scoped resources.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
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
