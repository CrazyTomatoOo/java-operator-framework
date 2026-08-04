/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.reconcile;

import io.fabric8.kubernetes.api.model.HasMetadata;

/**
 * A dependent resource managed on behalf of a primary resource.
 *
 * <p>Implementations compute the full desired state of one owned resource from the primary.
 * Submit the result through {@link Dependents#apply}, which marks it with the controller owner
 * reference and server-side-applies it. Declare the dependent on the controller with
 * {@code ControllerBuilder.manages(dependent)} so its events also trigger reconciliation.
 *
 * @param <D> dependent resource type
 * @param <P> primary resource type
 * @author z00919064 zhangshijie
 * @since 2026-08-01
 */
public interface DependentResource<D extends HasMetadata, P extends HasMetadata> {
    /**
     * Type of the dependent resource used to register the owned watch.
     *
     * @return the dependent resource type
     */
    Class<D> resourceType();

    /**
     * Computes the desired state of the dependent from the primary. The returned object must be
     * freshly built and must carry the target namespace/name; {@link Dependents#apply} adds the
     * owner reference. May read related resources from {@code context.cache()} /
     * {@code context.cacheFor(Class)} instead of the API server.
     *
     * @param primary the primary resource
     * @param context the reconciliation context
     * @return the desired state of the dependent resource
     */
    D desired(P primary, ReconciliationContext<P> context);
}
