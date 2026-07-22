package com.huawei.dcs.modelengine.operator.framework.reconciler;

/** Identifies whether a trigger came from a primary or secondary source. */
public enum TriggerRole {
    /** Triggered by the primary resource. */
    PRIMARY,
    /** Triggered by a secondary resource. */
    SECONDARY
}
