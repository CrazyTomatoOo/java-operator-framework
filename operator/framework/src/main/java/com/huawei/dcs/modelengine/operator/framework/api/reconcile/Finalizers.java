/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;

import java.util.Objects;

/**
 * Static helpers for the Kubernetes finalizer pattern.
 *
 * <p>Reconcilers manage cleanup of external resources by adding a finalizer before doing work and
 * removing it once cleanup completes. {@code isDeleting} drives the cleanup branch.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class Finalizers {
    private Finalizers() {
    }

    /**
     * True when the resource has a deletion timestamp, meaning deletion has been requested.
     *
     * @param resource the resource to check
     * @return true if deletion has been requested
     * @throws NullPointerException if {@code resource} or its metadata is null
     */
    public static boolean isDeleting(HasMetadata resource) {
        Objects.requireNonNull(resource.getMetadata(), "metadata");
        return resource.getMetadata().getDeletionTimestamp() != null;
    }

    /**
     * True when the resource already carries the finalizer.
     *
     * @param resource the resource to check
     * @param finalizer the finalizer name
     * @return true if the resource already carries the finalizer
     * @throws NullPointerException if {@code resource} or its metadata is null
     */
    public static boolean present(HasMetadata resource, String finalizer) {
        Objects.requireNonNull(resource.getMetadata(), "metadata");
        var current = resource.getMetadata().getFinalizers();
        return current != null && current.contains(finalizer);
    }

    /**
     * Server-side JSON patch adding the finalizer when absent; returns the patched resource.
     *
     * @param <T> resource type
     * @param client the Kubernetes client used to submit the patch
     * @param resource the resource to add the finalizer to
     * @param finalizer the finalizer name
     * @return the patched resource
     * @throws IllegalArgumentException if {@code finalizer} is null or blank
     */
    public static <T extends HasMetadata> T add(KubernetesClient client, T resource, String finalizer) {
        requireName(finalizer);
        if (present(resource, finalizer)) {
            return resource;
        }
        var finalizers = resource.getMetadata().getFinalizers();
        var body = finalizers == null || finalizers.isEmpty()
                ? "[{\"op\":\"add\",\"path\":\"/metadata/finalizers\",\"value\":[\"" + escape(finalizer) + "\"]}]"
                : "[{\"op\":\"add\",\"path\":\"/metadata/finalizers/-\",\"value\":\"" + escape(finalizer) + "\"}]";
        return client.resource(resource).patch(PatchContext.of(PatchType.JSON), body);
    }

    /**
     * Server-side JSON patch removing the finalizer when present; returns the patched resource.
     *
     * @param <T> resource type
     * @param client the Kubernetes client used to submit the patch
     * @param resource the resource to remove the finalizer from
     * @param finalizer the finalizer name
     * @return the patched resource
     * @throws IllegalArgumentException if {@code finalizer} is null or blank
     */
    public static <T extends HasMetadata> T remove(KubernetesClient client, T resource, String finalizer) {
        requireName(finalizer);
        var finalizers = resource.getMetadata().getFinalizers();
        if (finalizers == null || !finalizers.contains(finalizer)) {
            return resource;
        }
        var body = "[{\"op\":\"remove\",\"path\":\"/metadata/finalizers/" + finalizers.indexOf(finalizer) + "\"}]";
        return client.resource(resource).patch(PatchContext.of(PatchType.JSON), body);
    }

    private static void requireName(String finalizer) {
        if (finalizer == null || finalizer.isBlank()) {
            throw new IllegalArgumentException("finalizer must not be blank");
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
