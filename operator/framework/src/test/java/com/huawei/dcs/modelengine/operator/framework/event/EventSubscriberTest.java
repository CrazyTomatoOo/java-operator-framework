package com.huawei.dcs.modelengine.operator.framework.event;

import com.huawei.dcs.modelengine.operator.framework.SecondaryWatch;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.ObjectReference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSubscriberTest {
    @Test
    void shouldExposeSecondaryWatchForEvents() {
        SecondaryWatch<ConfigMap, Event> watch = EventSubscriber.forInvolvedObject(ConfigMap.class).toSecondaryWatch();

        assertEquals("events", watch.name());
        assertEquals(Event.class, watch.resourceClass());
        assertNotNull(watch.mapper());
    }

    @Test
    void shouldMapEventInvolvingMatchingPrimaryResource() {
        EventSubscriber<ConfigMap> subscriber = EventSubscriber.forInvolvedObject(ConfigMap.class);
        Event event = eventWithInvolvedObject("ConfigMap", "v1", "test", "config-1");

        List<Request> requests = subscriber.toSecondaryWatch().mapper().map(event, null).stream().toList();

        assertEquals(List.of(new Request("test", "config-1")), requests);
    }

    @Test
    void shouldReturnEmptyWhenInvolvedObjectKindDoesNotMatch() {
        EventSubscriber<ConfigMap> subscriber = EventSubscriber.forInvolvedObject(ConfigMap.class);
        Event event = eventWithInvolvedObject("Deployment", "apps/v1", "test", "config-1");

        assertTrue(subscriber.toSecondaryWatch().mapper().map(event, null).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenInvolvedObjectApiVersionDoesNotMatch() {
        EventSubscriber<ConfigMap> subscriber = EventSubscriber.forInvolvedObject(ConfigMap.class);
        Event event = eventWithInvolvedObject("ConfigMap", "v2", "test", "config-1");

        assertTrue(subscriber.toSecondaryWatch().mapper().map(event, null).isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenInvolvedObjectIsNull() {
        EventSubscriber<ConfigMap> subscriber = EventSubscriber.forInvolvedObject(ConfigMap.class);
        Event event = new Event();

        assertTrue(subscriber.toSecondaryWatch().mapper().map(event, null).isEmpty());
    }

    @Test
    void shouldRejectNullPrimaryResourceClass() {
        assertThrows(NullPointerException.class, () -> EventSubscriber.forInvolvedObject(null));
        assertThrows(NullPointerException.class, () -> EventMapper.involvedObject(null));
    }

    private static Event eventWithInvolvedObject(String kind, String apiVersion, String namespace, String name) {
        ObjectReference involvedObject = new ObjectReference();
        involvedObject.setKind(kind);
        involvedObject.setApiVersion(apiVersion);
        involvedObject.setNamespace(namespace);
        involvedObject.setName(name);
        Event event = new Event();
        event.setInvolvedObject(involvedObject);
        return event;
    }
}
