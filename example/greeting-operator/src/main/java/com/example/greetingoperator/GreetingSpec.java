/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import io.fabric8.kubernetes.api.model.KubernetesResource;

/**
 * Desired state of a {@link Greeting}.
 *
 * <p>{@code message} is the v1 field name and {@code text} the v2 name for the same value;
 * both live on one model so the conversion webhook can move data between the versions.
 * {@code style} optionally names a styles {@code ConfigMap} that decorates the rendered
 * message.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public class GreetingSpec implements KubernetesResource {
    private String message;

    private String text;

    private String style;

    /**
     * Returns the message text (v1 field name).
     *
     * @return the message, or {@code null} when the object was written through API version v2
     */
    public String getMessage() {
        return message;
    }

    /**
     * Sets the message text (v1 field name).
     *
     * @param message the message to set
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * Returns the message text (v2 field name).
     *
     * @return the message, or {@code null} when the object was written through API version v1
     */
    public String getText() {
        return text;
    }

    /**
     * Sets the message text (v2 field name).
     *
     * @param text the message to set
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * Returns the name of the styles ConfigMap decorating the rendered message.
     *
     * @return the style name, or {@code null} for no decoration
     */
    public String getStyle() {
        return style;
    }

    /**
     * Sets the name of the styles ConfigMap decorating the rendered message.
     *
     * @param style the style name to set, or {@code null} for no decoration
     */
    public void setStyle(String style) {
        this.style = style;
    }
}