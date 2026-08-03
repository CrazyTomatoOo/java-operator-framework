/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import java.util.Objects;

/**
 * Describes the event that requested reconciliation.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public record ReconciliationTrigger(
        ResourceEventType eventType,
        TriggerRole role,
        ResourceReference resource) {
    public ReconciliationTrigger {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
    }
}
