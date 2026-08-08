/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.discovery;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;

import io.fabric8.kubernetes.api.model.HasMetadata;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers explicit registrations and typed reconciler beans without unwrapping proxies for invocation.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class ControllerRegistrationDiscovery {
    private final ConfigurableListableBeanFactory beanFactory;

    /**
     * Creates the discovery over the given bean factory.
     *
     * @param beanFactory the Spring bean factory to scan for controller beans
     */
    public ControllerRegistrationDiscovery(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Discovers controller registrations from explicit beans and typed reconciler beans.
     *
     * @return the discovered controller registrations, one per resource type
     * @throws IllegalStateException when no controller is found, a resource type is duplicated,
     *         a reconciler is a lambda, or a resource type cannot be resolved
     */
    public List<ControllerRegistration<?>> discover() {
        var explicit = explicitRegistrations();
        var explicitTypes = Set.copyOf(explicit.keySet());
        var registeredReconcilers = registeredReconcilers(explicit);
        addDiscoveredReconcilers(explicit, explicitTypes, registeredReconcilers);
        if (explicit.isEmpty()) {
            throw new IllegalStateException("controller mode requires a Reconciler or ControllerRegistration bean");
        }
        return explicit.values().stream()
                .<ControllerRegistration<?>>map(NamedRegistration::registration)
                .toList();
    }

    private Map<Class<?>, NamedRegistration> explicitRegistrations() {
        var registrations = new LinkedHashMap<Class<?>, NamedRegistration>();
        for (var name : beanFactory.getBeanNamesForType(ControllerRegistration.class, false, false)) {
            var registration = beanFactory.getBean(name, ControllerRegistration.class);
            requireSpringBean(name, registration.reconciler());
            put(registrations, registration.resourceType(), new NamedRegistration(name, registration));
        }
        return registrations;
    }

    private IdentityHashMap<Object, Boolean> registeredReconcilers(Map<Class<?>, NamedRegistration> registrations) {
        var reconcilers = new IdentityHashMap<Object, Boolean>();
        registrations.values().forEach(entry -> reconcilers.put(entry.registration().reconciler(), Boolean.TRUE));
        return reconcilers;
    }

    private void addDiscoveredReconcilers(
            Map<Class<?>, NamedRegistration> registrations,
            Set<Class<?>> explicitTypes,
            IdentityHashMap<Object, Boolean> registeredReconcilers) {
        for (var name : beanFactory.getBeanNamesForType(Reconciler.class, false, false)) {
            var reconciler = beanFactory.getBean(name, Reconciler.class);
            if (registeredReconcilers.containsKey(reconciler)) {
                continue;
            }
            var resourceType = resolveResourceType(name, reconciler);
            if (!explicitTypes.contains(resourceType)) {
                put(registrations, resourceType, autoRegistration(name, resourceType, reconciler));
            }
        }
    }

    private void requireSpringBean(String registrationName, Reconciler<?> reconciler) {
        for (var name : beanFactory.getBeanNamesForType(Reconciler.class, false, false)) {
            if (beanFactory.getBean(name) == reconciler) {
                return;
            }
        }
        throw new IllegalStateException("ControllerRegistration bean '" + registrationName
                + "' references a reconciler that is not a Spring bean");
    }

    private Class<? extends HasMetadata> resolveResourceType(String beanName, Reconciler<?> reconciler) {
        var targetType = AopUtils.getTargetClass(reconciler);
        if (targetType.isSynthetic()) {
            throw new IllegalStateException("Reconciler bean '" + beanName
                    + "' is a lambda; use a concrete class or explicit ControllerRegistration");
        }
        var definitionType = beanFactory.getMergedBeanDefinition(beanName).getResolvableType();
        return resolveResourceType(definitionType)
                .or(() -> resolveResourceType(ResolvableType.forClass(targetType)))
                .orElseThrow(() -> new IllegalStateException(
                        "Reconciler bean '" + beanName + "' has an unresolved resource type"));
    }

    @SuppressWarnings("unchecked")
    private Optional<Class<? extends HasMetadata>> resolveResourceType(ResolvableType type) {
        var reconcilerType = type.as(Reconciler.class);
        var resolved = reconcilerType.getGeneric(0).resolve();
        if (reconcilerType.hasUnresolvableGenerics() || resolved == HasMetadata.class
                || resolved == null || !HasMetadata.class.isAssignableFrom(resolved)) {
            return Optional.empty();
        }
        return Optional.of((Class<? extends HasMetadata>) resolved);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NamedRegistration autoRegistration(
            String beanName,
            Class<? extends HasMetadata> resourceType,
            Reconciler<?> reconciler) {
        var registration = ControllerBuilder.forResource((Class) resourceType, (Reconciler) reconciler).build();
        return new NamedRegistration(beanName, registration);
    }

    private void put(
            Map<Class<?>, NamedRegistration> registrations,
            Class<?> resourceType,
            NamedRegistration candidate) {
        var previous = registrations.putIfAbsent(resourceType, candidate);
        if (previous != null) {
            throw new IllegalStateException("duplicate controller resource " + resourceType.getName() + " in beans '"
                    + previous.beanName() + "' and '" + candidate.beanName() + "'");
        }
    }

    private record NamedRegistration(String beanName, ControllerRegistration<?> registration) {
    }
}
