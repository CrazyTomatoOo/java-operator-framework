package com.example.echooperator.webhook;

import com.example.echooperator.api.v1alpha2.EchoResource;

import com.example.echooperator.api.v1alpha2.EchoSpec;

import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EchoValidatingWebhookTest {
    private final EchoValidatingWebhook webhook = new EchoValidatingWebhook();

    @Test
    void acceptsValidEchoResource() {
        AdmissionResponse response = webhook.validate(null, echo("hello", 1));

        assertTrue(response.getAllowed());
    }

    @Test
    void rejectsMissingMessage() {
        AdmissionResponse response = webhook.validate(null, echo("  ", 1));

        assertFalse(response.getAllowed());
        assertEquals("spec.message must not be empty", response.getStatus().getMessage());
    }

    @Test
    void rejectsMessageLongerThanOneHundredFortyCharacters() {
        AdmissionResponse response = webhook.validate(null, echo("x".repeat(141), 1));

        assertFalse(response.getAllowed());
        assertEquals("spec.message must be 140 characters or fewer", response.getStatus().getMessage());
    }

    @Test
    void rejectsNegativeReplicas() {
        AdmissionResponse response = webhook.validate(null, echo("hello", -1));

        assertFalse(response.getAllowed());
        assertEquals("spec.replicas must not be negative", response.getStatus().getMessage());
    }

    @Test
    void rejectsMissingSpec() {
        AdmissionResponse response = webhook.validate(null, new EchoResource());

        assertFalse(response.getAllowed());
        assertEquals("spec is required", response.getStatus().getMessage());
    }

    private static EchoResource echo(String message, int replicas) {
        EchoSpec spec = new EchoSpec();
        spec.message = message;
        spec.replicas = replicas;
        EchoResource resource = new EchoResource();
        resource.setSpec(spec);
        return resource;
    }
}
