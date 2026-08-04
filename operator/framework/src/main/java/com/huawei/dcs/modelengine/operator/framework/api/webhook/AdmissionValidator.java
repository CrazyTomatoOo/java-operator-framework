/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Validates an admission request without depending on a transport protocol.
 *
 * @param <T> resource type
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@FunctionalInterface
public interface AdmissionValidator<T extends HasMetadata> {
    /**
     * Validates the current resource for an admission request.
     *
     * @param current the current resource state
     * @param context the admission context
     * @return the admission decision
     * @throws Exception if validation fails
     */
    AdmissionDecision validate(T current, AdmissionContext context) throws Exception;
}
