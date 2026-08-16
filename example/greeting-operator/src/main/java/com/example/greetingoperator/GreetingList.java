/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import io.fabric8.kubernetes.api.model.DefaultKubernetesResourceList;

/**
 * List of {@link Greeting} resources, required by the fabric8 client for typed list/watch
 * operations on the custom resource.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public class GreetingList extends DefaultKubernetesResourceList<Greeting> {
}