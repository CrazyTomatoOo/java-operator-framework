/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Converts a resource to the API version requested by a conversion operation.
 *
 * @param <T> resource type
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@FunctionalInterface
public interface ResourceConverter<T extends HasMetadata> {
    /**
     * Converts a resource to the requested API version.
     *
     * @param resource the resource to convert
     * @param context the conversion context
     * @return the conversion result
     * @throws Exception if conversion fails
     */
    ConversionResult<T> convert(T resource, ConversionContext context) throws Exception;
}
