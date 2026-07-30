package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import io.fabric8.kubernetes.api.model.HasMetadata;

/** Validates an admission request without depending on a transport protocol. */
@FunctionalInterface
public interface AdmissionValidator<T extends HasMetadata> {
    AdmissionDecision validate(T current, AdmissionContext context) throws Exception;
}
