package com.huawei.dcs.modelengine.operator.framework.source;

/** Describes the resource events observed by an event source. */
public enum ResourceEventType {
    /** Resource added. */
    ADD,
    /** Resource updated. */
    UPDATE,
    /** Resource deleted. */
    DELETE,
    /** Periodic resync. */
    RESYNC
}
