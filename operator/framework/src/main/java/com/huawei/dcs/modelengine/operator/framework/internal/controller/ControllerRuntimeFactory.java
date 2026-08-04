/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

/**
 * Factory that creates a controller runtime for every runtime instance.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public interface ControllerRuntimeFactory {
    /**
     * Creates a controller runtime.
     *
     * @return a new controller runtime
     */
    ControllerRuntime create();
}
