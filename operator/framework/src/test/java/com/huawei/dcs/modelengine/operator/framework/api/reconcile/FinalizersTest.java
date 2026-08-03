/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@EnableKubernetesMockClient(crud = true)
class FinalizersTest {
    KubernetesClient client;

    @Test
    void presentAndIsDeletingReadMetadataWithoutServer() {
        var plain = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("plain").endMetadata().build();
        var withFinalizer = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("f").addToFinalizers("example.com/cleanup").endMetadata()
                .build();
        var deleting = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName("d")
                .withDeletionTimestamp("2024-01-01T00:00:00Z").endMetadata().build();

        assertThat(Finalizers.present(plain, "example.com/cleanup")).isFalse();
        assertThat(Finalizers.present(withFinalizer, "example.com/cleanup")).isTrue();
        assertThat(Finalizers.isDeleting(plain)).isFalse();
        assertThat(Finalizers.isDeleting(deleting)).isTrue();
    }

    @Test
    void addPatchesFinalizerWhenAbsentAndIsIdempotent() {
        var resource = create("idempotent");
        var finalizer = "example.com/cleanup";

        var patched = Finalizers.add(client, resource, finalizer);
        assertThat(Finalizers.present(patched, finalizer)).isTrue();
        assertThat(serverFinalizers("idempotent")).contains(finalizer);

        // adding again must not duplicate the entry
        Finalizers.add(client, resource, finalizer);
        assertThat(serverFinalizers("idempotent")).hasSize(1);
    }

    @Test
    void removePatchesFinalizerWhenPresentAndIsIdempotentWhenAbsent() {
        var resource = create("removable");
        Finalizers.add(client, resource, "example.com/cleanup");
        Finalizers.add(client, resource, "example.com/other");

        var patched = Finalizers.remove(client, resource, "example.com/cleanup");
        assertThat(Finalizers.present(patched, "example.com/cleanup")).isFalse();
        assertThat(serverFinalizers("removable")).containsExactly("example.com/other");

        // removing a missing finalizer is a no-op, never throws
        assertThat(Finalizers.remove(client, resource, "absent")).isSameAs(resource);
    }

    @Test
    void rejectsBlankFinalizerName() {
        var resource = create("rejected");
        assertThatIllegalArgumentException().isThrownBy(() -> Finalizers.add(client, resource, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> Finalizers.remove(client, resource, ""));
    }

    private ConfigMap create(String name) {
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName(name).endMetadata().build();
        return client.resource(resource).create();
    }

    private java.util.List<String> serverFinalizers(String name) {
        var fetched = client.configMaps().inNamespace("ns").withName(name).get();
        assertThat(fetched).as("resource must exist").isNotNull();
        var finalizers = fetched.getMetadata().getFinalizers();
        return finalizers == null ? java.util.List.of() : finalizers;
    }
}
