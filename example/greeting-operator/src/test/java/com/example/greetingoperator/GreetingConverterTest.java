/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.greetingoperator;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionContext;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;

import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link GreetingConverter} logic in isolation: the v1/v2 field move, the
 * apiVersion rewrite, and identity/status preservation.
 *
 * <p>The framework-served conversion route is exercised against built-in kinds (see the
 * framework's own {@code ConversionWebhookControllerTest}); binding a custom {@code Greeting}
 * on the server side currently fails in the fabric8 7.x deserializer (known limitation, see the
 * module README), so the callback itself is tested directly here.
 */
class GreetingConverterTest {
    private final GreetingConverter converter = new GreetingConverter();

    @Test
    void movesMessageFromV1ToV2() {
        var source = v1Greeting("hello");

        var result = converter.convert(source,
                new ConversionContext("greetings.example.com/v1", "greetings.example.com/v2"));

        assertThat(result.isConverted()).isTrue();
        var converted = result.resource().orElseThrow();
        assertThat(converted.getApiVersion()).isEqualTo("greetings.example.com/v2");
        assertThat(converted.getSpec().getText()).isEqualTo("hello");
        assertThat(converted.getSpec().getMessage()).isNull();
        assertThat(converted.getMetadata().getName()).isEqualTo("greet-1");
        assertThat(converted.getStatus()).isSameAs(source.getStatus());
    }

    @Test
    void movesMessageFromV2ToV1KeepingStyle() {
        var source = v2Greeting("bonjour");
        source.getSpec().setStyle("fancy");
        source.setStatus(status("Rendered"));

        var result = converter.convert(source,
                new ConversionContext("greetings.example.com/v2", "greetings.example.com/v1"));

        var converted = result.resource().orElseThrow();
        assertThat(converted.getApiVersion()).isEqualTo("greetings.example.com/v1");
        assertThat(converted.getSpec().getMessage()).isEqualTo("bonjour");
        assertThat(converted.getSpec().getText()).isNull();
        assertThat(converted.getSpec().getStyle()).isEqualTo("fancy");
        assertThat(converted.getStatus().getPhase()).isEqualTo("Rendered");
    }

    @Test
    void passesThroughWhenVersionsMatch() {
        var source = v1Greeting("hi");

        var result = converter.convert(source,
                new ConversionContext("greetings.example.com/v1", "greetings.example.com/v1"));

        var converted = result.resource().orElseThrow();
        assertThat(converted.getSpec().getMessage()).isEqualTo("hi");
        assertThat(converted.getApiVersion()).isEqualTo("greetings.example.com/v1");
    }

    @Test
    void toleratesMissingSpec() {
        var source = new Greeting();
        source.setMetadata(new ObjectMetaBuilder()
                .withName("greet-1").withNamespace("ns").withUid("uid-1").build());

        var result = converter.convert(source,
                new ConversionContext("greetings.example.com/v1", "greetings.example.com/v2"));

        assertThat(result.resource()).hasValueSatisfying(converted -> {
            assertThat(converted.getSpec().getText()).isNull();
            assertThat(converted.getSpec().getStyle()).isNull();
        });
    }

    private static Greeting v1Greeting(String message) {
        var greeting = new Greeting();
        greeting.setMetadata(new ObjectMetaBuilder()
                .withName("greet-1").withNamespace("ns").withUid("uid-1").build());
        var spec = new GreetingSpec();
        spec.setMessage(message);
        greeting.setSpec(spec);
        return greeting;
    }

    private static Greeting v2Greeting(String text) {
        var greeting = new Greeting();
        greeting.setApiVersion("greetings.example.com/v2");
        greeting.setMetadata(new ObjectMetaBuilder()
                .withName("greet-1").withNamespace("ns").withUid("uid-1").build());
        var spec = new GreetingSpec();
        spec.setText(text);
        greeting.setSpec(spec);
        return greeting;
    }

    private static GreetingStatus status(String phase) {
        var status = new GreetingStatus();
        status.setPhase(phase);
        return status;
    }
}