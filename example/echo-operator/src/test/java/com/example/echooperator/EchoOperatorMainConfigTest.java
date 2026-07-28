package com.example.echooperator;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EchoOperatorMainConfigTest {

    @Test
    void loadConfigUsesDefaultsForNewWebhookSettings() {
        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(Map.of(), new Properties());

        assertEquals(true, config.webhookEnabled());
        assertEquals(true, config.webhookCertAutoGenerate());
        assertEquals("echo-operator-webhook-ca", config.webhookCertSecretName());
        assertEquals("echo-operator", config.webhookServiceName());
        assertEquals("default", config.operatorPodNamespace());
        assertEquals("default", config.webhookServiceNamespace());
    }

    @Test
    void loadConfigReadsCustomEnvAndPropertyValues() {
        Map<String, String> env = Map.of(
                "OPERATOR_NAMESPACE", "watched",
                "OPERATOR_POD_NAMESPACE", "release-ns",
                "WEBHOOK_ENABLED", "false",
                "WEBHOOK_CERT_AUTO_GENERATE", "false");
        Properties defaults = new Properties();
        defaults.setProperty("webhook.cert.secret-name", "custom-secret");
        defaults.setProperty("webhook.service.name", "custom-service");
        defaults.setProperty("webhook.service.namespace", "custom-service-ns");

        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(env, defaults);

        assertEquals("watched", config.operatorNamespace());
        assertEquals("release-ns", config.operatorPodNamespace());
        assertEquals(false, config.webhookEnabled());
        assertEquals(false, config.webhookCertAutoGenerate());
        assertEquals("custom-secret", config.webhookCertSecretName());
        assertEquals("custom-service", config.webhookServiceName());
        assertEquals("custom-service-ns", config.webhookServiceNamespace());
    }

    @Test
    void loadConfigDefaultsWebhookServiceNamespaceToOperatorPodNamespace() {
        Map<String, String> env = Map.of(
                "OPERATOR_NAMESPACE", "watched",
                "OPERATOR_POD_NAMESPACE", "release-ns");

        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(env, new Properties());

        assertEquals("watched", config.operatorNamespace());
        assertEquals("release-ns", config.operatorPodNamespace());
        assertEquals("release-ns", config.webhookServiceNamespace());
    }

    @Test
    void loadConfigRejectsBlankEnvValues() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of("WEBHOOK_CERT_SECRET_NAME", ""), new Properties()));

        assertEquals("WEBHOOK_CERT_SECRET_NAME must not be blank", exception.getMessage());
    }

    @Test
    void loadConfigRejectsBlankPropertyValues() {
        Properties defaults = new Properties();
        defaults.setProperty("webhook.cert.secret-name", "");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of(), defaults));

        assertEquals("webhook.cert.secret-name must not be blank", exception.getMessage());
    }

    @Test
    void loadConfigRejectsInvalidWebhookEnabledEnvValue() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of("WEBHOOK_ENABLED", "yes"), new Properties()));

        assertEquals("WEBHOOK_ENABLED must be true or false: yes", exception.getMessage());
    }

    @Test
    void loadConfigRejectsInvalidWebhookCertAutoGeneratePropertyValue() {
        Properties defaults = new Properties();
        defaults.setProperty("webhook.cert.auto-generate", "maybe");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of(), defaults));

        assertEquals("webhook.cert.auto-generate must be true or false: maybe", exception.getMessage());
    }

    @Test
    void loadConfigDefaultsPerWebhookEnabledToTrue() {
        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(Map.of(), new Properties());

        assertEquals(true, config.webhookValidatingEnabled());
        assertEquals(true, config.webhookMutatingEnabled());
        assertEquals(true, config.webhookConversionEnabled());
    }

    @Test
    void loadConfigReadsPerWebhookEnabledFromEnv() {
        Map<String, String> env = Map.of(
                "WEBHOOK_VALIDATING_ENABLED", "false",
                "WEBHOOK_MUTATING_ENABLED", "false",
                "WEBHOOK_CONVERSION_ENABLED", "false");

        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(env, new Properties());

        assertEquals(false, config.webhookValidatingEnabled());
        assertEquals(false, config.webhookMutatingEnabled());
        assertEquals(false, config.webhookConversionEnabled());
    }

    @Test
    void loadConfigRejectsInvalidWebhookValidatingEnabledValue() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of("WEBHOOK_VALIDATING_ENABLED", "yes"), new Properties()));

        assertEquals("WEBHOOK_VALIDATING_ENABLED must be true or false: yes", exception.getMessage());
    }

    @Test
    void loadConfigDefaultsTaskOneCapabilities() {
        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(Map.of(), new Properties());

        assertEquals(true, config.controllerEnabled());
        assertEquals(true, config.webhookEnabled());
        assertEquals(true, config.webhookRegistrationCleanupEnabled());
        assertEquals(true, config.webhookSelfRegistrationEnabled());
        assertNull(config.webhookPredecessorValidatingName());
        assertNull(config.webhookPredecessorMutatingName());
    }

    @Test
    void loadConfigReadsTaskOneValuesFromEnvironmentAndProperties() {
        Map<String, String> env = Map.of(
                "CONTROLLER_ENABLED", "false",
                "WEBHOOK_REGISTRATION_CLEANUP_ENABLED", "false",
                "WEBHOOK_SELF_REGISTRATION_ENABLED", "false",
                "WEBHOOK_PREDECESSOR_VALIDATING_NAME", "env-validating");
        Properties defaults = new Properties();
        defaults.setProperty("webhook.predecessor.mutating.name", "property-mutating");

        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(env, defaults);

        assertEquals(false, config.controllerEnabled());
        assertEquals(false, config.webhookRegistrationCleanupEnabled());
        assertEquals(false, config.webhookSelfRegistrationEnabled());
        assertEquals("env-validating", config.webhookPredecessorValidatingName());
        assertEquals("property-mutating", config.webhookPredecessorMutatingName());
    }

    @Test
    void loadConfigPrefersTaskOneEnvironmentValuesOverProperties() {
        Map<String, String> env = Map.of(
                "CONTROLLER_ENABLED", "true",
                "WEBHOOK_REGISTRATION_CLEANUP_ENABLED", "true",
                "WEBHOOK_SELF_REGISTRATION_ENABLED", "true",
                "WEBHOOK_PREDECESSOR_VALIDATING_NAME", "env-validating",
                "WEBHOOK_PREDECESSOR_MUTATING_NAME", "env-mutating");
        Properties defaults = new Properties();
        defaults.setProperty("controller.enabled", "false");
        defaults.setProperty("webhook.registration.cleanup.enabled", "false");
        defaults.setProperty("webhook.self-registration.enabled", "false");
        defaults.setProperty("webhook.predecessor.validating.name", "property-validating");
        defaults.setProperty("webhook.predecessor.mutating.name", "property-mutating");

        EchoOperatorMain.OperatorConfig config = EchoOperatorMain.loadConfig(env, defaults);

        assertEquals(true, config.controllerEnabled());
        assertEquals(true, config.webhookRegistrationCleanupEnabled());
        assertEquals(true, config.webhookSelfRegistrationEnabled());
        assertEquals("env-validating", config.webhookPredecessorValidatingName());
        assertEquals("env-mutating", config.webhookPredecessorMutatingName());
    }

    @Test
    void loadConfigRejectsInvalidTaskOneBooleanValues() {
        IllegalArgumentException controllerException = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of("CONTROLLER_ENABLED", "yes"), new Properties()));
        assertEquals("CONTROLLER_ENABLED must be true or false: yes", controllerException.getMessage());

        Properties cleanupDefaults = new Properties();
        cleanupDefaults.setProperty("webhook.registration.cleanup.enabled", "maybe");
        IllegalArgumentException cleanupException = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of(), cleanupDefaults));
        assertEquals("webhook.registration.cleanup.enabled must be true or false: maybe", cleanupException.getMessage());

        IllegalArgumentException selfRegistrationException = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of("WEBHOOK_SELF_REGISTRATION_ENABLED", "sometimes"),
                        new Properties()));
        assertEquals("WEBHOOK_SELF_REGISTRATION_ENABLED must be true or false: sometimes",
                selfRegistrationException.getMessage());
    }

    @Test
    void loadConfigAllowsEachEnabledCapabilityCombination() {
        assertCapabilityCombination(true, true);
        assertCapabilityCombination(true, false);
        assertCapabilityCombination(false, true);
    }

    @Test
    void loadConfigRejectsWhenAllCapabilitiesAreDisabled() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> EchoOperatorMain.loadConfig(Map.of("CONTROLLER_ENABLED", "false", "WEBHOOK_ENABLED", "false"),
                        new Properties()));

        assertEquals("At least one of CONTROLLER_ENABLED or WEBHOOK_ENABLED must be true", exception.getMessage());
    }

    private void assertCapabilityCombination(boolean controllerEnabled, boolean webhookEnabled) {
        EchoOperatorMain.OperatorConfig config = assertDoesNotThrow(() -> EchoOperatorMain.loadConfig(
                Map.of("CONTROLLER_ENABLED", String.valueOf(controllerEnabled),
                        "WEBHOOK_ENABLED", String.valueOf(webhookEnabled)), new Properties()));

        assertEquals(controllerEnabled, config.controllerEnabled());
        assertEquals(webhookEnabled, config.webhookEnabled());
    }
}
