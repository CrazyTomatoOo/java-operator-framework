package com.huawei.dcs.modelengine.operator.framework.internal.policy;

/** Signals that the configured reconciliation attempts have been exhausted. */
public final class ReconcileTerminalException extends RuntimeException {
    public ReconcileTerminalException(String message, Throwable cause) {
        super(message, cause);
    }
}
