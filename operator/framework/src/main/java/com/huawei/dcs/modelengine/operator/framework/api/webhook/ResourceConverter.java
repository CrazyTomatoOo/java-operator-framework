package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import io.fabric8.kubernetes.api.model.HasMetadata;

/** Converts a resource to the API version requested by a conversion operation. */
@FunctionalInterface
public interface ResourceConverter<T extends HasMetadata> {
    ConversionResult<T> convert(T resource, ConversionContext context) throws Exception;
}
