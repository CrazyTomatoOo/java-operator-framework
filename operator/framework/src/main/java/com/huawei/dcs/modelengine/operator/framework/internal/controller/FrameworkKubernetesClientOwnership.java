/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import io.fabric8.kubernetes.client.KubernetesClient;

import org.springframework.beans.factory.DisposableBean;

/**
 * Closes only the Kubernetes client constructed by this framework.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class FrameworkKubernetesClientOwnership implements DisposableBean {
    private KubernetesClient ownedClient;

    /**
     * Marks the given client as constructed and owned by this framework.
     *
     * @param client the framework-constructed Kubernetes client
     */
    public synchronized void own(KubernetesClient client) {
        ownedClient = client;
    }

    /**
     * Reports whether the given client is the one owned by this framework.
     *
     * @param client the Kubernetes client to check
     * @return {@code true} when the framework owns the given client
     */
    public synchronized boolean owns(KubernetesClient client) {
        return ownedClient == client;
    }

    /**
     * Closes the owned client, if any, and releases the ownership.
     */
    @Override
    public synchronized void destroy() {
        if (ownedClient != null) {
            ownedClient.close();
            ownedClient = null;
        }
    }
}
