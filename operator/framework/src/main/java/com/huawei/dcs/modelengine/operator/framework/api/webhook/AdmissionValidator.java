/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * Validates an admission request without depending on a transport protocol.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
@FunctionalInterface
public interface AdmissionValidator<T extends HasMetadata> {
    AdmissionDecision validate(T current, AdmissionContext context) throws Exception;
}
