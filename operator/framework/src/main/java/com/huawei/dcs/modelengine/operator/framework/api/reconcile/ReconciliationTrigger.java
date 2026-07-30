package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import java.util.Objects;

/** Describes the event that requested reconciliation. */
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
