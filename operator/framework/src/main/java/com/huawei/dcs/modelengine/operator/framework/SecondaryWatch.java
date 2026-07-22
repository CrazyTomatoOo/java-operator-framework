package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.source.ResourceMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Objects;

/**
 * Describes a secondary resource source for a controller.
 *
 * @param <P> primary resource type reconciled by the controller
 * @param <S> secondary resource type being watched
 * @param name stable source name
 * @param resourceClass secondary resource class
 * @param mapper mapper from secondary events to primary reconcile requests
 * @param owned whether the secondary resource is owned by the primary resource
 */
public record SecondaryWatch<P extends HasMetadata, S extends HasMetadata>(
    String name,
    Class<S> resourceClass,
    ResourceMapper<S, P> mapper,
    boolean owned) {

    public SecondaryWatch {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(resourceClass, "resourceClass must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
    }
}
