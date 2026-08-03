/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import java.util.Optional;

/**
 * Allow or deny decision returned by an admission validator.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public final class AdmissionDecision {
    private static final AdmissionDecision ALLOWED = new AdmissionDecision(true, null);

    private final boolean allowed;
    private final String message;

    private AdmissionDecision(boolean allowed, String message) {
        this.allowed = allowed;
        this.message = message;
    }

    /**
     * Creates a decision that admits the request.
     *
     * @return an allow decision
     */
    public static AdmissionDecision allow() {
        return ALLOWED;
    }

    /**
     * Creates a decision that rejects the request.
     *
     * @param message the reason for denying the request
     * @return a deny decision
     * @throws IllegalArgumentException if the message is blank
     */
    public static AdmissionDecision deny(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new AdmissionDecision(false, message);
    }

    /**
     * Checks whether the request is admitted.
     *
     * @return {@code true} when the request is allowed
     */
    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Returns the denial reason, if any.
     *
     * @return the message, or empty when the request is allowed
     */
    public Optional<String> message() {
        return Optional.ofNullable(message);
    }
}
