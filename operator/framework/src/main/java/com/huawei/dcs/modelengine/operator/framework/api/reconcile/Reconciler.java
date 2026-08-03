/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Reconciles one Kubernetes resource.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@FunctionalInterface
public interface Reconciler<T extends HasMetadata> {
    ReconcileResult reconcile(T resource, ReconciliationContext<T> context) throws Exception;
}
