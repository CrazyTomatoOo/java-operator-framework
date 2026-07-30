package com.huawei.dcs.modelengine.operator.framework.api.event;

import io.fabric8.kubernetes.api.model.HasMetadata;

/** Publishes Kubernetes Events for a resource. */
public interface KubernetesEventPublisher {
    void normal(HasMetadata involvedObject, String reason, String message);

    void warning(HasMetadata involvedObject, String reason, String message);
}
