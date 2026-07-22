package com.huawei.dcs.modelengine.operator.framework.source;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Objects;

public record SourceConfiguration<R extends HasMetadata>(
        String name,
        Class<R> resourceClass,
        SourceRole role,
        ResourceMapper<R, ?> mapper,
        boolean generationChangeFilter) {

    public SourceConfiguration {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(resourceClass, "resourceClass must not be null");
        Objects.requireNonNull(role, "role must not be null");
        if (role == SourceRole.SECONDARY) {
            Objects.requireNonNull(mapper, "mapper must not be null for secondary sources");
        }
    }

    public SourceConfiguration(String name, Class<R> resourceClass, SourceRole role, ResourceMapper<R, ?> mapper) {
        this(name, resourceClass, role, mapper, false);
    }
}
