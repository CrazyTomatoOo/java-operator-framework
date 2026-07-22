package com.huawei.dcs.modelengine.operator.framework.retry;

import java.time.Duration;

/** Computes retry delays for failed reconciliation attempts. */
public interface RetryPolicy {
    Duration nextDelay(int attempt);
}
