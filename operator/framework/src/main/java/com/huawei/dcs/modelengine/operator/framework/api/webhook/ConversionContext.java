package com.huawei.dcs.modelengine.operator.framework.api.webhook;

/** Transport-neutral source and target versions for one converted resource. */
public record ConversionContext(String sourceVersion, String desiredVersion) {
    public ConversionContext {
        requireText(sourceVersion, "sourceVersion");
        requireText(desiredVersion, "desiredVersion");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
