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

/** Discovers typed webhook callbacks while retaining their original Spring proxies. */
public final class WebhookCallbackRegistry {
    private static final Pattern ROUTE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final ConfigurableListableBeanFactory beanFactory;
    private final Map<String, Callback> validators;
    private final Map<String, Callback> mutators;
    private final Map<String, Callback> converters;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

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

    public Optional<Callback> validator(String name) {
        return Optional.ofNullable(validators.get(name));
    }

    public Optional<Callback> mutator(String name) {
        return Optional.ofNullable(mutators.get(name));
    }

    public Optional<Callback> converter(String name) {
        return Optional.ofNullable(converters.get(name));
    }

    public int validatorCount() {
        return validators.size();
    }

    public int mutatorCount() {
        return mutators.size();
    }

    public int converterCount() {
        return converters.size();
    }

    public int callbackCount() {
        return validatorCount() + mutatorCount() + converterCount();
    }

    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure.get());
    }

    public void recordFailure(String type, String name) {
        lastFailure.set(type + " callback '" + name + "' failed");
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
        var resolved = resolve(declaredType(name), callbackType);
        if (resolved == null) {
            resolved = resolve(ResolvableType.forClass(AopUtils.getTargetClass(bean)), callbackType);
        }
        if (resolved == null) {
            throw new ApplicationContextException(
                    "webhook callback bean '" + name + "' has a raw or unresolved resource type");
        }
        return resolved;
    }

    private ResolvableType declaredType(String name) {
        if (beanFactory.containsBeanDefinition(name)) {
            return beanFactory.getMergedBeanDefinition(name).getResolvableType();
        }
        var type = beanFactory.getType(name, false);
        return type == null ? ResolvableType.NONE : ResolvableType.forClass(type);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends HasMetadata> resolve(ResolvableType type, Class<?> callbackType) {
        var genericType = type.as(callbackType);
        var resolved = genericType.getGeneric(0).resolve();
        if (genericType == ResolvableType.NONE || genericType.hasUnresolvableGenerics()) {
            return null;
        }
        if (!isConcreteResourceType(resolved)) {
            return null;
        }
        return (Class<? extends HasMetadata>) resolved;
    }

    private boolean isConcreteResourceType(Class<?> resolved) {
        return resolved != null && resolved != HasMetadata.class && HasMetadata.class.isAssignableFrom(resolved);
    }

    private void validateName(String name) {
        if (!ROUTE_NAME.matcher(name).matches()) {
            throw new ApplicationContextException(
                    "webhook callback bean name '" + name + "' is not a safe URL segment;"
                            + " Kubernetes webhook service paths require lowercase RFC 1123 segments");
        }
    }

    /** One named callback and its resolved resource type. */
    public record Callback(String name, Object bean, Class<? extends HasMetadata> resourceType) {
    }
}
