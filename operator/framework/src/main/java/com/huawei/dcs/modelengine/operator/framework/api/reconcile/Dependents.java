/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.Objects;

/**
 * Submission helper for managed dependent resources.
 *
 * <p>{@link #apply} computes the dependent's desired state from the primary, marks it with the
 * controller owner reference ({@link Owners}), and server-side-applies it ({@link Applies}). The
 * apiserver creates the dependent when absent, converges owned fields when it drifts, and
 * garbage-collects it when the primary is deleted. Deleting an individual dependent while the
 * primary lives is out of scope — remove the owner reference or delete it through the client
 * directly.
 *
 * @author z00919064 zhangshijie
 * @since 2026-08-01
 */
public final class Dependents {
    private Dependents() {
    }

    /**
     * Computes the dependent's desired state, adds the controller owner reference to a defensive copy,
     * and server-side-applies it under {@code fieldManager}; returns the server result.
     *
     * @param <D> dependent resource type
     * @param <P> primary resource type
     * @param client the Kubernetes client used to submit the apply
     * @param dependent the dependent resource to reconcile
     * @param primary the primary resource that owns the dependent
     * @param context the reconciliation context
     * @param fieldManager the explicit field manager name
     * @return the server result after applying the dependent's desired state
     * @throws NullPointerException if {@code dependent}, {@code context}, or the desired dependent is null
     */
    public static <D extends HasMetadata, P extends HasMetadata> D apply(
            KubernetesClient client,
            DependentResource<D, P> dependent,
            P primary,
            ReconciliationContext<P> context,
            String fieldManager) {
        Objects.requireNonNull(dependent, "dependent");
        Objects.requireNonNull(context, "context");
        var desired = Objects.requireNonNull(
                dependent.desired(primary, context), "desired must not be null");
        var owned = Owners.setController(primary, desired);
        return Applies.apply(client, owned, fieldManager);
    }
}
