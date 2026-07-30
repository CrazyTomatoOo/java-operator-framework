package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

/** Role played by the triggering resource in a controller. */
public enum TriggerRole {
    PRIMARY,
    OWNED,
    WATCHED,
    KUBERNETES_EVENT
}
