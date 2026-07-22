package com.huawei.dcs.modelengine.operator.framework.webhook.admission;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

/**
 * Mutates Kubernetes admission requests for a concrete resource type.
 *
 * @param <T> Resource type handled by this mutator.
 */
@FunctionalInterface
public interface AdmissionMutator<T extends HasMetadata> {
    AdmissionResponse mutate(AdmissionRequest request, T resource);
}
