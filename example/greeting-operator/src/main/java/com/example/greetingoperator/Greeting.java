/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The {@code Greeting} custom resource reconciled by this operator.
 *
 * <p>The model carries both API versions of the CRD: {@code spec.message} (v1) and
 * {@code spec.text} (v2) are two names for the same value, and the conversion webhook
 * moves data between them. Version {@code v1} is the storage version, so the controller
 * always reconciles {@code v1} objects while the API server still serves {@code v2}.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@Group("greetings.example.com")
@Version("v1")
@Kind("Greeting")
@Singular("greeting")
@Plural("greetings")
public class Greeting implements HasMetadata, Namespaced {
    private String apiVersion;

    private String kind;

    private ObjectMeta metadata;

    private GreetingSpec spec;

    private GreetingStatus status;

    /**
     * Creates a greeting resource with the group/version and kind filled in from the class
     * annotations, matching what the API server stores.
     */
    public Greeting() {
        apiVersion = HasMetadata.getApiVersion(Greeting.class);
        kind = HasMetadata.getKind(Greeting.class);
    }

    /**
     * Returns the API version of this greeting.
     *
     * @return the API version, for example {@code greetings.example.com/v1}
     */
    @Override
    public String getApiVersion() {
        return apiVersion;
    }

    /**
     * Sets the API version of this greeting.
     *
     * @param apiVersion the API version to set
     */
    @Override
    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    /**
     * Returns the kind of this resource ({@code Greeting}).
     *
     * @return the resource kind
     */
    @Override
    public String getKind() {
        return kind;
    }

    /**
     * Sets the kind of this resource.
     *
     * @param kind the resource kind to set
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * Returns the standard Kubernetes object metadata.
     *
     * @return the object metadata
     */
    @Override
    public ObjectMeta getMetadata() {
        return metadata;
    }

    /**
     * Sets the standard Kubernetes object metadata.
     *
     * @param metadata the object metadata to set
     */
    @Override
    public void setMetadata(ObjectMeta metadata) {
        this.metadata = metadata;
    }

    /**
     * Returns the desired state.
     *
     * @return the spec, or {@code null} before deserialization
     */
    public GreetingSpec getSpec() {
        return spec;
    }

    /**
     * Sets the desired state.
     *
     * @param spec the spec to set
     */
    public void setSpec(GreetingSpec spec) {
        this.spec = spec;
    }

    /**
     * Returns the observed state written through the status subresource.
     *
     * @return the status, or {@code null} before the first reconciliation
     */
    public GreetingStatus getStatus() {
        return status;
    }

    /**
     * Sets the observed state.
     *
     * @param status the status to set
     */
    public void setStatus(GreetingStatus status) {
        this.status = status;
    }
}