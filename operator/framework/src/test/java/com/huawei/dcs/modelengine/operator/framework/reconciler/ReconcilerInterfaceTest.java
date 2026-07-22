package com.huawei.dcs.modelengine.operator.framework.reconciler;

import io.fabric8.kubernetes.api.model.ConfigMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReconcilerInterfaceTest {
    @Test
    void shouldAllowUserImplementationForFabric8Resources() {
        Reconciler<ConfigMap> reconciler = new DummyReconciler();

        Result result = reconciler.reconcile(new Request("demo", "sample"), new ConfigMap());

        assertFalse(result.requeue());
        assertNull(result.requeueAfter());
        assertNull(result.error());
    }

    private static final class DummyReconciler implements Reconciler<ConfigMap> {
        @Override
        public Result reconcile(Request request, ConfigMap resource) {
            return Result.done();
        }
    }
}
