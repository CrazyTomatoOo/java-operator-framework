package com.huawei.dcs.modelengine.operator.framework.reconciler;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventType;

import java.util.Objects;

/** Captures the source of a reconciliation request. */
public record Trigger(
        /** Event type that produced the request. */
        ResourceEventType eventType,
        /** Resource API version. */
        String apiVersion,
        /** Resource kind. */
        String kind,
        /** Resource namespace. */
        String namespace,
        /** Resource name. */
        String name,
        /** Resource UID. */
        String uid,
        /** Trigger role. */
        TriggerRole role) {

    public Trigger {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(role, "role");
    }

    /** Creates a trigger from resource metadata. */
    public static Trigger from(HasMetadata resource, ResourceEventType eventType, TriggerRole role) {
        HasMetadata nonNullResource = Objects.requireNonNull(resource, "resource");
        ObjectMeta metadata = Objects.requireNonNull(nonNullResource.getMetadata(), "resource.metadata");
        return new Trigger(
                eventType,
                nonNullResource.getApiVersion(),
                nonNullResource.getKind(),
                metadata.getNamespace(),
                metadata.getName(),
                metadata.getUid(),
                role);
    }
}
