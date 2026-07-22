package com.huawei.dcs.modelengine.operator.framework.source;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Immutable snapshot of a Kubernetes resource event.
 *
 * @param type the event type
 * @param resource the current resource state
 * @param oldResource the previous resource state, if any
 * @param <R> the resource type
 */
public record ResourceEvent<R extends HasMetadata>(ResourceEventType type, R resource, R oldResource) {
}
