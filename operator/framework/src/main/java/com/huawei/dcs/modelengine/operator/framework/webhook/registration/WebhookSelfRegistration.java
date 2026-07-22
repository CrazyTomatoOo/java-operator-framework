package com.huawei.dcs.modelengine.operator.framework.webhook.registration;

import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionHandler;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ServiceReferenceBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.WebhookClientConfig;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.WebhookClientConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collection;
import java.util.Objects;

/**
 * Creates or updates Kubernetes admission webhook configurations during operator startup.
 */
public final class WebhookSelfRegistration {
    private static final String API_VERSION = "admissionregistration.k8s.io/v1";
    private static final String VALIDATING_KIND = "ValidatingWebhookConfiguration";
    private static final String MUTATING_KIND = "MutatingWebhookConfiguration";
    private static final String ADMISSION_REVIEW_VERSION = "v1";

    private final KubernetesClient client;
    private final WebhookRegistrationConfig config;

    public WebhookSelfRegistration(KubernetesClient client, WebhookRegistrationConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public void register(AdmissionHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        register(handler.validatorNames(), handler.mutatorNames());
    }

    public void register(Collection<String> validatorNames, Collection<String> mutatorNames) {
        String caBundle = caBundle();
        Objects.requireNonNull(validatorNames, "validatorNames must not be null")
                .forEach(name -> registerValidatingWebhook(normalize(name), caBundle));
        Objects.requireNonNull(mutatorNames, "mutatorNames must not be null")
                .forEach(name -> registerMutatingWebhook(normalize(name), caBundle));
    }

    private void registerValidatingWebhook(String name, String caBundle) {
        ValidatingWebhookConfiguration resource = new ValidatingWebhookConfigurationBuilder()
                .withApiVersion(API_VERSION)
                .withKind(VALIDATING_KIND)
                .withMetadata(new ObjectMetaBuilder().withName(resourceName(name)).build())
                .withWebhooks(validatingWebhook(name, caBundle))
                .build();
        client.admissionRegistration().v1().validatingWebhookConfigurations().resource(resource).createOrReplace();
    }

    private void registerMutatingWebhook(String name, String caBundle) {
        MutatingWebhookConfiguration resource = new MutatingWebhookConfigurationBuilder()
                .withApiVersion(API_VERSION)
                .withKind(MUTATING_KIND)
                .withMetadata(new ObjectMetaBuilder().withName(resourceName(name)).build())
                .withWebhooks(mutatingWebhook(name, caBundle))
                .build();
        client.admissionRegistration().v1().mutatingWebhookConfigurations().resource(resource).createOrReplace();
    }

    private ValidatingWebhook validatingWebhook(String name, String caBundle) {
        return new ValidatingWebhookBuilder()
                .withName(resourceName(name))
                .withClientConfig(clientConfig(config.validatingPathPrefix() + "/" + name, caBundle))
                .withFailurePolicy(config.failurePolicy())
                .withTimeoutSeconds(config.timeoutSeconds())
                .withSideEffects(config.sideEffects())
                .withAdmissionReviewVersions(ADMISSION_REVIEW_VERSION)
                .withRules(config.rules())
                .withNamespaceSelector(config.namespaceSelector())
                .withObjectSelector(config.objectSelector())
                .build();
    }

    private MutatingWebhook mutatingWebhook(String name, String caBundle) {
        return new MutatingWebhookBuilder()
                .withName(resourceName(name))
                .withClientConfig(clientConfig(config.mutatingPathPrefix() + "/" + name, caBundle))
                .withFailurePolicy(config.failurePolicy())
                .withTimeoutSeconds(config.timeoutSeconds())
                .withSideEffects(config.sideEffects())
                .withAdmissionReviewVersions(ADMISSION_REVIEW_VERSION)
                .withRules(config.rules())
                .withNamespaceSelector(config.namespaceSelector())
                .withObjectSelector(config.objectSelector())
                .build();
    }

    private WebhookClientConfig clientConfig(String path, String caBundle) {
        return new WebhookClientConfigBuilder()
                .withCaBundle(caBundle)
                .withService(new ServiceReferenceBuilder()
                        .withName(config.serviceName())
                        .withNamespace(config.serviceNamespace())
                        .withPort(config.servicePort())
                        .withPath(path)
                        .build())
                .build();
    }

    private String caBundle() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(config.caBundlePath());
        } catch (IOException exception) {
            throw new IllegalStateException("CA bundle file is missing or unreadable: " + config.caBundlePath(), exception);
        }
        if (bytes.length == 0) {
            throw new IllegalStateException("CA bundle file is empty: " + config.caBundlePath());
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String resourceName(String name) {
        return config.baseName() + "." + name;
    }

    private static String normalize(String name) {
        String normalized = Objects.requireNonNull(name, "webhook name must not be null").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("webhook name must not be blank");
        }
        return normalized.startsWith("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
    }
}
