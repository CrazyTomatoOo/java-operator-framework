/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Controller owner-reference helper for dependent resources.
 *
 * <p>A dependent carrying a {@code controller=true} owner reference is garbage-collected by
 * Kubernetes when its owner is deleted, and is matched by {@code Mappers.ownerReferences()} for
 * reconcile triggering. {@link #setController} writes that reference before the dependent is
 * submitted (see {@link Applies}). The reference is only valid when owner and dependent share a
 * namespace, or the owner is cluster-scoped; a dependent already controlled by a different owner
 * is rejected rather than silently taken over.
 *
 * @author z00919064 zhangshijie
 * @since 2026-08-01
 */
public final class Owners {
    private Owners() {
    }

    /**
     * Sets (or idempotently refreshes) the controller owner reference from {@code owner} on
     * {@code dependent}; returns the dependent. {@code blockOwnerDeletion} is set, so the owner
     * must have delete permission on the dependent's kind.
     *
     * @param owner the owning (controller) resource
     * @param dependent the dependent resource to stamp the owner reference on
     * @return the dependent with the controller owner reference set
     */
    public static <T extends HasMetadata> T setController(HasMetadata owner, T dependent) {
        Objects.requireNonNull(dependent, "dependent");
        var ownerMeta = requireValidOwner(owner);
        var dependentMeta = Objects.requireNonNull(dependent.getMetadata(), "dependent metadata");
        requireSameNamespace(ownerMeta, dependentMeta);
        var refs = new ArrayList<OwnerReference>();
        if (dependentMeta.getOwnerReferences() != null) {
            refs.addAll(dependentMeta.getOwnerReferences());
        }
        rejectForeignController(refs, ownerMeta.getUid());
        refs.removeIf(ref -> Boolean.TRUE.equals(ref.getController()));
        refs.add(controllerReference(owner, ownerMeta));
        dependentMeta.setOwnerReferences(refs);
        return dependent;
    }

    private static ObjectMeta requireValidOwner(HasMetadata owner) {
        Objects.requireNonNull(owner, "owner");
        var meta = Objects.requireNonNull(owner.getMetadata(), "owner metadata");
        if (meta.getName() == null || meta.getName().isBlank()) {
            throw new IllegalArgumentException("owner name must not be blank");
        }
        if (meta.getUid() == null || meta.getUid().isBlank()) {
            throw new IllegalArgumentException(
                    "owner uid must not be blank; the owner must already exist on the server");
        }
        return meta;
    }

    private static void requireSameNamespace(ObjectMeta owner, ObjectMeta dependent) {
        if (owner.getNamespace() != null && !owner.getNamespace().equals(dependent.getNamespace())) {
            throw new IllegalArgumentException(
                    "owner and dependent must share a namespace, or the owner must be cluster-scoped");
        }
    }

    private static void rejectForeignController(List<OwnerReference> refs, String ownerUid) {
        for (var ref : refs) {
            if (Boolean.TRUE.equals(ref.getController()) && !ownerUid.equals(ref.getUid())) {
                throw new IllegalStateException(String.format(
                        "dependent is already controlled by %s/%s (uid %s)",
                        ref.getKind(), ref.getName(), ref.getUid()));
            }
        }
    }

    private static OwnerReference controllerReference(HasMetadata owner, ObjectMeta meta) {
        return new OwnerReferenceBuilder()
                .withApiVersion(owner.getApiVersion())
                .withKind(owner.getKind())
                .withName(meta.getName())
                .withUid(meta.getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }
}
