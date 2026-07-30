package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;

/** Reconciles one Kubernetes resource. */
@FunctionalInterface
public interface Reconciler<T extends HasMetadata> {
    ReconcileResult reconcile(T resource, ReconciliationContext<T> context) throws Exception;
}
