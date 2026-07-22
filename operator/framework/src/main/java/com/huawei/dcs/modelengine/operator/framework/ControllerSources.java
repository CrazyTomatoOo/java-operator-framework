package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.event.EventSubscriber;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Fluent API for adding secondary resource sources to a controller registration.
 *
 * @param <P> primary resource type handled by the controller
 */
public interface ControllerSources<P extends HasMetadata> {
    <S extends HasMetadata> ControllerSources<P> owns(Class<S> resourceClass);

    /**
     * Adds an Event subscriber as a secondary resource source.
     *
     * @param eventSubscriber the Event subscriber to add
     * @return this source configuration
     */
    ControllerSources<P> withEventSubscriber(EventSubscriber<P> eventSubscriber);

    <S extends HasMetadata> ControllerSources<P> watches(
        String name,
        Class<S> resourceClass,
        ResourceMapper<S, P> mapper);
}
