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
    CompletionStage<Void> start(Runnable onStartLeading, Runnable onStopLeading);

    void stop();
}
