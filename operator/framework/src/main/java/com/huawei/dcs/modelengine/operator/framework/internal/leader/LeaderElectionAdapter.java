/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.leader;

import java.util.concurrent.CompletionStage;

/**
 * Leader election abstraction driven by start and stop leading callbacks.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public interface LeaderElectionAdapter {
    /**
     * Starts leader election with callbacks for gaining and losing leadership.
     *
     * @param onStartLeading callback invoked when leadership is acquired
     * @param onStopLeading callback invoked when leadership is lost
     * @return a stage that completes when leader election has started
     */
    CompletionStage<Void> start(Runnable onStartLeading, Runnable onStopLeading);

    /**
     * Stops leader election and releases any leadership lease.
     */
    void stop();
}
