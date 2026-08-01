/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

/**
 * Kubernetes resource event types that can trigger reconciliation.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public enum ResourceEventType {
    ADDED,
    UPDATED,
    DELETED,
    RESYNC
}
