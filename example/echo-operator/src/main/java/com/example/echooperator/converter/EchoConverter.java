package com.example.echooperator.converter;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

/**
 * Converts Echo custom resources between served API versions.
 */
public final class EchoConverter {
    private static final String KIND = "EchoResource";
    private static final String V1_API_VERSION = "example.com/v1alpha1";
    private static final String V2_API_VERSION = "example.com/v1alpha2";
    private static final String DEFAULT_LOG_LEVEL = "INFO";

    public com.example.echooperator.api.v1alpha2.EchoResource toV2(com.example.echooperator.api.v1alpha1.EchoResource source) {
        com.example.echooperator.api.v1alpha2.EchoResource target = new com.example.echooperator.api.v1alpha2.EchoResource();
        target.setApiVersion(V2_API_VERSION);
        target.setKind(KIND);
        target.setMetadata(copyMetadata(source.getMetadata()));
        target.setSpec(toV2Spec(source.getSpec()));
        target.setStatus(toV2Status(source.getStatus()));
        return target;
    }

    public com.example.echooperator.api.v1alpha1.EchoResource toV1(com.example.echooperator.api.v1alpha2.EchoResource source) {
        com.example.echooperator.api.v1alpha1.EchoResource target = new com.example.echooperator.api.v1alpha1.EchoResource();
        target.setApiVersion(V1_API_VERSION);
        target.setKind(KIND);
        target.setMetadata(copyMetadata(source.getMetadata()));
        target.setSpec(toV1Spec(source.getSpec()));
        target.setStatus(toV1Status(source.getStatus()));
        return target;
    }

    private static com.example.echooperator.api.v1alpha2.EchoSpec toV2Spec(com.example.echooperator.api.v1alpha1.EchoSpec source) {
        if (source == null) {
            return null;
        }
        com.example.echooperator.api.v1alpha2.EchoSpec target = new com.example.echooperator.api.v1alpha2.EchoSpec();
        target.message = source.message;
        target.replicas = source.replicas;
        target.logLevel = DEFAULT_LOG_LEVEL;
        return target;
    }

    private static com.example.echooperator.api.v1alpha1.EchoSpec toV1Spec(com.example.echooperator.api.v1alpha2.EchoSpec source) {
        if (source == null) {
            return null;
        }
        com.example.echooperator.api.v1alpha1.EchoSpec target = new com.example.echooperator.api.v1alpha1.EchoSpec();
        target.message = source.message;
        target.replicas = source.replicas;
        return target;
    }

    private static com.example.echooperator.api.v1alpha2.EchoStatus toV2Status(com.example.echooperator.api.v1alpha1.EchoStatus source) {
        if (source == null) {
            return null;
        }
        com.example.echooperator.api.v1alpha2.EchoStatus target = new com.example.echooperator.api.v1alpha2.EchoStatus();
        target.phase = source.phase;
        target.message = source.message;
        return target;
    }

    private static com.example.echooperator.api.v1alpha1.EchoStatus toV1Status(com.example.echooperator.api.v1alpha2.EchoStatus source) {
        if (source == null) {
            return null;
        }
        com.example.echooperator.api.v1alpha1.EchoStatus target = new com.example.echooperator.api.v1alpha1.EchoStatus();
        target.phase = source.phase;
        target.message = source.message;
        return target;
    }

    private static ObjectMeta copyMetadata(ObjectMeta source) {
        if (source == null) {
            return null;
        }
        return new ObjectMetaBuilder(source).build();
    }
}
