package com.huawei.dcs.modelengine.operator.framework.util;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;

import java.util.Objects;

public final class OwnerReferenceHelper {

  private OwnerReferenceHelper() {
  }

  public static OwnerReference createControllerOwnerReference(HasMetadata owner) {
    Objects.requireNonNull(owner, "owner must not be null");
    Objects.requireNonNull(owner.getMetadata(), "owner metadata must not be null");

    return new OwnerReferenceBuilder()
        .withApiVersion(owner.getApiVersion())
        .withKind(owner.getKind())
        .withName(owner.getMetadata().getName())
        .withUid(owner.getMetadata().getUid())
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build();
  }
}
