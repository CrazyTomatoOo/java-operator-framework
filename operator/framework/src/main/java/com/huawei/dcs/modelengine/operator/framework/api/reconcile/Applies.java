/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ServerSideApplicable;
import io.fabric8.kubernetes.client.utils.Serialization;

import java.util.Objects;

/**
 * Server-side apply helper for resources an operator manages.
 *
 * <p>{@link #apply} submits the full desired state through the Kubernetes apply patch type, so
 * create-or-update is one call and fields owned by other managers stay untouched. The field
 * manager must be explicit — fabric8 otherwise defaults to {@code fabric8}, and managers sharing
 * one name silently take over each other's fields. Use {@link #applyForcibly} to take ownership
 * of conflicting fields. The desired resource is copied before submission, so informer-cached
 * instances are not mutated.
 *
 * @author z00919064 zhangshijie
 * @since 2026-08-01
 */
public final class Applies {
    private Applies() {
    }

    /**
     * Server-side-applies the desired state under the given field manager; returns the server result.
     *
     * @param <T> resource type
     * @param client the Kubernetes client used to submit the apply
     * @param desired the desired state; copied before submission to protect informer-cached instances
     * @param fieldManager the explicit field manager name
     * @return the server result after applying {@code desired}
     * @throws NullPointerException if {@code desired} is null
     * @throws IllegalArgumentException if {@code fieldManager} is blank
     */
    public static <T extends HasMetadata> T apply(KubernetesClient client, T desired, String fieldManager) {
        return operation(client, desired, fieldManager).serverSideApply();
    }

    /**
     * Like {@link #apply}, but forces conflicts, taking ownership of fields owned by other managers.
     *
     * @param <T> resource type
     * @param client the Kubernetes client used to submit the apply
     * @param desired the desired state; copied before submission to protect informer-cached instances
     * @param fieldManager the explicit field manager name
     * @return the server result after applying {@code desired}
     * @throws NullPointerException if {@code desired} is null
     * @throws IllegalArgumentException if {@code fieldManager} is blank
     */
    public static <T extends HasMetadata> T applyForcibly(KubernetesClient client, T desired, String fieldManager) {
        return operation(client, desired, fieldManager).forceConflicts().serverSideApply();
    }

    private static <T extends HasMetadata> ServerSideApplicable<T> operation(
            KubernetesClient client, T desired, String fieldManager) {
        Objects.requireNonNull(desired, "desired");
        if (fieldManager == null || fieldManager.isBlank()) {
            throw new IllegalArgumentException("fieldManager must not be blank");
        }
        return client.resource(Serialization.clone(desired)).fieldManager(fieldManager);
    }
}
