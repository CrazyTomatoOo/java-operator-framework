package com.huawei.dcs.modelengine.operator.framework.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinalizerHelperTest {

  @Test
  void addFinalizerIsIdempotent() {
    HasMetadata resource = mock(HasMetadata.class);
    ObjectMeta metadata = new ObjectMeta();
    when(resource.getMetadata()).thenReturn(metadata);

    assertTrue(FinalizerHelper.addFinalizer(resource, "echo.example.com/finalizer"));
    assertTrue(FinalizerHelper.hasFinalizer(resource, "echo.example.com/finalizer"));
    assertFalse(FinalizerHelper.addFinalizer(resource, "echo.example.com/finalizer"));
    assertIterableEquals(
        java.util.List.of("echo.example.com/finalizer"),
        resource.getMetadata().getFinalizers()
    );
  }

  @Test
  void removeFinalizerIsIdempotent() {
    HasMetadata resource = mock(HasMetadata.class);
    ObjectMeta metadata = new ObjectMeta();
    metadata.setFinalizers(new java.util.ArrayList<>(java.util.List.of("echo.example.com/finalizer")));
    when(resource.getMetadata()).thenReturn(metadata);

    assertTrue(FinalizerHelper.removeFinalizer(resource, "echo.example.com/finalizer"));
    assertFalse(FinalizerHelper.hasFinalizer(resource, "echo.example.com/finalizer"));
    assertFalse(FinalizerHelper.removeFinalizer(resource, "echo.example.com/finalizer"));
    assertTrue(resource.getMetadata().getFinalizers() == null || resource.getMetadata().getFinalizers().isEmpty());
  }
}
