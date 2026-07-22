package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappersTest {
    @Test
    void ownerReferencesShouldReturnControllerOwnerRequests() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setNamespace("secondary-ns");
        metadata.setOwnerReferences(List.of(controllerOwnerReference("primary-name")));
        resource.setMetadata(metadata);

        List<Request> requests = Mappers.<ConfigMap, ConfigMap>ownerReferences().map(resource, null).stream().toList();

        assertEquals(List.of(new Request("secondary-ns", "primary-name")), requests);
    }

    @Test
    void ownerReferencesShouldReturnEmptyWhenOwnerReferencesMissing() {
        ConfigMap resource = new ConfigMap();
        resource.setMetadata(new ObjectMeta());

        assertTrue(Mappers.<ConfigMap, ConfigMap>ownerReferences().map(resource, null).isEmpty());
    }

    @Test
    void byLabelShouldMapPrimaryNameFromLabelInSameNamespace() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setNamespace("secondary-ns");
        metadata.setLabels(Map.of("primary-name", "primary-name"));
        resource.setMetadata(metadata);

        assertEquals(List.of(new Request("secondary-ns", "primary-name")),
            Mappers.<ConfigMap, ConfigMap>byLabel("primary-name").map(resource, null));
    }

    @Test
    void byLabelShouldMapPrimaryNameAndNamespaceFromLabels() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setLabels(Map.of("primary-name", "primary-name", "primary-namespace", "primary-ns"));
        resource.setMetadata(metadata);

        assertEquals(List.of(new Request("primary-ns", "primary-name")),
            Mappers.<ConfigMap, ConfigMap>byLabel("primary-name", "primary-namespace").map(resource, null));
    }

    @Test
    void byAnnotationShouldMapPrimaryNameAndNamespaceFromAnnotations() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setAnnotations(Map.of("primary-name", "primary-name", "primary-namespace", "primary-ns"));
        resource.setMetadata(metadata);

        assertEquals(List.of(new Request("primary-ns", "primary-name")),
            Mappers.<ConfigMap, ConfigMap>byAnnotation("primary-name", "primary-namespace").map(resource, null));
    }
    @Test
    void ownerReferencesShouldReturnEmptyWhenNoControllerOwner() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setNamespace("secondary-ns");
        OwnerReference ownerReference = new OwnerReference();
        ownerReference.setApiVersion("example.com/v1");
        ownerReference.setKind("Primary");
        ownerReference.setName("primary-name");
        ownerReference.setUid("uid-123");
        ownerReference.setController(false);
        ownerReference.setBlockOwnerDeletion(true);
        metadata.setOwnerReferences(List.of(ownerReference));
        resource.setMetadata(metadata);

        assertTrue(Mappers.<ConfigMap, ConfigMap>ownerReferences().map(resource, null).isEmpty());
    }

    @Test
    void byLabelShouldReturnEmptyWhenLabelMissing() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setNamespace("secondary-ns");
        resource.setMetadata(metadata);

        assertTrue(Mappers.<ConfigMap, ConfigMap>byLabel("primary-name").map(resource, null).isEmpty());
    }

    @Test
    void byLabelShouldReturnEmptyWhenNamespaceLabelMissing() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setLabels(Map.of("primary-name", "primary-name"));
        resource.setMetadata(metadata);

        assertTrue(Mappers.<ConfigMap, ConfigMap>byLabel("primary-name", "primary-namespace").map(resource, null).isEmpty());
    }

    @Test
    void byAnnotationShouldReturnEmptyWhenNameAnnotationMissing() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setAnnotations(Map.of("primary-namespace", "primary-ns"));
        resource.setMetadata(metadata);

        assertTrue(Mappers.<ConfigMap, ConfigMap>byAnnotation("primary-name", "primary-namespace").map(resource, null).isEmpty());
    }

    @Test
    void byAnnotationShouldReturnEmptyWhenNamespaceAnnotationMissing() {
        ConfigMap resource = new ConfigMap();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setAnnotations(Map.of("primary-name", "primary-name"));
        resource.setMetadata(metadata);

        assertTrue(Mappers.<ConfigMap, ConfigMap>byAnnotation("primary-name", "primary-namespace").map(resource, null).isEmpty());
    }

    @Test
    void mapperShouldReturnEmptyWhenMetadataMissing() {
        ConfigMap resource = new ConfigMap();

        assertTrue(Mappers.<ConfigMap, ConfigMap>ownerReferences().map(resource, null).isEmpty());
        assertTrue(Mappers.<ConfigMap, ConfigMap>byLabel("primary-name").map(resource, null).isEmpty());
        assertTrue(Mappers.<ConfigMap, ConfigMap>byAnnotation("primary-name", "primary-namespace").map(resource, null).isEmpty());
    }


    private static OwnerReference controllerOwnerReference(String name) {
        OwnerReference ownerReference = new OwnerReference();
        ownerReference.setApiVersion("example.com/v1");
        ownerReference.setKind("Primary");
        ownerReference.setName(name);
        ownerReference.setUid("uid-123");
        ownerReference.setController(true);
        ownerReference.setBlockOwnerDeletion(true);
        return ownerReference;
    }
}
