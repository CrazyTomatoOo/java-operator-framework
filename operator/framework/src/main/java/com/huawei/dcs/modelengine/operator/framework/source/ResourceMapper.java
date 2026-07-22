package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Collection;

/**
 * Maps a secondary resource event into the primary reconcile requests it should trigger.
 *
 * @param <R> secondary resource type observed by the mapper
 * @param <P> primary resource type associated with the resulting requests
 */
@FunctionalInterface
public interface ResourceMapper<R extends HasMetadata, P extends HasMetadata> {
    /**
     * Converts a secondary resource event into the primary requests that should be reconciled.
     *
     * @param secondary the secondary resource that changed
     * @param event the event associated with the secondary resource
     * @return the collection of reconcile requests to enqueue for primary resources
     */
    Collection<Request> map(R secondary, ResourceEvent<R> event);
}
