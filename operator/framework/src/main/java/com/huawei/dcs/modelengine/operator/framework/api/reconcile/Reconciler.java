/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Reconciles one Kubernetes resource.
 *
 * @param <T> primary resource type
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@FunctionalInterface
public interface Reconciler<T extends HasMetadata> {
    /**
     * Reconciles the current resource and returns its scheduling decision.
     *
     * @param resource the current resource
     * @param context the reconciliation context
     * @return the scheduling decision for the next reconciliation
     * @throws Exception if reconciliation fails
     */
    ReconcileResult reconcile(T resource, ReconciliationContext<T> context) throws Exception;
}
