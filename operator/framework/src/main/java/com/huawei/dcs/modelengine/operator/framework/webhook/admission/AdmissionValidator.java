package com.huawei.dcs.modelengine.operator.framework.webhook.admission;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

/**
 * Validates Kubernetes admission requests for a concrete resource type.
 *
 * @param <T> Resource type handled by this validator.
 */
@FunctionalInterface
public interface AdmissionValidator<T extends HasMetadata> {
    AdmissionResponse validate(AdmissionRequest request, T resource);
}
