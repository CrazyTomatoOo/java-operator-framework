/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Finalizers;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Dependents;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.StatusUpdates;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Reconciles a {@link Greeting} into a rendered child ConfigMap, an external state ConfigMap,
 * and its status subresource.
 *
 * <p>The reconciler exercises the full managed-dependent write path:
 * <ul>
 *   <li>{@link Finalizers#add}/{@link Finalizers#remove} guard the deletion of the external
 *       state ConfigMap, which carries no owner reference and would otherwise leak;</li>
 *   <li>{@link Dependents#apply} computes the child from {@link GreetingConfigMap} and
 *       server-side-applies it under a stable field manager;</li>
 *   <li>{@link StatusUpdates#update} persists {@code status.observedGeneration} plus the
 *       rendered message through the {@code /status} subresource.</li>
 * </ul>
 *
 * <p>{@code ReconcileResult.requeueNow()} is used twice: right after adding the finalizer
 * (its patch must land in the informer store before the next attempt reads it) and while the
 * applied child has not yet appeared in the owned ConfigMap cache. Unhandled exceptions are
 * left to the framework, which retries them with the configured {@code operator.framework.retry.*}
 * policy.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@Component
public class GreetingReconciler implements Reconciler<Greeting> {
    static final String FINALIZER = "greetings.example.com/finalizer";

    static final String STYLE_LABEL = "greetings.example.com/primary";

    static final String FIELD_MANAGER = "greeting-operator";

    private static final String STYLE_PREFIX_KEY = "prefix";

    private static final String EXTERNAL_SUFFIX = "-external";

    private static final String CHILD_SUFFIX = "-child";

    private static final String RENDERED = "Rendered";

    private final KubernetesClient client;

    private final KubernetesEventPublisher events;

    private final GreetingConfigMap child;

    /**
     * Creates a greeting reconciler.
     *
     * @param client the Kubernetes client used for finalizer patches, status writes, and the
     *     external state ConfigMap
     * @param events the publisher used for Kubernetes Events
     * @param child the managed dependent that computes the rendered child ConfigMap
     */
    GreetingReconciler(KubernetesClient client, KubernetesEventPublisher events, GreetingConfigMap child) {
        this.client = client;
        this.events = events;
        this.child = child;
    }

    /**
     * Renders the greeting. Handles three phases:
     * <ol>
     *   <li>deletion: deletes the external state ConfigMap and removes the finalizer;</li>
     *   <li>first sighting: adds the finalizer and requeues immediately;</li>
     *   <li>steady state: applies the child, persists the status, and reports a Kubernetes Event.</li>
     * </ol>
     *
     * @param resource the greeting under reconciliation
     * @param context the reconciliation context carrying the primary and secondary caches
     * @return {@code done} when the resource is converged, otherwise an immediate requeue
     */
    @Override
    public ReconcileResult reconcile(Greeting resource, ReconciliationContext<Greeting> context) {
        if (Finalizers.isDeleting(resource)) {
            return cleanUp(resource);
        }
        if (!Finalizers.present(resource, FINALIZER)) {
            Finalizers.add(client, resource, FINALIZER);
            return ReconcileResult.requeueNow();
        }
        return render(resource, context);
    }

    /**
     * Computes the rendered message: the greeting's message text prefixed by the configured
     * style, read from the styles ConfigMap cache.
     *
     * @param primary the greeting
     * @param context the reconciliation context
     * @return the rendered message
     */
    static String renderedMessage(Greeting primary, ReconciliationContext<Greeting> context) {
        var spec = primary.getSpec();
        var message = spec == null ? null : spec.getMessage();
        return prefix(primary, context) + (message == null ? "" : message);
    }

    private ReconcileResult render(Greeting resource, ReconciliationContext<Greeting> context) {
        var namespace = resource.getMetadata().getNamespace();
        var childName = resource.getMetadata().getName() + CHILD_SUFFIX;

        Dependents.apply(client, child, resource, context, FIELD_MANAGER);
        if (context.cacheFor(ConfigMap.class).getByKey(namespace + "/" + childName) == null) {
            return ReconcileResult.requeueNow();
        }

        var rendered = renderedMessage(resource, context);
        ensureExternalState(resource, rendered);
        persistStatus(resource, rendered);
        events.normal(resource, RENDERED,
                "Rendered message into ConfigMap " + childName + ", external state synchronized");
        return ReconcileResult.done();
    }

    private ReconcileResult cleanUp(Greeting resource) {
        var namespace = resource.getMetadata().getNamespace();
        var external = resource.getMetadata().getName() + EXTERNAL_SUFFIX;
        if (client.configMaps().inNamespace(namespace).withName(external).get() != null) {
            client.configMaps().inNamespace(namespace).withName(external).delete();
            events.normal(resource, "Cleaned", "Deleted external state ConfigMap " + external);
        }
        Finalizers.remove(client, resource, FINALIZER);
        return ReconcileResult.done();
    }

    private void ensureExternalState(Greeting resource, String rendered) {
        var namespace = resource.getMetadata().getNamespace();
        var external = resource.getMetadata().getName() + EXTERNAL_SUFFIX;
        var desired = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(external)
                .withNamespace(namespace)
                .endMetadata()
                .addToData("message", rendered)
                .build();
        var existing = client.configMaps().inNamespace(namespace).withName(external).get();
        if (existing == null) {
            client.configMaps().inNamespace(namespace).resource(desired).create();
        } else if (!Objects.equals(existing.getData(), desired.getData())) {
            client.configMaps().inNamespace(namespace).resource(desired).update();
        }
    }

    private void persistStatus(Greeting resource, String rendered) {
        var status = new GreetingStatus();
        status.setObservedGeneration(resource.getMetadata().getGeneration());
        status.setPhase(RENDERED);
        status.setMessage(rendered);
        StatusUpdates.update(client, resource, status);
    }

    private static String prefix(Greeting primary, ReconciliationContext<Greeting> context) {
        var spec = primary.getSpec();
        var style = spec == null ? null : spec.getStyle();
        if (style == null || style.isBlank()) {
            return "";
        }
        var styles = context.cacheFor(ConfigMap.class)
                .getByKey(primary.getMetadata().getNamespace() + "/" + style);
        if (styles == null) {
            return "";
        }
        var data = styles.getData();
        return data == null ? "" : data.getOrDefault(STYLE_PREFIX_KEY, "");
    }
}