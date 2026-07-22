package com.huawei.dcs.modelengine.operator.framework.webhook.conversion;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Converts a Kubernetes resource to a requested API version.
 */
@FunctionalInterface
public interface ConversionWebhookHandler {
    ConversionResult convert(String desiredVersion, HasMetadata resource);
}
