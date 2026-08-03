/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import java.util.Objects;
import java.util.Optional;

/**
 * Result returned by a resource converter.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class ConversionResult<T> {
    private final T resource;
    private final String message;

    private ConversionResult(T resource, String message) {
        this.resource = resource;
        this.message = message;
    }

    /**
     * Creates a result carrying the converted resource.
     *
     * @param <T> the resource type
     * @param resource the converted resource
     * @return a successful conversion result
     */
    public static <T> ConversionResult<T> converted(T resource) {
        return new ConversionResult<>(Objects.requireNonNull(resource, "resource must not be null"), null);
    }

    /**
     * Creates a result reporting a failed conversion.
     *
     * @param <T> the resource type
     * @param message the reason the conversion failed
     * @return a failed conversion result
     * @throws IllegalArgumentException if the message is blank
     */
    public static <T> ConversionResult<T> failed(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new ConversionResult<>(null, message);
    }

    /**
     * Checks whether the conversion succeeded.
     *
     * @return {@code true} when a converted resource is present
     */
    public boolean isConverted() {
        return resource != null;
    }

    /**
     * Returns the converted resource, if any.
     *
     * @return the converted resource, or empty when the conversion failed
     */
    public Optional<T> resource() {
        return Optional.ofNullable(resource);
    }

    /**
     * Returns the failure reason, if any.
     *
     * @return the message, or empty when the conversion succeeded
     */
    public Optional<String> message() {
        return Optional.ofNullable(message);
    }
}
