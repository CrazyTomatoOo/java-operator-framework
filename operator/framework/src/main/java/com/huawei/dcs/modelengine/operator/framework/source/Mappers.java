package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.List;
import java.util.Objects;

/**
 * Built-in resource mappers for common secondary watch relationships.
 */
public final class Mappers {
    private Mappers() {
    }

    public static <S extends HasMetadata, P extends HasMetadata> ResourceMapper<S, P> ownerReferences() {
        return (resource, event) -> {
            Objects.requireNonNull(resource, "resource must not be null");
            if (resource.getMetadata() == null || resource.getMetadata().getOwnerReferences() == null) {
                return List.of();
            }
            String namespace = resource.getMetadata().getNamespace();
            return resource.getMetadata().getOwnerReferences().stream()
                .filter(ownerReference -> Boolean.TRUE.equals(ownerReference.getController()))
                .map(ownerReference -> new Request(namespace, ownerReference.getName()))
                .toList();
        };
    }

    public static <S extends HasMetadata, P extends HasMetadata> ResourceMapper<S, P> byLabel(String nameLabel) {
        return byLabel(nameLabel, null);
    }

    public static <S extends HasMetadata, P extends HasMetadata> ResourceMapper<S, P> byLabel(
        String nameLabel,
        String namespaceLabel) {
        return (resource, event) -> {
            Objects.requireNonNull(resource, "resource must not be null");
            if (resource.getMetadata() == null || resource.getMetadata().getLabels() == null) {
                return List.of();
            }
            String name = resource.getMetadata().getLabels().get(nameLabel);
            if (name == null) {
                return List.of();
            }
            String namespace = namespaceLabel == null ? resource.getMetadata().getNamespace() : resource.getMetadata().getLabels().get(namespaceLabel);
            if (namespace == null) {
                return List.of();
            }
            return List.of(new Request(namespace, name));
        };
    }

    public static <S extends HasMetadata, P extends HasMetadata> ResourceMapper<S, P> byAnnotation(
        String nameAnnotation,
        String namespaceAnnotation) {
        return (resource, event) -> {
            Objects.requireNonNull(resource, "resource must not be null");
            if (resource.getMetadata() == null || resource.getMetadata().getAnnotations() == null) {
                return List.of();
            }
            String name = resource.getMetadata().getAnnotations().get(nameAnnotation);
            String namespace = resource.getMetadata().getAnnotations().get(namespaceAnnotation);
            if (name == null || namespace == null) {
                return List.of();
            }
            return List.of(new Request(namespace, name));
        };
    }
}
