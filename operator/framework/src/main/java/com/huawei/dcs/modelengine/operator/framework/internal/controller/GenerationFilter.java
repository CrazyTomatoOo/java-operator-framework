package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Objects;

/** Selects primary updates relevant to reconciliation. */
final class GenerationFilter {
    private GenerationFilter() {
    }

    static boolean accepts(HasMetadata previous, HasMetadata current, boolean enabled) {
        if (!enabled || isResync(previous, current)) {
            return true;
        }
        var oldMetadata = previous.getMetadata();
        var newMetadata = current.getMetadata();
        return !Objects.equals(oldMetadata.getGeneration(), newMetadata.getGeneration())
                || !Objects.equals(oldMetadata.getDeletionTimestamp(), newMetadata.getDeletionTimestamp())
                || !Objects.equals(oldMetadata.getFinalizers(), newMetadata.getFinalizers());
    }

    static boolean isResync(HasMetadata previous, HasMetadata current) {
        if (previous == current) {
            return true;
        }
        var resourceVersion = previous.getMetadata().getResourceVersion();
        return resourceVersion != null
                && resourceVersion.equals(current.getMetadata().getResourceVersion());
    }
}
