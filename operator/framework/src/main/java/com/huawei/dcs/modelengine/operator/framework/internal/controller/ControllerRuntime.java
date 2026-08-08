/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import java.util.concurrent.CompletionStage;

/**
 * A running set of controller resources (informers, workers, queue).
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public interface ControllerRuntime {
    /**
     * Starts informers and worker threads.
     */
    void start();

    /**
     * Reports whether the runtime has started and is not stopping.
     *
     * @return {@code true} when the runtime is running
     */
    default boolean isRunning() {
        return isReady();
    }

    /**
     * Reports whether all informers are synchronized and the runtime can serve requests.
     *
     * @return {@code true} when the runtime is ready
     */
    boolean isReady();

    /**
     * Returns the number of reconciliation requests waiting in the queue.
     *
     * @return the current queue depth
     */
    default int queueDepth() {
        return 0;
    }

    /**
     * Stops the runtime and its controller resources.
     *
     * @return a stage that completes when the runtime has stopped
     */
    CompletionStage<Void> stop();
}
