/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

/**
 * Factory that creates a controller runtime for every runtime term.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public interface ControllerRuntimeFactory {
    ControllerRuntime create();
}
