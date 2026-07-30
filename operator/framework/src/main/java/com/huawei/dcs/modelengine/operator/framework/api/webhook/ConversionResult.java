package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import java.util.Objects;
import java.util.Optional;

/** Result returned by a resource converter. */
public final class ConversionResult<T> {
    private final T resource;
    private final String message;

    private ConversionResult(T resource, String message) {
        this.resource = resource;
        this.message = message;
    }

    public static <T> ConversionResult<T> converted(T resource) {
        return new ConversionResult<>(Objects.requireNonNull(resource, "resource must not be null"), null);
    }

    public static <T> ConversionResult<T> failed(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new ConversionResult<>(null, message);
    }

    public boolean isConverted() {
        return resource != null;
    }

    public Optional<T> resource() {
        return Optional.ofNullable(resource);
    }

    public Optional<String> message() {
        return Optional.ofNullable(message);
    }
}
