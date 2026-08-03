/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

/**
 * Transport-neutral source and target versions for one converted resource.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
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
