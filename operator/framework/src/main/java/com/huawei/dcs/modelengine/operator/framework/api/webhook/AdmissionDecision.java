package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import java.util.Optional;

/** Allow or deny decision returned by an admission validator. */
public final class AdmissionDecision {
    private static final AdmissionDecision ALLOWED = new AdmissionDecision(true, null);

    private final boolean allowed;
    private final String message;

    private AdmissionDecision(boolean allowed, String message) {
        this.allowed = allowed;
        this.message = message;
    }

    public static AdmissionDecision allow() {
        return ALLOWED;
    }

    public static AdmissionDecision deny(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new AdmissionDecision(false, message);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public Optional<String> message() {
        return Optional.ofNullable(message);
    }
}
