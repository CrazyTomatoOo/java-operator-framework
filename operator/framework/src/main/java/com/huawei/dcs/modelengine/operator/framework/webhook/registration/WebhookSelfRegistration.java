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
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceConversion;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ServiceReference;
import io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookConversion;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final String WEBHOOK_CONVERSION_STRATEGY = "Webhook";
    private static final int MAX_CONVERSION_CONFLICT_RETRIES = 3;

    private final KubernetesClient client;
    private final WebhookRegistrationConfig config;

    public WebhookSelfRegistration(KubernetesClient client, WebhookRegistrationConfig config) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public void register(AdmissionHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        register(handler.enabledValidatorNames(), handler.enabledMutatorNames());
    }

    public void register(Collection<String> validatorNames, Collection<String> mutatorNames) {
        String caBundle = caBundle();
        Objects.requireNonNull(validatorNames, "validatorNames must not be null")
                .forEach(name -> registerValidatingWebhook(normalize(name), caBundle));
        Objects.requireNonNull(mutatorNames, "mutatorNames must not be null")
                .forEach(name -> registerMutatingWebhook(normalize(name), caBundle));
    }

    public void patchConversionWebhookClientConfig(String crdName, Path caBundlePath,
            String serviceName, String serviceNamespace, int servicePort) {
        Objects.requireNonNull(crdName, "CRD name must not be null");
        Objects.requireNonNull(serviceName, "service name must not be null");
        Objects.requireNonNull(serviceNamespace, "service namespace must not be null");
        String caBundle = caBundle(caBundlePath);
        int conflictRetries = 0;
        while (true) {
            CustomResourceDefinition crd = client.apiextensions().v1().customResourceDefinitions()
                    .withName(crdName).get();
            CustomResourceConversion conversion = webhookConversion(crdName, crd);
            WebhookConversion webhook = conversion.getWebhook();
            io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookClientConfig clientConfig = webhook.getClientConfig();
            if (isConfigured(clientConfig, caBundle, serviceName, serviceNamespace, servicePort)) {
                return;
            }
            if (clientConfig == null) {
                clientConfig = new io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookClientConfig();
                webhook.setClientConfig(clientConfig);
            }
            clientConfig.setCaBundle(caBundle);
            ServiceReference service = clientConfig.getService();
            if (service == null) {
                service = new ServiceReference();
                clientConfig.setService(service);
            }
            service.setName(serviceName);
            service.setNamespace(serviceNamespace);
            service.setPort(servicePort);
            try {
                client.apiextensions().v1().customResourceDefinitions().resource(crd).update();
                return;
            } catch (KubernetesClientException exception) {
                if (exception.getCode() != 409 || conflictRetries == MAX_CONVERSION_CONFLICT_RETRIES) {
                    throw exception;
                }
                conflictRetries++;
            }
        }
    }

    public void unregisterAdmissionWebhooks(String baseName, Collection<String> validatorNames,
            Collection<String> mutatorNames) {
        Objects.requireNonNull(baseName, "base name must not be null");
        Objects.requireNonNull(validatorNames, "validator names must not be null")
                .forEach(name -> deleteValidatingWebhook(baseName + "." + normalize(name)));
        Objects.requireNonNull(mutatorNames, "mutator names must not be null")
                .forEach(name -> deleteMutatingWebhook(baseName + "." + normalize(name)));
    }

    private CustomResourceConversion webhookConversion(String crdName, CustomResourceDefinition crd) {
        if (crd == null || crd.getSpec() == null || crd.getSpec().getConversion() == null
                || !WEBHOOK_CONVERSION_STRATEGY.equals(crd.getSpec().getConversion().getStrategy())
                || crd.getSpec().getConversion().getWebhook() == null) {
            throw new IllegalStateException("CRD is missing or not configured for webhook conversion: " + crdName);
        }
        return crd.getSpec().getConversion();
    }

    private boolean isConfigured(io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookClientConfig clientConfig,
            String caBundle, String serviceName,
            String serviceNamespace, int servicePort) {
        ServiceReference service = clientConfig == null ? null : clientConfig.getService();
        return clientConfig != null && caBundle.equals(clientConfig.getCaBundle()) && service != null
                && serviceName.equals(service.getName()) && serviceNamespace.equals(service.getNamespace())
                && Integer.valueOf(servicePort).equals(service.getPort());
    }

    private void deleteValidatingWebhook(String name) {
        try {
            client.admissionRegistration().v1().validatingWebhookConfigurations().withName(name).delete();
        } catch (KubernetesClientException exception) {
            if (exception.getCode() != 404) {
                throw exception;
            }
        }
    }

    private void deleteMutatingWebhook(String name) {
        try {
            client.admissionRegistration().v1().mutatingWebhookConfigurations().withName(name).delete();
        } catch (KubernetesClientException exception) {
            if (exception.getCode() != 404) {
                throw exception;
            }
        }
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
        return caBundle(config.caBundlePath());
    }

    private String caBundle(Path caBundlePath) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Objects.requireNonNull(caBundlePath, "CA bundle path must not be null"));
        } catch (IOException exception) {
            throw new IllegalStateException("CA bundle file is missing or unreadable: " + caBundlePath, exception);
        }
        if (bytes.length == 0) {
            throw new IllegalStateException("CA bundle file is empty: " + caBundlePath);
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
