/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Converts {@code Greeting} objects between API versions {@code v1} and {@code v2}.
 *
 * <p>The CRD serves both versions but stores {@code v1}; the API server calls this webhook at
 * {@code /operator-framework/webhooks/convert/greetingconverter} whenever it must hand out a
 * version other than the stored one. The conversion moves the message between its v1 field name
 * ({@code spec.message}) and the v2 name ({@code spec.text}) and rewrites the apiVersion,
 * preserving identity and status.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@Component("greetingconverter")
public class GreetingConverter implements ResourceConverter<Greeting> {
    @Override
    public ConversionResult<Greeting> convert(Greeting resource, ConversionContext context) {
        var source = version(context.sourceVersion());
        var desired = version(context.desiredVersion());
        var converted = new Greeting();
        converted.setMetadata(new ObjectMetaBuilder(resource.getMetadata()).build());
        converted.setKind(resource.getKind());
        converted.setApiVersion(context.desiredVersion());
        converted.setStatus(resource.getStatus());
        if (source.equals(desired)) {
            converted.setSpec(resource.getSpec());
            return ConversionResult.converted(converted);
        }
        if (source.equals("v1")) {
            converted.setSpec(moveToV2(resource));
        } else {
            converted.setSpec(moveToV1(resource));
        }
        return ConversionResult.converted(converted);
    }

    private static GreetingSpec moveToV2(Greeting resource) {
        var spec = new GreetingSpec();
        spec.setText(message(resource));
        spec.setStyle(style(resource));
        return spec;
    }

    private static GreetingSpec moveToV1(Greeting resource) {
        var spec = new GreetingSpec();
        spec.setMessage(message(resource));
        spec.setStyle(style(resource));
        return spec;
    }

    private static String message(Greeting resource) {
        var spec = resource.getSpec();
        if (spec == null) {
            return null;
        }
        return spec.getText() != null ? spec.getText() : spec.getMessage();
    }

    private static String style(Greeting resource) {
        return resource.getSpec() == null ? null : resource.getSpec().getStyle();
    }

    private static String version(String apiVersion) {
        var separator = apiVersion.indexOf('/');
        return separator < 0 ? apiVersion : apiVersion.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}