package com.huawei.dcs.modelengine.operator.framework.internal.leader;

import java.util.concurrent.CompletionStage;

public interface LeaderElectionAdapter {
    CompletionStage<Void> start(Runnable onStartLeading, Runnable onStopLeading);

    void stop();
}
