/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;

import org.aspectj.lang.ProceedingJoinPoint;

/**
 * Identity of one reconciler invocation, used as a rate-limit and retry key.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
record ReconcileInvocationKey(String controller, ResourceKey resourceKey) {
    static ReconcileInvocationKey from(ProceedingJoinPoint joinPoint, String controller) {
        var context = (ReconciliationContext<?>) joinPoint.getArgs()[1];
        return new ReconcileInvocationKey(controller, context.resourceKey());
    }
}
