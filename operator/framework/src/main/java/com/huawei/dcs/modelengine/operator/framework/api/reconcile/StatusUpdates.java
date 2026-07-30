package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.Objects;

/**
 * Helper to persist a resource's status through the {@code /status} subresource.
 *
 * <p>Set the status on the resource (e.g. {@code resource.getStatus().setPhase("Ready")}) then call
 * {@link #update}. Requires the CRD to declare a {@code status} subresource.
 */
public final class StatusUpdates {
    private StatusUpdates() {
    }

    /** Updates the status subresource with the resource's current status; returns the server result. */
    public static <T extends HasMetadata> T update(KubernetesClient client, T resource) {
        Objects.requireNonNull(resource, "resource");
        return client.resource(resource).updateStatus();
    }
}
