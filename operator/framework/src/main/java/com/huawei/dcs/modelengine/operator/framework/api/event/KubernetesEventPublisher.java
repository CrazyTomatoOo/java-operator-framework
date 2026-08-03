/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.event;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Publishes Kubernetes Events for a resource.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public interface KubernetesEventPublisher {
    void normal(HasMetadata involvedObject, String reason, String message);

    void warning(HasMetadata involvedObject, String reason, String message);
}
