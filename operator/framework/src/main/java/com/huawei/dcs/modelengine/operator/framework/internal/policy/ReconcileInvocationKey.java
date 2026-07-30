package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import org.aspectj.lang.ProceedingJoinPoint;

record ReconcileInvocationKey(String controller, ResourceKey resourceKey) {
    static ReconcileInvocationKey from(ProceedingJoinPoint joinPoint, String controller) {
        var context = (ReconciliationContext<?>) joinPoint.getArgs()[1];
        return new ReconcileInvocationKey(controller, context.resourceKey());
    }
}
