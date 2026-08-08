/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.webhook;

import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;

import io.fabric8.kubernetes.api.model.HasMetadata;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextException;
import org.springframework.core.ResolvableType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Discovers typed webhook callbacks while retaining their original Spring proxies.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public final class WebhookCallbackRegistry {
    private static final Pattern ROUTE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final ConfigurableListableBeanFactory beanFactory;

    private final Map<String, Callback> validators;

    private final Map<String, Callback> mutators;

    private final Map<String, Callback> converters;

    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    /**
     * Discovers all validator, mutator, and converter beans and indexes them by route name.
     *
     * @param beanFactory the bean factory to discover callback beans from
     * @throws ApplicationContextException when no callback bean exists, a bean name is not a safe URL
     *     segment, a route name is duplicated, or a callback's resource type cannot be resolved
     */
    public WebhookCallbackRegistry(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        validators = discover(AdmissionValidator.class);
        mutators = discover(AdmissionMutator.class);
        converters = discover(ResourceConverter.class);
        if (callbackCount() == 0) {
            throw new ApplicationContextException(
                "webhook mode requires an AdmissionValidator, AdmissionMutator, or ResourceConverter bean");
        }
    }

    /**
     * Returns the total number of registered webhook callbacks of all types.
     *
     * @return the validator, mutator, and converter counts combined
     */
    public int callbackCount() {
        return validatorCount() + mutatorCount() + converterCount();
    }

    /**
     * Returns the number of registered validator callbacks.
     *
     * @return the validator callback count
     */
    public int validatorCount() {
        return validators.size();
    }

    /**
     * Returns the number of registered mutator callbacks.
     *
     * @return the mutator callback count
     */
    public int mutatorCount() {
        return mutators.size();
    }

    /**
     * Returns the number of registered converter callbacks.
     *
     * @return the converter callback count
     */
    public int converterCount() {
        return converters.size();
    }

    private Map<String, Callback> discover(Class<?> callbackType) {
        var callbacks = new LinkedHashMap<String, Callback>();
        for (var name : beanFactory.getBeanNamesForType(callbackType, false, false)) {
            validateName(name);
            var bean = beanFactory.getBean(name);
            var resourceType = resolveResourceType(name, bean, callbackType);
            if (callbacks.putIfAbsent(name, new Callback(name, bean, resourceType)) != null) {
                throw new ApplicationContextException("duplicate webhook route name '" + name + "'");
            }
        }
        return Map.copyOf(callbacks);
    }

    private Class<? extends HasMetadata> resolveResourceType(String name, Object bean, Class<?> callbackType) {
        return resolve(declaredType(name), callbackType).or(
                () -> resolve(ResolvableType.forClass(AopUtils.getTargetClass(bean)), callbackType))
            .orElseThrow(() -> new ApplicationContextException(
                "webhook callback bean '" + name + "' has a raw or unresolved resource type"));
    }

    private ResolvableType declaredType(String name) {
        if (beanFactory.containsBeanDefinition(name)) {
            return beanFactory.getMergedBeanDefinition(name).getResolvableType();
        }
        var type = beanFactory.getType(name, false);
        return type == null ? ResolvableType.NONE : ResolvableType.forClass(type);
    }

    @SuppressWarnings("unchecked")
    private Optional<Class<? extends HasMetadata>> resolve(ResolvableType type, Class<?> callbackType) {
        var genericType = type.as(callbackType);
        var resolved = genericType.getGeneric(0).resolve();
        if (genericType == ResolvableType.NONE || genericType.hasUnresolvableGenerics()) {
            return Optional.empty();
        }
        if (!isConcreteResourceType(resolved)) {
            return Optional.empty();
        }
        return Optional.of((Class<? extends HasMetadata>) resolved);
    }

    private boolean isConcreteResourceType(Class<?> resolved) {
        return resolved != null && resolved != HasMetadata.class && HasMetadata.class.isAssignableFrom(resolved);
    }

    private void validateName(String name) {
        if (!ROUTE_NAME.matcher(name).matches()) {
            throw new ApplicationContextException("webhook callback bean name '" + name + "' is not a safe URL segment;"
                + " Kubernetes webhook service paths require lowercase RFC 1123 segments");
        }
    }

    /**
     * Finds the validator callback registered under a route name.
     *
     * @param name the callback bean name from the webhook request path
     * @return the matching validator callback, or empty when none is registered under {@code name}
     */
    public Optional<Callback> validator(String name) {
        return Optional.ofNullable(validators.get(name));
    }

    /**
     * Finds the mutator callback registered under a route name.
     *
     * @param name the callback bean name from the webhook request path
     * @return the matching mutator callback, or empty when none is registered under {@code name}
     */
    public Optional<Callback> mutator(String name) {
        return Optional.ofNullable(mutators.get(name));
    }

    /**
     * Finds the converter callback registered under a route name.
     *
     * @param name the callback bean name from the webhook request path
     * @return the matching converter callback, or empty when none is registered under {@code name}
     */
    public Optional<Callback> converter(String name) {
        return Optional.ofNullable(converters.get(name));
    }

    /**
     * Returns the most recently recorded callback failure, if any.
     *
     * @return the last failure description, or empty when no callback has failed
     */
    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    /**
     * Records a callback failure so it can be surfaced through health reporting.
     *
     * @param type the callback type, such as {@code validator}, {@code mutator}, or {@code converter}
     * @param name the callback bean name that failed
     */
    public void recordFailure(String type, String name) {
        lastFailure.set(type + " callback '" + name + "' failed");
    }

    /**
     * One named callback and its resolved resource type.
     *
     * @param name callback bean name
     * @param bean callback bean
     * @param resourceType resolved resource type
     */
    public record Callback(String name, Object bean, Class<? extends HasMetadata> resourceType) {}
}
