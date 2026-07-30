package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import io.fabric8.kubernetes.api.model.HasMetadata;

/** Mutates an admission request without depending on a transport protocol. */
@FunctionalInterface
public interface AdmissionMutator<T extends HasMetadata> {
    MutationResult<T> mutate(T current, AdmissionContext context) throws Exception;
}
