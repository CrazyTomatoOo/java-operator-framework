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

// ponytail: SSA stub like AppliesTest — assert the request this helper controls; merge semantics
// are the apiserver's job.
@EnableKubernetesMockClient(crud = false)
class DependentsTest {
    private static final String PATH = "/api/v1/namespaces/ns/configmaps/child?fieldManager=test-operator";

    KubernetesClient client;
    KubernetesMockServer server;

    @Test
    void applySetsOwnerReferenceAndAppliesDesiredState() throws InterruptedException {
        server.expect().patch().withPath(PATH)
                .andReturn(200, configMap("applied")).once();
        var primary = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("primary").withUid("uid-1").endMetadata()
                .build();

        var applied = Dependents.apply(client, new EchoConfigMap(), primary, context(), "test-operator");

        assertThat(applied.getData()).containsEntry("k", "applied");
        var sent = server.getLastRequest().getBody().readUtf8();
        assertThat(sent).contains("\"controller\":true");
        assertThat(sent).contains("\"blockOwnerDeletion\":true");
        assertThat(sent).contains("\"uid\":\"uid-1\"");
        assertThat(sent).contains("\"name\":\"primary\"");
    }

    @Test
    void rejectsNullDesired() {
        var primary = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("primary").withUid("uid-1").endMetadata()
                .build();
        DependentResource<ConfigMap, ConfigMap> empty = new DependentResource<>() {
            @Override
            public Class<ConfigMap> resourceType() {
                return ConfigMap.class;
            }

            @Override
            public ConfigMap desired(ConfigMap primary, ReconciliationContext<ConfigMap> context) {
                return null;
            }
        };

        assertThrows(NullPointerException.class,
                () -> Dependents.apply(client, empty, primary, context(), "fm"));
    }

    private static ReconciliationContext<ConfigMap> context() {
        return ReconciliationContext.withoutCache(new ResourceKey("ns", "primary"), java.util.List.of());
    }


    private static ConfigMap configMap(String value) {
        return new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("child").endMetadata()
                .addToData("k", value)
                .build();
    }

    /** Echoes the primary's data into an owned ConfigMap named after the primary's "child" key. */
    private static final class EchoConfigMap implements DependentResource<ConfigMap, ConfigMap> {
        @Override
        public Class<ConfigMap> resourceType() {
            return ConfigMap.class;
        }

        @Override
        public ConfigMap desired(ConfigMap primary, ReconciliationContext<ConfigMap> context) {
            return configMap("from-" + primary.getMetadata().getName());
        }
    }
}
