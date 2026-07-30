package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

/** Kubernetes resource event types that can trigger reconciliation. */
public enum ResourceEventType {
    ADDED,
    UPDATED,
    DELETED,
    RESYNC
}
