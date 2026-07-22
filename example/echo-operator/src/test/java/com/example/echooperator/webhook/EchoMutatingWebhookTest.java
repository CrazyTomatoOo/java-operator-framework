package com.example.echooperator.webhook;

import com.example.echooperator.api.v1alpha2.EchoResource;

import com.example.echooperator.api.v1alpha2.EchoSpec;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoMutatingWebhookTest {
    private final EchoMutatingWebhook webhook = new EchoMutatingWebhook();

    @Test
    void injectsDefaultAnnotationAndReplicaCount() {
        EchoResource resource = echo("hello", 0);

        AdmissionResponse response = webhook.mutate(null, resource);

        assertTrue(response.getAllowed());
        assertEquals("[{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{\"echo.example.com/mutated\":\"true\"}},"
                + "{\"op\":\"replace\",\"path\":\"/spec/replicas\",\"value\":1}]", response.getPatch());
    }

    @Test
    void defaultsBlankMessageBeforeValidationSeesTheResource() {
        EchoResource resource = echo("  ", 1);

        AdmissionResponse response = webhook.mutate(null, resource);

        assertEquals("[{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{\"echo.example.com/mutated\":\"true\"}},"
                + "{\"op\":\"add\",\"path\":\"/spec/message\",\"value\":\"Hello, Echo!\"}]", response.getPatch());
    }

    @Test
    void preservesPositiveReplicasWhileAddingAnnotation() {
        EchoResource resource = echo("hello", 3);
        resource.getMetadata().setAnnotations(Map.of("existing", "annotation"));

        AdmissionResponse response = webhook.mutate(null, resource);

        assertEquals("[{\"op\":\"add\",\"path\":\"/metadata/annotations/echo.example.com~1mutated\","
                + "\"value\":\"true\"}]", response.getPatch());
    }

    @Test
    void createsSpecWhenMissing() {
        EchoResource resource = new EchoResource();
        resource.setMetadata(new ObjectMetaBuilder().withName("echo").build());

        AdmissionResponse response = webhook.mutate(null, resource);

        assertEquals("[{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{\"echo.example.com/mutated\":\"true\"}},"
                + "{\"op\":\"add\",\"path\":\"/spec\",\"value\":{\"message\":\"Hello, Echo!\",\"replicas\":1}}]", response.getPatch());
    }

    private static EchoResource echo(String message, int replicas) {
        EchoSpec spec = new EchoSpec();
        spec.message = message;
        spec.replicas = replicas;
        EchoResource resource = new EchoResource();
        resource.setMetadata(new ObjectMetaBuilder().withName("echo").build());
        resource.setSpec(spec);
        return resource;
    }
}
