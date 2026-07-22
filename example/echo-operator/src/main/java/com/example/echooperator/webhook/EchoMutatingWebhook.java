package com.example.echooperator.webhook;

import com.example.echooperator.api.v1alpha2.EchoResource;
import com.example.echooperator.api.v1alpha2.EchoSpec;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionResult;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Defaults Echo custom resources before they are persisted.
 */
public final class EchoMutatingWebhook implements AdmissionMutator<EchoResource> {
    public static final String DEFAULT_MESSAGE = "Hello, Echo!";
    public static final String MUTATED_ANNOTATION = "echo.example.com/mutated";

    @Override
    public AdmissionResponse mutate(AdmissionRequest request, EchoResource resource) {
        List<String> operations = new ArrayList<>();
        ObjectMeta metadata = resource.getMetadata();
        if (metadata == null) {
            operations.add("{\"op\":\"add\",\"path\":\"/metadata\",\"value\":{\"annotations\":{\""
                    + MUTATED_ANNOTATION + "\":\"true\"}}}");
        } else if (metadata.getAnnotations() == null || metadata.getAnnotations().isEmpty()) {
            operations.add("{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{\""
                    + MUTATED_ANNOTATION + "\":\"true\"}}");
        } else {
            operations.add("{\"op\":\"add\",\"path\":\"/metadata/annotations/echo.example.com~1mutated\","
                    + "\"value\":\"true\"}");
        }

        EchoSpec spec = resource.getSpec();
        if (spec == null) {
            operations.add("{\"op\":\"add\",\"path\":\"/spec\",\"value\":{\"message\":\"" + DEFAULT_MESSAGE
                    + "\",\"replicas\":1}}");
        } else {
            if (spec.replicas <= 0) {
                operations.add("{\"op\":\"replace\",\"path\":\"/spec/replicas\",\"value\":1}");
            }
            if (spec.message == null || spec.message.isBlank()) {
                operations.add("{\"op\":\"add\",\"path\":\"/spec/message\",\"value\":\"" + DEFAULT_MESSAGE + "\"}");
            }
        }

        return AdmissionResult.jsonPatch("[" + String.join(",", operations) + "]");
    }
}
