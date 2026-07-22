package com.example.echooperator.converter;

import com.example.echooperator.api.v1alpha1.EchoResource;
import com.example.echooperator.api.v1alpha1.EchoSpec;
import com.example.echooperator.api.v1alpha1.EchoStatus;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class EchoConverterTest {

    @Test
    void convertsV1ToV2WithDefaults() {
        EchoResource source = v1Resource();

        com.example.echooperator.api.v1alpha2.EchoResource converted = new EchoConverter().toV2(source);

        assertEquals("example.com/v1alpha2", converted.getApiVersion());
        assertEquals("EchoResource", converted.getKind());
        assertEquals("echo-sample", converted.getMetadata().getName());
        assertEquals("default", converted.getMetadata().getNamespace());
        assertEquals("true", converted.getMetadata().getAnnotations().get("echo.example.com/defaulted"));
        assertEquals("echo", converted.getMetadata().getLabels().get("app"));
        assertEquals("echo-uid", converted.getMetadata().getUid());
        assertEquals("12345", converted.getMetadata().getResourceVersion());
        assertEquals("cleanup.example.com", converted.getMetadata().getFinalizers().get(0));
        assertEquals("hello", converted.getSpec().message);
        assertEquals(2, converted.getSpec().replicas);
        assertEquals("INFO", converted.getSpec().logLevel);
        assertEquals("READY", converted.getStatus().phase);
        assertEquals("hello", converted.getStatus().message);
        assertNotSame(source.getMetadata().getAnnotations(), converted.getMetadata().getAnnotations());
        assertNotSame(source.getMetadata().getLabels(), converted.getMetadata().getLabels());
    }

    @Test
    void roundTripPreservesV1Fields() {
        EchoResource source = v1Resource();
        EchoConverter converter = new EchoConverter();

        EchoResource roundTripped = converter.toV1(converter.toV2(source));

        assertEquals("example.com/v1alpha1", roundTripped.getApiVersion());
        assertEquals("EchoResource", roundTripped.getKind());
        assertEquals("echo-sample", roundTripped.getMetadata().getName());
        assertEquals("default", roundTripped.getMetadata().getNamespace());
        assertEquals("true", roundTripped.getMetadata().getAnnotations().get("echo.example.com/defaulted"));
        assertEquals("echo", roundTripped.getMetadata().getLabels().get("app"));
        assertEquals("hello", roundTripped.getSpec().message);
        assertEquals(2, roundTripped.getSpec().replicas);
        assertEquals("READY", roundTripped.getStatus().phase);
        assertEquals("hello", roundTripped.getStatus().message);
    }

    @Test
    void convertsV2ToV1IgnoringV2OnlyFields() {
        com.example.echooperator.api.v1alpha2.EchoResource source = new com.example.echooperator.api.v1alpha2.EchoResource();
        source.setMetadata(new ObjectMetaBuilder()
                .withName("echo-sample")
                .withNamespace("default")
                .withAnnotations(Map.of("echo.example.com/defaulted", "true"))
                .withLabels(Map.of("app", "echo"))
                .withUid("echo-uid")
                .withResourceVersion("12345")
                .withFinalizers("cleanup.example.com")
                .build());
        com.example.echooperator.api.v1alpha2.EchoSpec spec = new com.example.echooperator.api.v1alpha2.EchoSpec();
        spec.message = "hello";
        spec.replicas = 2;
        spec.logLevel = "DEBUG";
        source.setSpec(spec);
        com.example.echooperator.api.v1alpha2.EchoStatus status = new com.example.echooperator.api.v1alpha2.EchoStatus();
        status.phase = "READY";
        status.message = "hello";
        source.setStatus(status);

        EchoResource converted = new EchoConverter().toV1(source);

        assertEquals("example.com/v1alpha1", converted.getApiVersion());
        assertEquals("hello", converted.getSpec().message);
        assertEquals(2, converted.getSpec().replicas);
        assertEquals("READY", converted.getStatus().phase);
        assertEquals("hello", converted.getStatus().message);
    }

    private static EchoResource v1Resource() {
        EchoSpec spec = new EchoSpec();
        spec.message = "hello";
        spec.replicas = 2;

        EchoStatus status = new EchoStatus();
        status.phase = "READY";
        status.message = "hello";

        EchoResource resource = new EchoResource();
        resource.setApiVersion("example.com/v1alpha1");
        resource.setKind("EchoResource");
        resource.setMetadata(new ObjectMetaBuilder()
                .withName("echo-sample")
                .withNamespace("default")
                .withAnnotations(Map.of("echo.example.com/defaulted", "true"))
                .withLabels(Map.of("app", "echo"))
                .withUid("echo-uid")
                .withResourceVersion("12345")
                .withFinalizers("cleanup.example.com")
                .build());
        resource.setSpec(spec);
        resource.setStatus(status);
        return resource;
    }
}
