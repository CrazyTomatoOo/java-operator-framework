/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import io.fabric8.kubernetes.api.model.KubernetesResource;

/**
 * Observed state of a {@link Greeting}, persisted through the status subresource.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public class GreetingStatus implements KubernetesResource {
    private Long observedGeneration;

    private String phase;

    private String message;

    /**
     * Returns the generation observed by the last successful reconciliation.
     *
     * @return the observed generation
     */
    public Long getObservedGeneration() {
        return observedGeneration;
    }

    /**
     * Sets the generation observed by the last successful reconciliation.
     *
     * @param observedGeneration the observed generation to set
     */
    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    /**
     * Returns the reconciliation phase, for example {@code Rendered}.
     *
     * @return the phase
     */
    public String getPhase() {
        return phase;
    }

    /**
     * Sets the reconciliation phase.
     *
     * @param phase the phase to set
     */
    public void setPhase(String phase) {
        this.phase = phase;
    }

    /**
     * Returns the message currently rendered into the child ConfigMap.
     *
     * @return the rendered message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets the message currently rendered into the child ConfigMap.
     *
     * @param message the rendered message to set
     */
    public void setMessage(String message) {
        this.message = message;
    }
}