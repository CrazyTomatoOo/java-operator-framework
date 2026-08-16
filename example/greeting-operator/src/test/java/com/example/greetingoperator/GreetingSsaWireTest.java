/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Dependents;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.TriggerRole;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.impl.cache.CacheImpl;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Verifies the server-side-apply wire format {@code GreetingReconciler} relies on through
 * {@link Dependents#apply}: the {@code application/apply-patch+yaml} content type, the explicit
 * field manager, and the controller owner reference stamped on the desired child.
 *
 * <p>The in-memory mock rejects apply patches with HTTP 415, so the request is stubbed and only
 * the request shape asserted — merge semantics are the API server's job, exactly as the framework
 * tests document.
 */
@EnableKubernetesMockClient(crud = false)
class GreetingSsaWireTest {
    private static final String PATH = "/api/v1/namespaces/ns/configmaps/greet-1-child"
            + "?fieldManager=" + GreetingReconciler.FIELD_MANAGER;

    KubernetesClient client;

    KubernetesMockServer server;

    @Test
    void applyStampsOwnerReferenceAndUsesTheExplicitFieldManager() throws InterruptedException {
        server.expect().patch().withPath(PATH)
                .andReturn(200, child("applied"))
                .once();

        var primary = new Greeting();
        primary.setMetadata(new ObjectMetaBuilder()
                .withName("greet-1").withNamespace("ns").withUid("uid-1").build());
        var spec = new GreetingSpec();
        spec.setMessage("hello");
        primary.setSpec(spec);

        var applied = Dependents.apply(client, new GreetingConfigMap(), primary,
                context(primary), GreetingReconciler.FIELD_MANAGER);

        assertThat(applied.getData()).containsEntry("message", "applied");
        assertThat(server.getLastRequest().getHeader("Content-Type")).contains("apply-patch+yaml");
        var sent = server.getLastRequest().getBody().readUtf8();
        assertThat(sent).contains("\"controller\":true");
        assertThat(sent).contains("\"blockOwnerDeletion\":true");
        assertThat(sent).contains("\"uid\":\"uid-1\"");
        assertThat(sent).contains("\"name\":\"greet-1\"");
    }

    private static ReconciliationContext<Greeting> context(Greeting resource) {
        var primaryCache = new CacheImpl<Greeting>();
        primaryCache.put(resource);
        var configMapCache = new CacheImpl<ConfigMap>();
        configMapCache.put(child("greet-1-child"));
        var reference = ResourceReference.from(resource);
        var trigger = new ReconciliationTrigger(ResourceEventType.ADDED, TriggerRole.PRIMARY, reference);
        return new ReconciliationContext<>(reference.key(), List.of(trigger), primaryCache,
                Map.of(Greeting.class, primaryCache, ConfigMap.class, configMapCache));
    }

    private static ConfigMap child(String message) {
        return new ConfigMapBuilder()
                .withNewMetadata().withName("greet-1-child").withNamespace("ns").endMetadata()
                .addToData("message", message)
                .build();
    }
}