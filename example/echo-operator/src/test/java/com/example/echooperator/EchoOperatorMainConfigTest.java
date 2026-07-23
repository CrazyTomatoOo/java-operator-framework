package com.example.echooperator;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
