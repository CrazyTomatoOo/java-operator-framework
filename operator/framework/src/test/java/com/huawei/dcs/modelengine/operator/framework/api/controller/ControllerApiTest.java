/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerApiTest {
    @Test
    void buildsImmutableControllerRegistration() {
        var builder = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> ReconcileResult.done())
                .generationFilter(true)
                .resyncPeriod(Duration.ZERO)
                .owns(Secret.class)
                .watches("secrets", Secret.class, Mappers.byLabel("primary"))
                .watchesKubernetesEvents();

        var registration = builder.build();
        builder.owns(ConfigMap.class);

        assertEquals(ConfigMap.class, registration.resourceType());
        assertEquals(Boolean.TRUE, registration.generationFilter().orElseThrow());
        assertEquals(Duration.ZERO, registration.resyncPeriod().orElseThrow());
        assertEquals(List.of(Secret.class), registration.ownedResources());
        assertEquals("secrets", registration.secondaryWatches().getFirst().name());
        assertTrue(registration.watchesKubernetesEvents());
        assertThrows(UnsupportedOperationException.class, () -> registration.ownedResources().clear());
    }

    @Test
    void preservesOptionalOverridesAndRejectsInvalidConfiguration() {
        var registration = ControllerBuilder
                .forResource(ConfigMap.class, (resource, context) -> ReconcileResult.done())
                .build();

        assertFalse(registration.generationFilter().isPresent());
        assertFalse(registration.resyncPeriod().isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> ReconcileResult.done())
                        .resyncPeriod(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> ReconcileResult.done())
                        .watches(" ", Secret.class, Mappers.byLabel("primary")));
    }

    @Test
    void mapsNativeKubernetesRelationships() {
        var owner = new OwnerReferenceBuilder().withName("owner").withController(true).build();
        var secondary = new ConfigMapBuilder()
                .withNewMetadata()
                .withNamespace("operators")
                .withName("secondary")
                .withOwnerReferences(owner)
                .addToLabels("primary", "label-owner")
                .addToAnnotations("operator.example/primary", "annotation-owner")
                .endMetadata()
                .build();
        var previous = new ConfigMapBuilder(secondary)
                .editMetadata()
                .addToLabels("primary", "previous-owner")
                .endMetadata()
                .build();

        ResourceMapper<ConfigMap, ConfigMap> owners = Mappers.ownerReferences();
        ResourceMapper<ConfigMap, ConfigMap> labels = Mappers.byLabel("primary");
        ResourceMapper<ConfigMap, ConfigMap> annotations = Mappers.byAnnotation("operator.example/primary");

        assertEquals(List.of(new ResourceKey("operators", "owner")), owners.map(ResourceEvent.added(secondary)));
        assertEquals(List.of(new ResourceKey("operators", "label-owner")), labels.map(ResourceEvent.added(secondary)));
        assertEquals(
                List.of(new ResourceKey("operators", "label-owner"), new ResourceKey("operators", "previous-owner")),
                labels.map(ResourceEvent.updated(previous, secondary)));
        assertEquals(List.of(new ResourceKey("operators", "annotation-owner")),
                annotations.map(ResourceEvent.added(secondary)));
    }

    @Test
    void ownerReferenceMapperFiltersByPrimaryGvkAndController() {
        var matching = new OwnerReferenceBuilder().withApiVersion("v1").withKind("ConfigMap")
                .withName("matching").withController(true).build();
        var wrongVersion = new OwnerReferenceBuilder(matching).withApiVersion("v2").withName("wrong-version").build();
        var wrongKind = new OwnerReferenceBuilder(matching).withKind("Secret").withName("wrong-kind").build();
        var notController = new OwnerReferenceBuilder(matching).withController(false)
                .withName("not-controller").build();
        var secondary = new ConfigMapBuilder().withNewMetadata().withNamespace("operators")
                .withOwnerReferences(matching, wrongVersion, wrongKind, notController).endMetadata().build();

        assertEquals(List.of(new ResourceKey("operators", "matching")),
                Mappers.<ConfigMap, ConfigMap>ownerReferences(ConfigMap.class)
                        .map(ResourceEvent.added(secondary)));
    }

    @Test
    void involvedObjectMapperFiltersByPrimaryGvk() {
        var matching = new EventBuilder().withNewInvolvedObject().withApiVersion("v1").withKind("ConfigMap")
                .withNamespace("operators").withName("matching").endInvolvedObject().build();
        var wrongVersion = new EventBuilder().withNewInvolvedObject().withApiVersion("v2").withKind("ConfigMap")
                .withNamespace("operators").withName("wrong-version").endInvolvedObject().build();
        var wrongKind = new EventBuilder().withNewInvolvedObject().withApiVersion("v1").withKind("Secret")
                .withNamespace("operators").withName("wrong-kind").endInvolvedObject().build();

        assertEquals(List.of(new ResourceKey("operators", "matching")),
                Mappers.<ConfigMap>involvedObject(ConfigMap.class).map(ResourceEvent.added(matching)));
        assertEquals(List.of(),
                Mappers.<ConfigMap>involvedObject(ConfigMap.class).map(ResourceEvent.added(wrongVersion)));
        assertEquals(List.of(),
                Mappers.<ConfigMap>involvedObject(ConfigMap.class).map(ResourceEvent.added(wrongKind)));
    }

    @Test
    void mapsKubernetesEventInvolvedObject() {
        var event = new EventBuilder()
                .withNewMetadata().withName("event").endMetadata()
                .withNewInvolvedObject()
                .withNamespace("operators")
                .withName("primary")
                .endInvolvedObject()
                .build();

        assertEquals(List.of(new ResourceKey("operators", "primary")),
                Mappers.<ConfigMap>involvedObject().map(ResourceEvent.added(event)));
    }

    @Test
    void watchSelectorStoresLabelAndFieldSelectorsIndependently() {
        var registration = ControllerBuilder
                .forResource(ConfigMap.class, (resource, context) -> ReconcileResult.done())
                .labelSelector(Map.of("app", "my-op"))
                .fieldSelector(Map.of("metadata.namespace", "operators"))
                .build();

        var selector = registration.watchSelector().orElseThrow();
        assertEquals(Map.of("app", "my-op"), selector.labels());
        assertEquals(Map.of("metadata.namespace", "operators"), selector.fields());
        assertThrows(UnsupportedOperationException.class, () -> selector.labels().put("x", "y"));
    }

    @Test
    void watchSelectorIsAbsentWhenNeitherConfigured() {
        var registration = ControllerBuilder
                .forResource(ConfigMap.class, (resource, context) -> ReconcileResult.done())
                .build();
        assertFalse(registration.watchSelector().isPresent());
    }
}
