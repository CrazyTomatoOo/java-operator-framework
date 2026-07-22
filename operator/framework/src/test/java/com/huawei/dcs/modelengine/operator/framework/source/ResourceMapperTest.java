package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;

import io.fabric8.kubernetes.api.model.ConfigMap;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ResourceMapperTest {
    @Test
    void shouldBeUsableAsLambdaAndReturnRequests() {
        ResourceMapper<ConfigMap, ConfigMap> mapper = (secondary, event) -> List.of(new Request("primary-ns", "primary-name"));

        @SuppressWarnings("unchecked")
        ResourceEvent<ConfigMap> event = mock(ResourceEvent.class);
        Collection<Request> requests = mapper.map(new ConfigMap(), event);

        assertEquals(List.of(new Request("primary-ns", "primary-name")), requests);
    }
}
