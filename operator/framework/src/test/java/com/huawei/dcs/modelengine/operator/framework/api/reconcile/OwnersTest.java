/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnersTest {
    @Test
    void setsControllerReferenceWithOwnerCoordinates() {
        var owner = owner("primary", "uid-1");
        var dependent = dependent("ns");

        Owners.setController(owner, dependent);

        assertThat(dependent.getMetadata().getOwnerReferences()).singleElement().satisfies(ref -> {
            assertThat(ref.getController()).isTrue();
            assertThat(ref.getBlockOwnerDeletion()).isTrue();
            assertThat(ref.getApiVersion()).isEqualTo(owner.getApiVersion());
            assertThat(ref.getKind()).isEqualTo(owner.getKind());
            assertThat(ref.getName()).isEqualTo("primary");
            assertThat(ref.getUid()).isEqualTo("uid-1");
        });
    }

    @Test
    void refreshSameOwnerIsIdempotent() {
        var owner = owner("primary", "uid-1");
        var dependent = dependent("ns");
        Owners.setController(owner, dependent);

        Owners.setController(owner, dependent);

        assertThat(dependent.getMetadata().getOwnerReferences()).hasSize(1);
    }

    @Test
    void keepsNonControllerReferences() {
        var owner = owner("primary", "uid-1");
        var dependent = dependent("ns");
        dependent.getMetadata().setOwnerReferences(java.util.List.of(new OwnerReferenceBuilder()
                .withApiVersion("v1").withKind("ConfigMap").withName("other").withUid("uid-9")
                .withController(false)
                .build()));

        Owners.setController(owner, dependent);

        assertThat(dependent.getMetadata().getOwnerReferences()).hasSize(2);
    }

    @Test
    void rejectsForeignController() {
        var dependent = dependent("ns");
        Owners.setController(owner("first", "uid-1"), dependent);

        assertThrows(IllegalStateException.class,
                () -> Owners.setController(owner("second", "uid-2"), dependent));
    }

    @Test
    void rejectsNamespaceMismatch() {
        var dependent = dependent("other-ns");

        assertThrows(IllegalArgumentException.class,
                () -> Owners.setController(owner("primary", "uid-1"), dependent));
    }

    @Test
    void clusterScopedOwnerMayOwnAnyNamespace() {
        var owner = owner("primary", "uid-1");
        owner.getMetadata().setNamespace(null);
        var dependent = dependent("other-ns");

        Owners.setController(owner, dependent);

        assertThat(dependent.getMetadata().getOwnerReferences()).hasSize(1);
    }

    @Test
    void rejectsOwnerWithoutUid() {
        var owner = owner("primary", "uid-1");
        owner.getMetadata().setUid(null);

        assertThrows(IllegalArgumentException.class,
                () -> Owners.setController(owner, dependent("ns")));
    }

    private static io.fabric8.kubernetes.api.model.ConfigMap owner(String name, String uid) {
        return new ConfigMapBuilder()
                .withNewMetadata().withNamespace("ns").withName(name).withUid(uid).endMetadata()
                .build();
    }

    private static io.fabric8.kubernetes.api.model.ConfigMap dependent(String namespace) {
        return new ConfigMapBuilder()
                .withNewMetadata().withNamespace(namespace).withName("child").endMetadata()
                .build();
    }
}
