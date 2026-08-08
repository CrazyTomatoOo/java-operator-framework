/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class WebhookApiTest {
    @Test
    void createsAdmissionDecisions() {
        assertTrue(AdmissionDecision.allow().isAllowed());
        assertTrue(AdmissionDecision.allow().message().isEmpty());
        assertFalse(AdmissionDecision.deny("invalid spec").isAllowed());
        assertEquals("invalid spec", AdmissionDecision.deny("invalid spec").message().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> AdmissionDecision.deny(" "));
    }

    @Test
    void createsMutationResults() {
        var resource = new ConfigMapBuilder().withNewMetadata().withName("sample").endMetadata().build();

        assertEquals(MutationResult.Status.UNCHANGED, MutationResult.unchanged().status());
        assertEquals(resource, MutationResult.mutated(resource).resource().orElseThrow());
        assertEquals(MutationResult.Status.DENIED, MutationResult.denied("not allowed").status());
        assertThrows(NullPointerException.class, () -> MutationResult.mutated(null));
        assertThrows(IllegalArgumentException.class, () -> MutationResult.denied(""));
    }

    @Test
    void contextsAreStableAndImmutable() {
        var reference = new ResourceReference("v1", "ConfigMap", "default", "sample", null);
        var groups = new java.util.ArrayList<>(List.of("developers"));
        var identity = new AdmissionContext.UserIdentity("alice", "user-1", groups, Map.of("scopes", List.of("write")));
        var context = new AdmissionContext("request-1", "UPDATE", reference, true,
            Map.<String, Object>of("propagationPolicy", "Foreground"), identity);

        groups.add("admins");
        assertEquals("request-1", context.uid());
        assertEquals(List.of("developers"), context.user().groups());
        assertThrows(UnsupportedOperationException.class, () -> context.user().groups().add("other"));
        assertEquals(Map.of("propagationPolicy", "Foreground"), context.options());
        assertThrows(UnsupportedOperationException.class, () -> context.options().put("fieldManager", "test"));
        assertEquals("v1", new ConversionContext("v1", "v2").sourceVersion());
        assertEquals("v2", new ConversionContext("v1", "v2").desiredVersion());
        assertThrows(IllegalArgumentException.class, () -> new ConversionContext("", "v2"));
    }

    @Test
    void createsConversionResults() {
        var resource = new ConfigMapBuilder().withNewMetadata().withName("sample").endMetadata().build();

        assertTrue(ConversionResult.converted(resource).isConverted());
        assertEquals("conversion failed", ConversionResult.failed("conversion failed").message().orElseThrow());
        assertFalse(ConversionResult.failed("conversion failed").isConverted());
    }
}
