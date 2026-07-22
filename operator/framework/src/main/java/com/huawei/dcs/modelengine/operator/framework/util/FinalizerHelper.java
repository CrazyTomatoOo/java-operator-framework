package com.huawei.dcs.modelengine.operator.framework.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FinalizerHelper {

  private FinalizerHelper() {
  }

  public static boolean hasFinalizer(HasMetadata resource, String finalizer) {
    Objects.requireNonNull(resource, "resource must not be null");
    Objects.requireNonNull(finalizer, "finalizer must not be null");

    ObjectMeta metadata = resource.getMetadata();
    List<String> finalizers = metadata == null ? null : metadata.getFinalizers();
    return finalizers != null && finalizers.contains(finalizer);
  }

  public static boolean addFinalizer(HasMetadata resource, String finalizer) {
    Objects.requireNonNull(resource, "resource must not be null");
    Objects.requireNonNull(finalizer, "finalizer must not be null");

    ObjectMeta metadata = ensureMetadata(resource);
    List<String> finalizers = metadata.getFinalizers();
    if (finalizers == null) {
      finalizers = new ArrayList<>();
      metadata.setFinalizers(finalizers);
    }
    if (finalizers.contains(finalizer)) {
      return false;
    }
    finalizers.add(finalizer);
    return true;
  }

  public static boolean removeFinalizer(HasMetadata resource, String finalizer) {
    Objects.requireNonNull(resource, "resource must not be null");
    Objects.requireNonNull(finalizer, "finalizer must not be null");

    ObjectMeta metadata = resource.getMetadata();
    if (metadata == null || metadata.getFinalizers() == null) {
      return false;
    }

    boolean removed = metadata.getFinalizers().removeIf(finalizer::equals);
    if (metadata.getFinalizers().isEmpty()) {
      metadata.setFinalizers(null);
    }
    return removed;
  }

  private static ObjectMeta ensureMetadata(HasMetadata resource) {
    ObjectMeta metadata = resource.getMetadata();
    if (metadata == null) {
      metadata = new ObjectMeta();
      resource.setMetadata(metadata);
    }
    return metadata;
  }
}
