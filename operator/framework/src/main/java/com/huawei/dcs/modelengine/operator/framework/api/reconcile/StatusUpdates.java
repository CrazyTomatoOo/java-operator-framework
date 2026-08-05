/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import io.fabric8.kubernetes.client.utils.Serialization;

import java.util.Map;
import java.util.Objects;

/**
 * Helper to persist a resource's status through the {@code /status} subresource.
 *
 * <p>{@link #update} issues a JSON merge patch built from the given status object, so the resource
 * itself is never mutated — safe to pass an informer-cached instance. Requires the CRD to declare
 * a {@code status} subresource.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class StatusUpdates {
    private StatusUpdates() {
    }

    /**
     * Patches the status subresource with the given status; returns the server result.
     *
     * @param <T> resource type
     * @param client the Kubernetes client used to submit the patch
     * @param resource the resource whose status subresource is patched
     * @param status the desired status object, serialized as JSON
     * @return the server result after patching the status
     * @throws NullPointerException if {@code resource} or {@code status} is null
     */
    public static <T extends HasMetadata> T update(KubernetesClient client, T resource, Object status) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(status, "status");
        var body = Serialization.asJson(Map.of("status", status));
        return client.resource(resource)
                .subresource("status").patch(PatchContext.of(PatchType.JSON_MERGE), body);
    }
}
