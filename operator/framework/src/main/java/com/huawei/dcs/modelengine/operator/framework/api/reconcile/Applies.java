/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ServerSideApplicable;
import java.util.Objects;

/**
 * Server-side apply helper for resources an operator manages.
 *
 * <p>{@link #apply} submits the full desired state through the Kubernetes apply patch type, so
 * create-or-update is one call and fields owned by other managers stay untouched. The field
 * manager must be explicit — fabric8 otherwise defaults to {@code fabric8}, and managers sharing
 * one name silently take over each other's fields. Use {@link #applyForcibly} to take ownership
 * of conflicting fields. Pass a freshly built desired object; never apply a mutated
 * informer-cached instance.
 *
 * @author z00919064 zhangshjie
 * @since 2026-08-01
 */
public final class Applies {
    private Applies() {
    }

    /** Server-side-applies the desired state under the given field manager; returns the server result. */
    public static <T extends HasMetadata> T apply(KubernetesClient client, T desired, String fieldManager) {
        return operation(client, desired, fieldManager).serverSideApply();
    }

    /** Like {@link #apply}, but forces conflicts, taking ownership of fields owned by other managers. */
    public static <T extends HasMetadata> T applyForcibly(KubernetesClient client, T desired, String fieldManager) {
        return operation(client, desired, fieldManager).forceConflicts().serverSideApply();
    }

    private static <T extends HasMetadata> ServerSideApplicable<T> operation(
            KubernetesClient client, T desired, String fieldManager) {
        Objects.requireNonNull(desired, "desired");
        if (fieldManager == null || fieldManager.isBlank()) {
            throw new IllegalArgumentException("fieldManager must not be blank");
        }
        return client.resource(desired).fieldManager(fieldManager);
    }
}
