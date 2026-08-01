/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Converts a resource to the API version requested by a conversion operation.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
@FunctionalInterface
public interface ResourceConverter<T extends HasMetadata> {
    ConversionResult<T> convert(T resource, ConversionContext context) throws Exception;
}
