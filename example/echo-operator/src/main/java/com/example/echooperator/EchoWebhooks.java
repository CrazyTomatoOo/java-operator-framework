/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.echooperator;

import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.MutationResult;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

import org.springframework.stereotype.Component;

/**
 * Admission callbacks served by the framework at
 * {@code /operator-framework/webhooks/{validate|mutate}/{beanName}}.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
final class EchoWebhooks {
    private EchoWebhooks() {
    }

    @Component("echovalidator")
    static class EchoValidator implements AdmissionValidator<ConfigMap> {
        /**
         * Rejects echo-enabled ConfigMaps whose {@code data.message} is blank.
         *
         * @param current the ConfigMap under admission
         * @param context the admission context
         * @return allow when the ConfigMap is not echo-enabled or carries a message, otherwise a denial
         */
        @Override
        public AdmissionDecision validate(ConfigMap current, AdmissionContext context) {
            if (!EchoReconciler.isEnabled(current)) {
                return AdmissionDecision.allow();
            }
            var message = EchoReconciler.message(current);
            return message == null || message.isBlank()
                    ? AdmissionDecision.deny("data.message must not be blank on echo ConfigMaps")
                    : AdmissionDecision.allow();
        }
    }

    @Component("echomutator")
    static class EchoMutator implements AdmissionMutator<ConfigMap> {
        /**
         * Defaults {@code data.message} to {@code "hello world"} on echo-enabled ConfigMaps that lack one.
         *
         * @param current the ConfigMap under admission
         * @param context the admission context
         * @return a mutated copy with the default message, or unchanged when the default does not apply
         */
        @Override
        public MutationResult<ConfigMap> mutate(ConfigMap current, AdmissionContext context) {
            if (!EchoReconciler.isEnabled(current) || EchoReconciler.message(current) != null) {
                return MutationResult.unchanged();
            }
            return MutationResult.mutated(new ConfigMapBuilder(current)
                    .addToData(EchoReconciler.MESSAGE_KEY, "hello world")
                    .build());
        }
    }
}
