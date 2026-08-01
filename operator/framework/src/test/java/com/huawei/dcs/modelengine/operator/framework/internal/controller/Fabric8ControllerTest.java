package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.TriggerRole;
import com.huawei.dcs.modelengine.operator.framework.autoconfigure.OperatorFrameworkProperties;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnableKubernetesMockClient(crud = true)
class Fabric8ControllerTest {
    KubernetesClient client;

    @Test
    void reconcilesPrimarySecondaryAndKubernetesEventTriggers() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).generationFilter(true)
                .watches("secrets", Secret.class, Mappers.byLabel("primary"))
                .watchesKubernetesEvents()
                .build();
        var primary = primary();
        client.configMaps().inNamespace("operators").resource(primary).create();
        var properties = properties();
        properties.getController().setFilterEventsByInvolvedObject(false);
        var runtime = new Fabric8Controller<>(client, registration, properties.getController(), Duration.ofSeconds(1));

        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(runtime.isReady()).isTrue();
                assertThat(contexts).isNotEmpty();
            });
            contexts.clear();

            client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder(primary)
                    .editMetadata().addToFinalizers("cleanup").endMetadata().build()).update();
            awaitRole(contexts, TriggerRole.PRIMARY);
            contexts.clear();

            client.secrets().inNamespace("operators").resource(new SecretBuilder()
                    .withNewMetadata().withName("secondary").withNamespace("operators")
                    .addToLabels("primary", "sample").endMetadata().build()).create();
            awaitRole(contexts, TriggerRole.WATCHED);
            contexts.clear();

            client.v1().events().inNamespace("operators").resource(new EventBuilder()
                    .withNewMetadata().withName("related").withNamespace("operators").endMetadata()
                    .withNewInvolvedObject().withApiVersion("v1").withKind("ConfigMap")
                    .withName("sample").withNamespace("operators").endInvolvedObject()
                    .build()).create();
            awaitRole(contexts, TriggerRole.KUBERNETES_EVENT);
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void eventCountIncrementsDoNotRetrigger() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).watchesKubernetesEvents().build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        var properties = properties();
        properties.getController().setFilterEventsByInvolvedObject(false);
        var runtime = new Fabric8Controller<>(client, registration, properties.getController(), Duration.ofSeconds(1));

        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);
            contexts.clear();
            var event = client.v1().events().inNamespace("operators").resource(new EventBuilder()
                    .withNewMetadata().withName("flapping").withNamespace("operators").endMetadata()
                    .withNewInvolvedObject().withApiVersion("v1").withKind("ConfigMap")
                    .withName("sample").withNamespace("operators").endInvolvedObject()
                    .build()).create();
            awaitRole(contexts, TriggerRole.KUBERNETES_EVENT);
            contexts.clear();

            event.setCount(2);
            client.v1().events().inNamespace("operators").resource(event).update();
            Thread.sleep(300);
            assertThat(contexts).isEmpty();
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void selectsConfiguredNamespaceOrAllNamespacesForClusterScope() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        client.configMaps().inNamespace("other").resource(new ConfigMapBuilder(primary())
                .editMetadata().withName("foreign").withNamespace("other").withUid("foreign-uid").endMetadata()
                .build()).create();
        var namespaced = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));

        try {
            namespaced.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(contexts)
                    .extracting(context -> context.resourceKey().namespace()).contains("operators"));
            Thread.sleep(200);
            assertThat(contexts).extracting(context -> context.resourceKey().namespace()).doesNotContain("other");
        } finally {
            namespaced.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }

        contexts.clear();
        var clusterProperties = properties();
        clusterProperties.getController().setNamespace(null);
        clusterProperties.getController().setClusterScoped(true);
        var cluster = new Fabric8Controller<>(
                client, registration, clusterProperties.getController(), Duration.ofSeconds(1));
        try {
            cluster.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(contexts)
                    .extracting(context -> context.resourceKey().namespace()).contains("operators", "other"));
        } finally {
            cluster.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void filtersSameGenerationButAllowsFinalizerAndDeliversDeletedResource() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).generationFilter(true).build();
        var primary = primary();
        client.configMaps().inNamespace("operators").resource(primary).create();
        var runtime = new Fabric8Controller<>(client, registration, properties().getController(), Duration.ofSeconds(1));

        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(contexts).isNotEmpty());
            contexts.clear();

            client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder(primary)
                    .editMetadata().addToLabels("ignored", "change").endMetadata().build()).update();
            Thread.sleep(200);
            assertThat(contexts).isEmpty();

            var deleting = client.configMaps().inNamespace("operators").withName("sample").get();
            client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder(deleting)
                    .editMetadata().addToFinalizers("cleanup").endMetadata().build()).update();
            awaitRole(contexts, TriggerRole.PRIMARY);
            contexts.clear();

            var finalizing = client.configMaps().inNamespace("operators").withName("sample").get();
            client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder(finalizing)
                    .editMetadata().withFinalizers(List.of()).endMetadata().build()).update();
            awaitRole(contexts, TriggerRole.PRIMARY);
            contexts.clear();

            client.configMaps().inNamespace("operators").withName("sample").delete();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(contexts)
                    .flatExtracting(ReconciliationContext::triggers)
                    .extracting(ReconciliationTrigger::eventType)
                    .contains(ResourceEventType.DELETED));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void retriesDeletedResourceFromSnapshot() throws Exception {
        var deletionCalls = new AtomicInteger();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            var deleted = context.triggers().stream()
                    .anyMatch(trigger -> trigger.eventType() == ResourceEventType.DELETED);
            if (!deleted && deletionCalls.get() == 0) {
                return ReconcileResult.done();
            }
            return deletionCalls.incrementAndGet() == 1
                    ? ReconcileResult.requeueAfter(Duration.ofMillis(10))
                    : ReconcileResult.done();
        }).build();
        var primary = primary();
        client.configMaps().inNamespace("operators").resource(primary).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));

        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);
            client.configMaps().inNamespace("operators").withName("sample").delete();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(deletionCalls).hasValue(2));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void repeatedStopInterruptsCurrentWorkAndDiscardsBacklog() throws Exception {
        var invocations = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            invocations.incrementAndGet();
            entered.countDown();
            release.await();
            return ReconcileResult.done();
        }).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(5));

        try {
            runtime.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder()
                    .withNewMetadata().withNamespace("operators").withName("queued").endMetadata().build()).create();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(runtime.queueDepth()).isPositive());

            var first = runtime.stop();
            var repeated = runtime.stop();

            assertThat(repeated).isSameAs(first);
            first.toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(invocations).hasValue(1);
        } finally {
            release.countDown();
            runtime.stop().toCompletableFuture().get(6, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerSurvivesReconcilerErrorAndContinuesProcessing() throws Exception {
        var calls = new AtomicInteger();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            if (calls.getAndIncrement() == 0) {
                throw new StackOverflowError("boom");
            }
            return ReconcileResult.done();
        }).generationFilter(false).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(calls.get()).isGreaterThanOrEqualTo(1));
            client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder(primary())
                    .editMetadata().addToLabels("tick", "2").endMetadata().build()).update();
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(calls.get()).isGreaterThanOrEqualTo(2));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }
    private OperatorFrameworkProperties properties() {
        var properties = new OperatorFrameworkProperties();
        properties.getController().setNamespace("operators");
        properties.getController().setResyncPeriod(Duration.ofHours(1));
        return properties;
    }

    private ConfigMap primary() {
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName("sample")
                .withNamespace("operators")
                .withUid("primary-uid")
                .withGeneration(1L)
                .endMetadata()
                .build();
    }

    @Test
    void indexedCacheResolvesPrimaryByIndexField() throws Exception {
        var resolved = new CopyOnWriteArrayList<List<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            resolved.add(context.cache().byIndex("by-name", resource.getMetadata().getName()));
            return ReconcileResult.done();
        }).indexField("by-name", r -> r.getMetadata().getName()).build();
        var primary = primary();
        client.configMaps().inNamespace("operators").resource(primary).create();
        var runtime = new Fabric8Controller<>(client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(runtime.isReady()).isTrue();
                assertThat(resolved).isNotEmpty();
            });
            var hit = resolved.getFirst();
            assertThat(hit).hasSize(1);
            assertThat(((ConfigMap) hit.getFirst()).getMetadata().getName()).isEqualTo("sample");
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void secondaryCachesExposeWatchedAndPrimaryResources() throws Exception {
        var found = new CopyOnWriteArrayList<Boolean>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            var secret = context.cacheFor(Secret.class).getByKey("operators/secondary");
            var primaryHit = context.cacheFor(ConfigMap.class).getByKey("operators/sample");
            found.add(secret != null && primaryHit != null);
            return ReconcileResult.done();
        }).watches("secrets", Secret.class, Mappers.byLabel("primary")).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        client.secrets().inNamespace("operators").resource(new SecretBuilder()
                .withNewMetadata().withName("secondary").withNamespace("operators")
                .addToLabels("primary", "sample").endMetadata().build()).create();
        var runtime = new Fabric8Controller<>(client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(found).contains(true));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void startTwiceIsIgnoredAndStopBeforeStartCompletesImmediately() throws Exception {
        var registration = ControllerBuilder.forResource(ConfigMap.class,
                (resource, context) -> ReconcileResult.done()).build();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));

        runtime.stop().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertThat(runtime.isRunning()).isFalse();
        assertThat(runtime.isReady()).isFalse();

        try {
            runtime.start();
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);
            assertThat(runtime.isRunning()).isTrue();
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
        assertThat(runtime.isRunning()).isFalse();
    }

    @Test
    void labelSelectorReconcilesOnlyMatchingPrimaries() throws Exception {
        var names = new CopyOnWriteArrayList<String>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            names.add(resource.getMetadata().getName());
            return ReconcileResult.done();
        }).labelSelector(Map.of("expose", "yes")).build();
        client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder()
                .withNewMetadata().withName("visible").withNamespace("operators").withUid("visible-uid")
                .addToLabels("expose", "yes").endMetadata().build()).create();
        client.configMaps().inNamespace("operators").resource(new ConfigMapBuilder()
                .withNewMetadata().withName("hidden").withNamespace("operators").withUid("hidden-uid")
                .endMetadata().build()).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(names).contains("visible"));
            Thread.sleep(300);
            assertThat(names).doesNotContain("hidden");
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void ownedResourceTriggersOwnedReconcile() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).owns(Secret.class).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);
            contexts.clear();

            client.secrets().inNamespace("operators").resource(new SecretBuilder()
                    .withNewMetadata().withName("owned").withNamespace("operators")
                    .withOwnerReferences(new OwnerReferenceBuilder()
                            .withApiVersion("v1").withKind("ConfigMap").withController(true)
                            .withName("sample").withUid("primary-uid").build())
                    .endMetadata().build()).create();
            awaitRole(contexts, TriggerRole.OWNED);
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void watchedSecondaryDeleteEnqueuesDeletionTrigger() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).watches("secrets", Secret.class, Mappers.byLabel("primary")).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        client.secrets().inNamespace("operators").resource(new SecretBuilder()
                .withNewMetadata().withName("secondary").withNamespace("operators")
                .addToLabels("primary", "sample").endMetadata().build()).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            awaitRole(contexts, TriggerRole.WATCHED);
            contexts.clear();

            client.secrets().inNamespace("operators").withName("secondary").delete();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(contexts)
                    .anySatisfy(context -> assertThat(context.triggers()).anySatisfy(trigger -> {
                        assertThat(trigger.role()).isEqualTo(TriggerRole.WATCHED);
                        assertThat(trigger.eventType()).isEqualTo(ResourceEventType.DELETED);
                    })));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void failingSecondaryMapperIsSwallowedAndControllerStaysHealthy() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).watches("secrets", Secret.class, event -> {
            throw new IllegalStateException("broken mapper");
        }).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);
            contexts.clear();

            client.secrets().inNamespace("operators").resource(new SecretBuilder()
                    .withNewMetadata().withName("secondary").withNamespace("operators").endMetadata()
                    .build()).create();
            Thread.sleep(300);
            assertThat(contexts).isEmpty();
            assertThat(runtime.isReady()).isTrue();
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void secondaryKeyWithoutPrimaryResourceIsSkipped() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).watches("secrets", Secret.class, Mappers.byLabel("primary")).build();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);

            client.secrets().inNamespace("operators").resource(new SecretBuilder()
                    .withNewMetadata().withName("orphan").withNamespace("operators")
                    .addToLabels("primary", "ghost").endMetadata().build()).create();
            Thread.sleep(300);
            assertThat(contexts).isEmpty();
            assertThat(runtime.isReady()).isTrue();
            assertThat(runtime.queueDepth()).isZero();
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void resyncPeriodDeliversResyncTriggers() throws Exception {
        var contexts = new CopyOnWriteArrayList<ReconciliationContext<?>>();
        var registration = ControllerBuilder.forResource(ConfigMap.class, (resource, context) -> {
            contexts.add(context);
            return ReconcileResult.done();
        }).generationFilter(true).watches("secrets", Secret.class, Mappers.byLabel("primary")).build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        client.secrets().inNamespace("operators").resource(new SecretBuilder()
                .withNewMetadata().withName("secondary").withNamespace("operators")
                .addToLabels("primary", "sample").endMetadata().build()).create();
        var props = properties();
        props.getController().setResyncPeriod(Duration.ofMillis(100));
        var runtime = new Fabric8Controller<>(client, registration, props.getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(contexts)
                    .flatExtracting(ReconciliationContext::triggers)
                    .extracting(ReconciliationTrigger::eventType)
                    .contains(ResourceEventType.RESYNC));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void gaugesReflectQueueDepthAndSyncState() throws Exception {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var metrics = new OperatorFrameworkMetrics(registry);
        var controller = ConfigMap.class.getName();
        var registration = ControllerBuilder.forResource(ConfigMap.class,
                (resource, context) -> ReconcileResult.done()).build();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1), metrics);

        assertThat(registry.get("operator.framework.queue.depth")
                .tag("controller", controller).gauge().value()).isZero();
        assertThat(registry.get("operator.framework.informer.synced")
                .tag("controller", controller).gauge().value()).isZero();

        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(registry.get("operator.framework.informer.synced")
                            .tag("controller", controller).gauge().value()).isEqualTo(1.0));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void clusterScopedResourceReconcilesWithNullNamespaceKey() throws Exception {
        var keys = new CopyOnWriteArrayList<ResourceKey>();
        var registration = ControllerBuilder.forResource(Namespace.class, (resource, context) -> {
            keys.add(context.resourceKey());
            return ReconcileResult.done();
        }).build();
        client.namespaces().resource(new NamespaceBuilder()
                .withNewMetadata().withName("watched-namespace").endMetadata().build()).create();
        var props = properties();
        props.getController().setClusterScoped(true);
        var runtime = new Fabric8Controller<>(client, registration, props.getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(keys)
                    .anySatisfy(key -> assertThat(key.namespace()).isNull()));
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void eventInformerWithInvolvedObjectFieldFilterStartsAndStaysHealthy() throws Exception {
        // ponytail: the in-memory mock cannot match involvedObject field selectors, so this
        // only exercises the filter-enabled informer wiring, not event delivery
        var registration = ControllerBuilder.forResource(ConfigMap.class,
                (resource, context) -> ReconcileResult.done()).watchesKubernetesEvents().build();
        client.configMaps().inNamespace("operators").resource(primary()).create();
        var runtime = new Fabric8Controller<>(
                client, registration, properties().getController(), Duration.ofSeconds(1));
        try {
            runtime.start();
            await().atMost(Duration.ofSeconds(5)).until(runtime::isReady);
            assertThat(runtime.isRunning()).isTrue();
        } finally {
            runtime.stop().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private void awaitRole(List<ReconciliationContext<?>> contexts, TriggerRole role) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(contexts)
                .anySatisfy(context -> assertThat(context.triggers())
                        .anySatisfy(trigger -> assertThat(trigger.role()).isEqualTo(role))));
    }
}
