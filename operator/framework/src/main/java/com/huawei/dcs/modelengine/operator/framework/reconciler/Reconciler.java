package com.huawei.dcs.modelengine.operator.framework.reconciler;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * User-provided reconciliation logic for one Kubernetes resource type.
 *
 * @param <T> resource type handled by this reconciler
 */
@FunctionalInterface
public interface Reconciler<T extends HasMetadata> {
    Result reconcile(Request request, T resource);
}
