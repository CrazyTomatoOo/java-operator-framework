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
    void start();

    boolean isReady();

    default boolean isRunning() {
        return isReady();
    }

    default int queueDepth() {
        return 0;
    }

    CompletionStage<Void> stop();
}
