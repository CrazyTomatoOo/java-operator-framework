/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import java.util.Objects;

/**
 * Describes the normalized event queued to request reconciliation.
 *
 * <p>For secondary watches, the controller creates this value after mapping an informer
 * {@code ResourceEvent}. Primary events may create it directly from the informer callback.
 * Unlike {@code ResourceEvent}, this trigger keeps only the triggering resource identity,
 * event type, and role; resource snapshots remain available only to the mapper.
 *
 * @param eventType event type that triggered reconciliation
 * @param role role of the triggering resource
 * @param resource triggering resource identity
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public record ReconciliationTrigger(ResourceEventType eventType, TriggerRole role, ResourceReference resource) {
    /**
     * Validates the triggering event details.
     *
     * @param eventType event type that triggered reconciliation
     * @param role role of the triggering resource
     * @param resource triggering resource identity
     * @throws NullPointerException if any argument is null
     */
    public ReconciliationTrigger {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
    }
}
