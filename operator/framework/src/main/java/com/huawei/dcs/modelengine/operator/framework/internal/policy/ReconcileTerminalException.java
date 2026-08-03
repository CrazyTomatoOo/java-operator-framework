/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.policy;

/**
 * Signals that the configured reconciliation attempts have been exhausted.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public final class ReconcileTerminalException extends RuntimeException {
    /**
     * Creates the terminal exception with a failure message and the underlying cause.
     *
     * @param message the failure description, including the number of exhausted attempts
     * @param cause the last reconciler exception before the attempts ran out
     */
    public ReconcileTerminalException(String message, Throwable cause) {
        super(message, cause);
    }
}
