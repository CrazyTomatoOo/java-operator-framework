package com.huawei.dcs.modelengine.operator.framework.webhook.admission;

import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponseBuilder;

/**
 * Convenience factories for admission responses returned by validators and mutators.
 */
public final class AdmissionResult {
    private AdmissionResult() {
    }

    public static AdmissionResponse allowed() {
        return new AdmissionResponseBuilder().withAllowed(true).build();
    }

    public static AdmissionResponse denied(String message) {
        return new AdmissionResponseBuilder()
                .withAllowed(false)
                .withStatus(new StatusBuilder().withMessage(message).build())
                .build();
    }

    public static AdmissionResponse jsonPatch(String patch) {
        return new AdmissionResponseBuilder()
                .withAllowed(true)
                .withPatch(patch)
                .build();
    }
}
