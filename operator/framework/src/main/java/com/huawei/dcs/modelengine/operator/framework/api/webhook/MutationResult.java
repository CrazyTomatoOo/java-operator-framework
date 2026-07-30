package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import java.util.Objects;
import java.util.Optional;

/** Result returned by an admission mutator. */
public final class MutationResult<T> {
    private final Status status;
    private final T resource;
    private final String message;

    private MutationResult(Status status, T resource, String message) {
        this.status = status;
        this.resource = resource;
        this.message = message;
    }

    public static <T> MutationResult<T> unchanged() {
        return new MutationResult<>(Status.UNCHANGED, null, null);
    }

    public static <T> MutationResult<T> mutated(T resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        return new MutationResult<>(Status.MUTATED, resource, null);
    }

    public static <T> MutationResult<T> denied(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new MutationResult<>(Status.DENIED, null, message);
    }

    public Status status() {
        return status;
    }

    public Optional<T> resource() {
        return Optional.ofNullable(resource);
    }

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
