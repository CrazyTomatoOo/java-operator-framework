package com.example.echooperator;

import com.example.echooperator.EchoWebhooks.EchoMutator;
import com.example.echooperator.EchoWebhooks.EchoValidator;
import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceEventType;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.TriggerRole;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@EnableKubernetesMockClient(crud = true)
class EchoOperatorTest {
    private KubernetesClient client;
    private KubernetesEventPublisher events;
    private EchoReconciler reconciler;

    @BeforeEach
    void setUp() {
        events = mock(KubernetesEventPublisher.class);
        reconciler = new EchoReconciler(client, events);
    }

    @Test
    void controllerRegistrationAppliesLabelSelectorAndIndexField() {
        var registration = new EchoOperatorApplication().echoController(reconciler);
        var selector = registration.watchSelector().orElseThrow();
        assertThat(selector.labels()).containsEntry(EchoReconciler.ENABLED_LABEL, "true");
        assertThat(registration.indexFields()).containsKey(EchoReconciler.INDEX_ECHO_TARGET);
    }


    @Test
    void echoesUppercaseMessageIntoOwnedChild() {
        var source = source("sample", "hello");
        client.configMaps().inNamespace("default").resource(source).create();

        var result = reconciler.reconcile(source, context(source, ResourceEventType.ADDED));

        assertThat(result.isDone()).isTrue();
        var child = client.configMaps().inNamespace("default").withName("sample-echo").get();
        assertThat(child.getData()).containsEntry("message", "HELLO");
        assertThat(child.getMetadata().getOwnerReferences()).singleElement().satisfies(owner -> {
            assertThat(owner.getKind()).isEqualTo("ConfigMap");
            assertThat(owner.getName()).isEqualTo("sample");
            assertThat(owner.getController()).isTrue();
        });
        verify(events).normal(source, "Echoed", "Echoed message into ConfigMap sample-echo");
    }

    @Test
    void skipsUnlabeledResourcesAndDeletes() {
        var unlabeled = new ConfigMapBuilder(source("plain", "hello"))
                .editMetadata().withLabels(null).endMetadata().build();
        var deleted = source("gone", "hello");

        reconciler.reconcile(unlabeled, context(unlabeled, ResourceEventType.ADDED));
        reconciler.reconcile(deleted, context(deleted, ResourceEventType.DELETED));

        assertThat(client.configMaps().inNamespace("default").withName("plain-echo").get()).isNull();
        assertThat(client.configMaps().inNamespace("default").withName("gone-echo").get()).isNull();
        verify(events, never()).normal(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void springContextDiscoversAllCallbacksInCombinedMode() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class,
                        ServletWebServerFactoryAutoConfiguration.class,
                        com.huawei.dcs.modelengine.operator.framework.autoconfigure
                                .OperatorFrameworkAutoConfiguration.class))
                .withUserConfiguration(EchoOperatorApplication.class)
                .withBean(KubernetesClient.class, () -> mock(KubernetesClient.class),
                        definition -> definition.setDestroyMethodName(""))
                .withPropertyValues("operator.framework.mode=combined")
                .run(context -> {
                    assertThat(context).hasSingleBean(EchoReconciler.class);
                    assertThat(context).hasSingleBean(EchoValidator.class);
                    assertThat(context).hasSingleBean(EchoMutator.class);
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    void admissionCallbacksDefaultAndRequireMessage() {
        var mutator = new EchoMutator();
        var validator = new EchoValidator();
        var empty = source("empty", null);

        var mutated = mutator.mutate(empty, null);
        assertThat(mutated.resource()).hasValueSatisfying(
                resource -> assertThat(resource.getData()).containsEntry("message", "hello world"));

        assertThat(validator.validate(source("blank", " "), null).isAllowed()).isFalse();
        assertThat(validator.validate(source("ok", "hi"), null).isAllowed()).isTrue();
    }

    private ConfigMap source(String name, String message) {
        var builder = new ConfigMapBuilder()
                .withApiVersion("v1").withKind("ConfigMap")
                .withNewMetadata()
                .withNamespace("default").withName(name).withUid(name + "-uid")
                .withLabels(java.util.Map.of(EchoReconciler.ENABLED_LABEL, "true"))
                .endMetadata();
        return message == null ? builder.build() : builder.addToData("message", message).build();
    }

    private ReconciliationContext<ConfigMap> context(ConfigMap resource, ResourceEventType type) {
        var trigger = new ReconciliationTrigger(type, TriggerRole.PRIMARY, ResourceReference.from(resource));
        return ReconciliationContext.<ConfigMap>withoutCache(ResourceReference.from(resource).key(), List.of(trigger));
    }
}
