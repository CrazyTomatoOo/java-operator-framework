/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.event;

import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@EnableKubernetesMockClient(crud = true)
class AggregatingKubernetesEventPublisherTest {
    KubernetesClient client;

    @Test
    void createsAggregatesWithConfiguredComponentAndStartsANewDeterministicWindow() {
        var properties = new OperatorFrameworkProperties();
        properties.getEvents().setComponent("configured-component");
        var environment = new MockEnvironment().withProperty("spring.application.name", "sample-operator");
        var clock = new MutableClock(Instant.parse("2024-01-01T00:00:00Z"));
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").withUid("uid-1").endMetadata()
                .build();

        try (var publisher = new AggregatingKubernetesEventPublisher(client, properties, environment, clock)) {
            publisher.normal(resource, "Ready", "controller is ready");
            publisher.normal(resource, "Ready", "controller is ready");

            var events = client.v1().events().inNamespace("operators").list().getItems();
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.getCount()).isEqualTo(2);
                assertThat(event.getReportingComponent()).isEqualTo("configured-component");
                assertThat(event.getSource().getComponent()).isEqualTo("configured-component");
                assertThat(event.getMetadata().getName()).startsWith("sample.");
            });

            clock.advance(Duration.ofMinutes(6));
            publisher.normal(resource, "Ready", "controller is ready");
            assertThat(client.v1().events().inNamespace("operators").list().getItems()).hasSize(2);
        }
    }

    @Test
    void validatesPublicInputs() {
        var publisher = new AggregatingKubernetesEventPublisher(
                client, new OperatorFrameworkProperties(), new MockEnvironment(), Clock.systemUTC());
        var resource = new ConfigMapBuilder().withNewMetadata().withName("sample").endMetadata().build();

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> publisher.warning(resource, " ", "message"));
        publisher.close();
    }

    @Test
    void publicationFailureDoesNotFailReconciliation() {
        var publisher = new AggregatingKubernetesEventPublisher(
                client, new OperatorFrameworkProperties(), new MockEnvironment(), Clock.systemUTC());
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").endMetadata().build();
        client.close();

        assertThatCode(() -> publisher.normal(resource, "Ready", "ready")).doesNotThrowAnyException();
        publisher.close();
    }

    @Test
    void warningPublishesWarningTypeEvent() {
        var publisher = new AggregatingKubernetesEventPublisher(
                client, new OperatorFrameworkProperties(), new MockEnvironment(), Clock.systemUTC());
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").endMetadata().build();

        publisher.warning(resource, "Unhealthy", "probe failed");

        assertThat(client.v1().events().inNamespace("operators").list().getItems())
                .singleElement().satisfies(event -> assertThat(event.getType()).isEqualTo("Warning"));
        publisher.close();
    }

    @Test
    void republishAfterCacheEvictionMergesIntoExistingEvent() {
        var properties = new OperatorFrameworkProperties();
        properties.getEvents().setMaxCacheEntries(1);
        var publisher = new AggregatingKubernetesEventPublisher(
                client, properties, new MockEnvironment(), Clock.systemUTC());
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").withUid("uid-1").endMetadata()
                .build();

        publisher.normal(resource, "Ready", "first");
        publisher.normal(resource, "Ready", "second");
        publisher.normal(resource, "Ready", "first");

        var events = client.v1().events().inNamespace("operators").list().getItems();
        assertThat(events).hasSize(2);
        assertThat(events).filteredOn(event -> "first".equals(event.getMessage()))
                .singleElement().satisfies(event -> assertThat(event.getCount()).isEqualTo(2));
        publisher.close();
    }

    @Test
    void createConflictWithoutExistingEventIsSwallowed() {
        var mocks = new EventClientMocks();
        var conflict = new io.fabric8.kubernetes.client.KubernetesClientException("conflict", 409, null);
        org.mockito.Mockito.when(mocks.resource.create()).thenThrow(conflict);
        org.mockito.Mockito.when(mocks.resource.get()).thenReturn(null);
        var publisher = new AggregatingKubernetesEventPublisher(
                mocks.client, new OperatorFrameworkProperties(), new MockEnvironment(), Clock.systemUTC());
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").endMetadata().build();

        assertThatCode(() -> publisher.normal(resource, "Ready", "ready")).doesNotThrowAnyException();
        publisher.close();
    }

    @Test
    void updateConflictRetriesWithLatestServerState() {
        var mocks = new EventClientMocks();
        var conflict = new io.fabric8.kubernetes.client.KubernetesClientException("conflict", 409, null);
        var persisted = new io.fabric8.kubernetes.api.model.EventBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample.abc").endMetadata().build();
        org.mockito.Mockito.when(mocks.resource.create()).thenReturn(persisted);
        org.mockito.Mockito.when(mocks.resource.update()).thenThrow(conflict).thenReturn(persisted);
        org.mockito.Mockito.when(mocks.resource.get()).thenReturn(persisted);
        var clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        var publisher = new AggregatingKubernetesEventPublisher(
                mocks.client, new OperatorFrameworkProperties(), new MockEnvironment(), clock);
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").withUid("uid-1").endMetadata()
                .build();

        publisher.normal(resource, "Ready", "ready");
        assertThatCode(() -> publisher.normal(resource, "Ready", "ready")).doesNotThrowAnyException();
        publisher.close();
    }

    @Test
    void updateConflictWithDisappearedEventIsSwallowed() {
        var mocks = new EventClientMocks();
        var conflict = new io.fabric8.kubernetes.client.KubernetesClientException("conflict", 409, null);
        var persisted = new io.fabric8.kubernetes.api.model.EventBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample.abc").endMetadata().build();
        org.mockito.Mockito.when(mocks.resource.create()).thenReturn(persisted);
        org.mockito.Mockito.when(mocks.resource.update()).thenThrow(conflict);
        org.mockito.Mockito.when(mocks.resource.get()).thenReturn(null);
        var clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        var publisher = new AggregatingKubernetesEventPublisher(
                mocks.client, new OperatorFrameworkProperties(), new MockEnvironment(), clock);
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").withUid("uid-1").endMetadata()
                .build();

        publisher.normal(resource, "Ready", "ready");
        assertThatCode(() -> publisher.normal(resource, "Ready", "ready")).doesNotThrowAnyException();
        publisher.close();
    }

    @SuppressWarnings("unchecked")
    private static final class EventClientMocks {
        private final KubernetesClient client = org.mockito.Mockito.mock(KubernetesClient.class);
        private final io.fabric8.kubernetes.client.dsl.V1APIGroupDSL v1 =
                org.mockito.Mockito.mock(io.fabric8.kubernetes.client.dsl.V1APIGroupDSL.class);
        private final io.fabric8.kubernetes.client.dsl.MixedOperation<
                io.fabric8.kubernetes.api.model.Event,
                io.fabric8.kubernetes.api.model.EventList,
                io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.Event>> events =
                org.mockito.Mockito.mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        private final io.fabric8.kubernetes.client.dsl.NonNamespaceOperation<
                io.fabric8.kubernetes.api.model.Event,
                io.fabric8.kubernetes.api.model.EventList,
                io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.Event>> inNamespace =
                org.mockito.Mockito.mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        private final io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.Event> resource =
                org.mockito.Mockito.mock(io.fabric8.kubernetes.client.dsl.Resource.class);

        EventClientMocks() {
            org.mockito.Mockito.when(client.v1()).thenReturn(v1);
            org.mockito.Mockito.when(v1.events()).thenReturn(events);
            org.mockito.Mockito.when(events.inNamespace(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(inNamespace);
            org.mockito.Mockito.when(inNamespace.withName(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(resource);
            org.mockito.Mockito.when(inNamespace.resource(
                    org.mockito.ArgumentMatchers.any(io.fabric8.kubernetes.api.model.Event.class)))
                    .thenReturn(resource);
        }
    }

    @Test
    void blankNamespaceFallsBackToDefault() {
        var publisher = new AggregatingKubernetesEventPublisher(
                client, new OperatorFrameworkProperties(), new MockEnvironment(), Clock.systemUTC());
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace(" ").withName("sample").endMetadata().build();

        publisher.normal(resource, "Ready", "ready");

        assertThat(client.v1().events().inNamespace("default").list().getItems()).hasSize(1);
        publisher.close();
    }

    @Test
    void blankConfiguredComponentFallsBackToApplicationName() {
        var properties = new OperatorFrameworkProperties();
        properties.getEvents().setComponent(" ");
        var environment = new MockEnvironment().withProperty("spring.application.name", "my-operator");
        var publisher = new AggregatingKubernetesEventPublisher(client, properties, environment, Clock.systemUTC());
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").endMetadata().build();

        publisher.normal(resource, "Ready", "ready");

        assertThat(client.v1().events().inNamespace("operators").list().getItems())
                .singleElement().satisfies(event -> assertThat(event.getReportingComponent()).isEqualTo("my-operator"));
        publisher.close();
    }

    @Test
    void blankApplicationNameFallsBackToFrameworkComponent() {
        var environment = new MockEnvironment().withProperty("spring.application.name", " ");
        var publisher = new AggregatingKubernetesEventPublisher(
                client, new OperatorFrameworkProperties(), environment, Clock.systemUTC());
        var resource = new ConfigMapBuilder()
                .withNewMetadata().withNamespace("operators").withName("sample").endMetadata().build();

        publisher.normal(resource, "Ready", "ready");

        assertThat(client.v1().events().inNamespace("operators").list().getItems())
                .singleElement().satisfies(event ->
                        assertThat(event.getReportingComponent()).isEqualTo("operator-framework"));
        publisher.close();
    }

    @Test
    void validationRejectsNullReason() {
        var publisher = new AggregatingKubernetesEventPublisher(
                client, new OperatorFrameworkProperties(), new MockEnvironment(), Clock.systemUTC());
        var resource = new ConfigMapBuilder().withNewMetadata().withName("sample").endMetadata().build();

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> publisher.normal(resource, null, "message"));
        publisher.close();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
