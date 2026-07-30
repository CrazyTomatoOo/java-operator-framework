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
