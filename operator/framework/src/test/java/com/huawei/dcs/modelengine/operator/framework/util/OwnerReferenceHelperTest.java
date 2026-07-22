package com.huawei.dcs.modelengine.operator.framework.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OwnerReferenceHelperTest {

  @Test
  void createControllerOwnerReferenceSetsExpectedFields() {
    HasMetadata owner = mock(HasMetadata.class);
    ObjectMeta metadata = new ObjectMeta();
    metadata.setName("echo");
    metadata.setUid("uid-123");

    when(owner.getApiVersion()).thenReturn("example.com/v1");
    when(owner.getKind()).thenReturn("Echo");
    when(owner.getMetadata()).thenReturn(metadata);

    OwnerReference reference = OwnerReferenceHelper.createControllerOwnerReference(owner);

    assertAll(
        () -> assertNotNull(reference),
        () -> assertEquals("example.com/v1", reference.getApiVersion()),
        () -> assertEquals("Echo", reference.getKind()),
        () -> assertEquals("echo", reference.getName()),
        () -> assertEquals("uid-123", reference.getUid()),
        () -> assertTrue(Boolean.TRUE.equals(reference.getController())),
        () -> assertTrue(Boolean.TRUE.equals(reference.getBlockOwnerDeletion()))
    );
  }
}
