package com.huawei.dcs.modelengine.operator.framework.webhook.conversion;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.List;
import java.util.Objects;

/**
 * Result returned by a conversion webhook handler.
 */
public final class ConversionResult {
    private final HasMetadata convertedObject;
    private final List<String> errors;

    private ConversionResult(HasMetadata convertedObject, List<String> errors) {
        this.convertedObject = convertedObject;
        this.errors = List.copyOf(errors);
    }

    public static ConversionResult converted(HasMetadata convertedObject) {
        return new ConversionResult(Objects.requireNonNull(convertedObject, "convertedObject must not be null"), List.of());
    }

    public static ConversionResult failed(String error) {
        String message = Objects.requireNonNull(error, "error must not be null").strip();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("error must not be blank");
        }
        return new ConversionResult(null, List.of(message));
    }

    public boolean successful() {
        return errors.isEmpty();
    }

    public HasMetadata convertedObject() {
        return convertedObject;
    }

    public List<String> errors() {
        return errors;
    }
}
