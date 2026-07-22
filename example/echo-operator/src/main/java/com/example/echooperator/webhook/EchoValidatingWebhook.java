package com.example.echooperator.webhook;

import com.example.echooperator.api.v1alpha2.EchoResource;
import com.example.echooperator.api.v1alpha2.EchoSpec;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionValidator;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

/**
 * Validates Echo custom resources before they are persisted.
 */
public final class EchoValidatingWebhook implements AdmissionValidator<EchoResource> {
    private static final int MAX_MESSAGE_LENGTH = 140;

    @Override
    public AdmissionResponse validate(AdmissionRequest request, EchoResource resource) {
        EchoSpec spec = resource.getSpec();
        if (spec == null) {
            return AdmissionResult.denied("spec is required");
        }
        if (spec.message == null || spec.message.isBlank()) {
            return AdmissionResult.denied("spec.message must not be empty");
        }
        if (spec.message.length() > MAX_MESSAGE_LENGTH) {
            return AdmissionResult.denied("spec.message must be 140 characters or fewer");
        }
        if (spec.replicas < 0) {
            return AdmissionResult.denied("spec.replicas must not be negative");
        }
        return AdmissionResult.allowed();
    }
}
