package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import java.util.concurrent.CompletionStage;

interface ControllerRuntime {
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
