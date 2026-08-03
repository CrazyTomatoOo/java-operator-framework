/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

// ponytail: fabric8 mock CRUD mode rejects apply-patch+yaml (415), so stub the PATCH and assert
// the request this helper controls (content type, fieldManager, force); merge semantics are the
// apiserver's job.
@EnableKubernetesMockClient(crud = false)
class AppliesTest {
    KubernetesClient client;
    KubernetesMockServer server;

    @Test
    void applySendsApplyPatchWithExplicitFieldManager() throws InterruptedException {
        server.expect().patch().withPath("/api/v1/namespaces/ns/configmaps/cm?fieldManager=test-operator")
                .andReturn(200, desired("v1")).once();

        var applied = Applies.apply(client, desired("v1"), "test-operator");

        assertThat(applied.getData()).containsEntry("k", "v1");
        assertThat(server.getLastRequest().getHeader("Content-Type")).contains("apply-patch+yaml");
    }

    @Test
    void applyForciblyAddsForceParameter() {
        server.expect().patch()
                .withPath("/api/v1/namespaces/ns/configmaps/cm?fieldManager=test-operator&force=true")
                .andReturn(200, desired("v1")).once();

        var applied = Applies.applyForcibly(client, desired("v1"), "test-operator");

        assertThat(applied.getData()).containsEntry("k", "v1");
    }

    @Test
    void rejectsInvalidArguments() {
        var cm = desired("v1");
        assertThrows(NullPointerException.class, () -> Applies.apply(client, null, "fm"));
        assertThrows(IllegalArgumentException.class, () -> Applies.apply(client, cm, null));
        assertThrows(IllegalArgumentException.class, () -> Applies.apply(client, cm, " "));
    }

    private static ConfigMap desired(String value) {
        return new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("cm").endMetadata()
                .addToData("k", value)
                .build();
    }
}
