package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.DisposableBean;

/** Closes only the Kubernetes client constructed by this framework. */
public final class FrameworkKubernetesClientOwnership implements DisposableBean {
    private KubernetesClient ownedClient;

    public synchronized void own(KubernetesClient client) {
        ownedClient = client;
    }

    public synchronized boolean owns(KubernetesClient client) {
        return ownedClient == client;
    }

    @Override
    public synchronized void destroy() {
        if (ownedClient != null) {
            ownedClient.close();
            ownedClient = null;
        }
    }
}
