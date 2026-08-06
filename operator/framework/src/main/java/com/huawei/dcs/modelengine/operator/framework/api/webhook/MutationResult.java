/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import java.util.Objects;
import java.util.Optional;

/**
 * Result returned by an admission mutator.
 *
 * @param <T> resource type
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class MutationResult<T> {
    private final Status status;
    private final T resource;
    private final String message;

    private MutationResult(Status status, T resource, String message) {
        this.status = status;
        this.resource = resource;
        this.message = message;
    }

    /**
     * Creates a result that leaves the resource unmodified.
     *
     * <p>Use this only when the callback made no change. If the callback changed the object in
     * place, return {@link #mutated(Object)} even when the same object instance is supplied; the
     * explicit status distinguishes that case from {@code unchanged()}.
     *
     * @param <T> the resource type
     * @return an unchanged result with no resource
     */
    public static <T> MutationResult<T> unchanged() {
        return new MutationResult<>(Status.UNCHANGED, null, null);
    }

    /**
     * Creates a result that replaces the resource with a mutated version.
     *
     * <p>The {@code resource} may be the same instance received by the mutator when it was changed
     * in place. The explicit {@link Status#MUTATED} status distinguishes this result from
     * {@link #unchanged()}, and the transport computes the patch from the original request snapshot.
     *
     * @param <T> the resource type
     * @param resource the mutated resource
     * @return a mutated result carrying the new resource state
     * @throws NullPointerException if {@code resource} is null
     */
    public static <T> MutationResult<T> mutated(T resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        return new MutationResult<>(Status.MUTATED, resource, null);
    }

    /**
     * Creates a result that rejects the request during mutation.
     *
     * @param <T> the resource type
     * @param message the reason for denying the request
     * @return a denied result
     * @throws IllegalArgumentException if the message is null or blank
     */
    public static <T> MutationResult<T> denied(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new MutationResult<>(Status.DENIED, null, message);
    }

    /**
     * Returns the outcome of the mutation.
     *
     * @return the mutation status
     */
    public Status status() {
        return status;
    }

    /**
     * Returns the mutated resource, if any.
     *
     * @return the mutated resource, or empty when unchanged or denied
     */
    public Optional<T> resource() {
        return Optional.ofNullable(resource);
    }

    /**
     * Returns the denial reason, if any.
     *
     * @return the message, or empty when the request was not denied
     */
    public Optional<String> message() {
        return Optional.ofNullable(message);
    }

    /** Mutator outcome. */
    public enum Status {
        UNCHANGED,
        MUTATED,
        DENIED
    }
}
