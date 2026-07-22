package com.huawei.dcs.modelengine.operator.framework;

import com.huawei.dcs.modelengine.operator.framework.event.EventSubscriber;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerBuilderTest {
    @Test
    void shouldBuildRegistrationWithPrimaryReconcilerAndSecondaryWatches() {
        ResourceMapper<Pod, ConfigMap> podMapper = (pod, event) -> List.of(new Request(pod.getMetadata().getNamespace(), "primary"));

        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler((request, resource) -> Result.done())
            .owns(Secret.class)
            .watches("pod-watch", Pod.class, podMapper)
            .build();

        assertSame(ConfigMap.class, registration.resourceClass());
        assertEquals(2, registration.secondaryWatches().size());

        SecondaryWatch<ConfigMap, ?> ownedWatch = registration.secondaryWatches().get(0);
        assertEquals("Secret", ownedWatch.name());
        assertSame(Secret.class, ownedWatch.resourceClass());
        assertTrue(ownedWatch.owned());

        SecondaryWatch<ConfigMap, ?> watchedSource = registration.secondaryWatches().get(1);
        assertEquals("pod-watch", watchedSource.name());
        assertSame(Pod.class, watchedSource.resourceClass());
        assertFalse(watchedSource.owned());
        assertSame(podMapper, watchedSource.mapper());
    }

    @Test
    void shouldBuildRegistrationWithEventSubscriber() {
        ControllerRegistration<ConfigMap> registration = ControllerBuilder.forResource(ConfigMap.class)
            .withReconciler((request, resource) -> Result.done())
            .withEventSubscriber(EventSubscriber.forInvolvedObject(ConfigMap.class))
            .build();

        assertEquals(1, registration.secondaryWatches().size());
        SecondaryWatch<ConfigMap, ?> eventWatch = registration.secondaryWatches().get(0);
        assertEquals("events", eventWatch.name());
        assertSame(Event.class, eventWatch.resourceClass());
        assertNotNull(eventWatch.mapper());
    }

    @Test
    void shouldRejectNullEventSubscriber() {
        ControllerBuilder<ConfigMap> builder = ControllerBuilder.forResource(ConfigMap.class);

        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> builder.withEventSubscriber(null));

        assertTrue(exception.getMessage().contains("eventSubscriber"));
    }
}
