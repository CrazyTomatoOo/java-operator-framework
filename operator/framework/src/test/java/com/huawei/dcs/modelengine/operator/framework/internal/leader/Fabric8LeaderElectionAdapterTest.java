package com.huawei.dcs.modelengine.operator.framework.internal.leader;

import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElector;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectorBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Fabric8LeaderElectionAdapterTest {
    @Test
    void buildsDefaultLeaseElectionConfigurationAndForwardsCallbacks() {
        var client = mock(KubernetesClient.class);
        when(client.getNamespace()).thenReturn("operators");
        var properties = new OperatorFrameworkProperties();
        var environment = new MockEnvironment().withProperty("spring.application.name", "Sample Operator");
        var adapter = new Fabric8LeaderElectionAdapter(client, properties, environment);
        var starts = new AtomicInteger();
        var stops = new AtomicInteger();

        var config = adapter.config(starts::incrementAndGet, stops::incrementAndGet);
        config.getLeaderCallbacks().onStartLeading();
        config.getLeaderCallbacks().onStopLeading();

        assertThat(config.getName()).isEqualTo("sample-operator-leader");
        assertThat(config.getLock()).isInstanceOf(LeaseLock.class);
        assertThat(config.getLock().describe()).contains("operators", "sample-operator-leader");
        assertThat(config.getLeaseDuration()).isEqualTo(properties.getLeaderElection().getLeaseDuration());
        assertThat(config.getRenewDeadline()).isEqualTo(properties.getLeaderElection().getRenewDeadline());
        assertThat(config.getRetryPeriod()).isEqualTo(properties.getLeaderElection().getRetryPeriod());
        assertThat(starts).hasValue(1);
        assertThat(stops).hasValue(1);
    }

    @Test
    void honorsConfiguredLeaseNameAndNamespace() {
        var client = mock(KubernetesClient.class);
        when(client.getNamespace()).thenReturn("client-namespace");
        var properties = new OperatorFrameworkProperties();
        properties.getController().setNamespace("controller-namespace");
        properties.getLeaderElection().setLeaseName("custom-lease");
        properties.getLeaderElection().setNamespace("leader-namespace");
        var adapter = new Fabric8LeaderElectionAdapter(client, properties, new MockEnvironment());

        var config = adapter.config(() -> { }, () -> { });

        assertThat(config.getName()).isEqualTo("custom-lease");
        assertThat(config.getLock().describe()).contains("leader-namespace", "custom-lease");
    }

    @Test
    void boundsGeneratedLeaseName() {
        var client = mock(KubernetesClient.class);
        when(client.getNamespace()).thenReturn("operators");
        var applicationName = "Very Long Operator Name ".repeat(10);
        var environment = new MockEnvironment().withProperty("spring.application.name", applicationName);
        var adapter = new Fabric8LeaderElectionAdapter(
                client, new OperatorFrameworkProperties(), environment);

        var name = adapter.config(() -> { }, () -> { }).getName();

        assertThat(name).hasSizeLessThanOrEqualTo(63).matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    }

    @Test
    void cancellationReliesOnReleaseOnCancelWithoutDoubleRelease() {
        var client = mock(KubernetesClient.class);
        var builder = mock(LeaderElectorBuilder.class);
        var elector = mock(LeaderElector.class);
        var election = new CompletableFuture<>();
        when(client.leaderElector()).thenReturn(builder);
        when(builder.withConfig(any())).thenReturn(builder);
        when(builder.build()).thenReturn(elector);
        doReturn(election).when(elector).start();
        var adapter = new Fabric8LeaderElectionAdapter(
                client, new OperatorFrameworkProperties(), new MockEnvironment());

        adapter.start(() -> { }, () -> { });
        adapter.stop();

        assertThat(election).isCancelled();
        verify(elector, never()).release();
    }

    @Test
    void inheritsControllerNamespaceBeforeClientNamespace() {
        var client = mock(KubernetesClient.class);
        when(client.getNamespace()).thenReturn("client-namespace");
        var properties = new OperatorFrameworkProperties();
        properties.getController().setNamespace("controller-namespace");
        var adapter = new Fabric8LeaderElectionAdapter(client, properties, new MockEnvironment());

        assertThat(adapter.config(() -> { }, () -> { }).getLock().describe()).contains("controller-namespace");
    }
}
