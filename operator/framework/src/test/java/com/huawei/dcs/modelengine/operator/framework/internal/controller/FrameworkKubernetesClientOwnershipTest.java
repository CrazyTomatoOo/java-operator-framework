package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FrameworkKubernetesClientOwnershipTest {
    @Test
    void closesOnlyOwnedClient() {
        var owned = mock(KubernetesClient.class);
        var supplied = mock(KubernetesClient.class);
        var ownership = new FrameworkKubernetesClientOwnership();

        ownership.own(owned);
        assertThat(ownership.owns(owned)).isTrue();
        assertThat(ownership.owns(supplied)).isFalse();
        ownership.destroy();

        verify(owned).close();
        verify(supplied, never()).close();
    }
}
