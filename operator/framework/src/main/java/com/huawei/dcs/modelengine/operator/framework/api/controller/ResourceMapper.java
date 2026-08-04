/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;

import io.fabric8.kubernetes.api.model.HasMetadata;

import java.util.Collection;

/**
 * Maps a secondary resource event to primary resource keys.
 *
 * @param <S> secondary resource type
 * @param <T> primary resource type
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@FunctionalInterface
public interface ResourceMapper<S extends HasMetadata, T extends HasMetadata> {
    /**
     * Maps a secondary resource event to the keys of affected primary resources.
     *
     * @param event the secondary resource event
     * @return the affected primary resource keys
     */
    Collection<ResourceKey> map(ResourceEvent<S> event);
}
