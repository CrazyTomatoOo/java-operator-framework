package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/** Common mappings from secondary resources to primary resource keys. */
public final class Mappers {
    private Mappers() {
    }

    public static <S extends HasMetadata, T extends HasMetadata> ResourceMapper<S, T> ownerReferences() {
        return event -> ownerKeys(event, null, null);
    }

    public static <S extends HasMetadata, T extends HasMetadata> ResourceMapper<S, T> ownerReferences(
            Class<T> primaryType) {
        Objects.requireNonNull(primaryType, "primaryType must not be null");
        return event -> ownerKeys(event, HasMetadata.getApiVersion(primaryType), HasMetadata.getKind(primaryType));
    }

    public static <S extends HasMetadata, T extends HasMetadata> ResourceMapper<S, T> byLabel(String key) {
        return byMetadata(key, ObjectMeta::getLabels);
    }

    public static <S extends HasMetadata, T extends HasMetadata> ResourceMapper<S, T> byAnnotation(String key) {
        return byMetadata(key, ObjectMeta::getAnnotations);
    }

    public static <T extends HasMetadata> ResourceMapper<Event, T> involvedObject() {
        return event -> involvedObjectKeys(event, null, null);
    }

    public static <T extends HasMetadata> ResourceMapper<Event, T> involvedObject(Class<T> primaryType) {
        Objects.requireNonNull(primaryType, "primaryType must not be null");
        return event -> involvedObjectKeys(
                event, HasMetadata.getApiVersion(primaryType), HasMetadata.getKind(primaryType));
    }

    private static <S extends HasMetadata, T extends HasMetadata> ResourceMapper<S, T> byMetadata(
            String key,
            Function<ObjectMeta, Map<String, String>> values) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        Objects.requireNonNull(values, "values must not be null");
        return event -> mapMetadata(event, key, values);
    }

    private static <S extends HasMetadata> Collection<ResourceKey> mapMetadata(
            ResourceEvent<S> event,
            String key,
            Function<ObjectMeta, Map<String, String>> values) {
        return resources(event)
                .map(HasMetadata::getMetadata)
                .filter(Objects::nonNull)
                .flatMap(metadata -> metadataKey(metadata, key, values).stream())
                .distinct()
                .toList();
    }

    private static <S extends HasMetadata> Collection<ResourceKey> ownerKeys(
            ResourceEvent<S> event,
            String apiVersion,
            String kind) {
        return resources(event)
                .map(HasMetadata::getMetadata)
                .filter(Objects::nonNull)
                .flatMap(metadata -> ownerKeys(metadata, apiVersion, kind).stream())
                .distinct()
                .toList();
    }

    private static Collection<ResourceKey> ownerKeys(ObjectMeta metadata, String apiVersion, String kind) {
        if (metadata.getOwnerReferences() == null) {
            return List.of();
        }
        return metadata.getOwnerReferences().stream()
                .filter(owner -> Boolean.TRUE.equals(owner.getController()))
                .filter(owner -> matches(owner.getApiVersion(), apiVersion) && matches(owner.getKind(), kind))
                .filter(owner -> owner.getName() != null && !owner.getName().isBlank())
                .map(owner -> new ResourceKey(metadata.getNamespace(), owner.getName()))
                .toList();
    }

    private static Collection<ResourceKey> involvedObjectKeys(
            ResourceEvent<Event> event,
            String apiVersion,
            String kind) {
        return resources(event)
                .map(Event::getInvolvedObject)
                .filter(Objects::nonNull)
                .filter(reference -> matches(reference.getApiVersion(), apiVersion))
                .filter(reference -> matches(reference.getKind(), kind))
                .filter(reference -> reference.getName() != null && !reference.getName().isBlank())
                .map(reference -> new ResourceKey(reference.getNamespace(), reference.getName()))
                .distinct()
                .toList();
    }

    private static boolean matches(String actual, String expected) {
        return expected == null || Objects.equals(actual, expected);
    }

    private static Collection<ResourceKey> metadataKey(
            ObjectMeta metadata,
            String key,
            Function<ObjectMeta, Map<String, String>> values) {
        var entries = values.apply(metadata);
        var name = entries == null ? null : entries.get(key);
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return List.of(new ResourceKey(metadata.getNamespace(), name));
    }

    private static <S extends HasMetadata> Stream<S> resources(ResourceEvent<S> event) {
        return Stream.concat(Stream.of(event.resource()), event.previousResource().stream());
    }
}
