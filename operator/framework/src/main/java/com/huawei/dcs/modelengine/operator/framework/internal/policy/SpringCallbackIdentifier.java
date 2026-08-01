/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.policy;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpringCallbackIdentifier.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
/** Resolves callback type and bean-name tags; cached per target class and callback kind. */
final class SpringCallbackIdentifier {
    private record CacheKey(Class<?> targetClass, CallbackKind kind) {
    }

    private final ConfigurableListableBeanFactory beanFactory;
    private final ConcurrentHashMap<CacheKey, Identity> identities = new ConcurrentHashMap<>();

    SpringCallbackIdentifier(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    Identity identify(ProceedingJoinPoint joinPoint) {
        // ponytail: identity is fixed by target class + kind; cache skips the per-call bean-factory scan
        var key = new CacheKey(joinPoint.getTarget().getClass(), callbackKind(joinPoint));
        return identities.computeIfAbsent(key, ignored -> resolve(joinPoint));
    }

    int cacheSize() {
        return identities.size();
    }

    private Identity resolve(ProceedingJoinPoint joinPoint) {
        var callback = callbackKind(joinPoint);
        var name = beanName(callback.callbackInterface, joinPoint.getThis(), joinPoint.getTarget());
        return new Identity(callback.type, name);
    }

    private CallbackKind callbackKind(ProceedingJoinPoint joinPoint) {
        var signature = joinPoint.getSignature();
        return signature == null
                ? CallbackKind.fromTarget(joinPoint.getTarget())
                : CallbackKind.from(signature.getName());
    }

    private String beanName(Class<?> type, Object proxy, Object target) {
        for (var name : beanFactory.getBeanNamesForType(type, false, false)) {
            var bean = beanFactory.getBean(name);
            if (bean == proxy || bean == target) {
                return name;
            }
        }
        return target.getClass().getSimpleName();
    }

    private enum CallbackKind {
        RECONCILER("reconciler", Reconciler.class),
        VALIDATOR("validator", AdmissionValidator.class),
        MUTATOR("mutator", AdmissionMutator.class),
        CONVERTER("converter", ResourceConverter.class);

        private final String type;
        private final Class<?> callbackInterface;

        CallbackKind(String type, Class<?> callbackInterface) {
            this.type = type;
            this.callbackInterface = callbackInterface;
        }

        private static CallbackKind from(String methodName) {
            return switch (methodName) {
                case "reconcile" -> RECONCILER;
                case "validate" -> VALIDATOR;
                case "mutate" -> MUTATOR;
                case "convert" -> CONVERTER;
                default -> throw new IllegalArgumentException("Unsupported callback method: " + methodName);
            };
        }

        private static CallbackKind fromTarget(Object target) {
            if (target instanceof Reconciler<?>) {
                return RECONCILER;
            }
            if (target instanceof AdmissionValidator<?>) {
                return VALIDATOR;
            }
            if (target instanceof AdmissionMutator<?>) {
                return MUTATOR;
            }
            return CONVERTER;
        }
    }

    record Identity(String type, String bean) {
    }
}
