/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

/**
 * Role played by the triggering resource in a controller.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public enum TriggerRole {
    PRIMARY,
    OWNED,
    WATCHED,
    KUBERNETES_EVENT
}
