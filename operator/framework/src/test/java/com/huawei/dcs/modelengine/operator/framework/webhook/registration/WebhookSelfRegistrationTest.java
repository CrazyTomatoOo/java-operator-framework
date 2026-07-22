package com.huawei.dcs.modelengine.operator.framework.webhook.registration;

import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationList;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperations;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperationsBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationList;
import io.fabric8.kubernetes.client.AdmissionRegistrationAPIGroupDSL;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1AdmissionRegistrationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookSelfRegistrationTest {
    @TempDir
    Path tempDir;

    @Test
    void registerCreatesValidatingWebhookConfiguration() throws Exception {
        Path caBundle = caBundle();
        ClientMocks mocks = clientMocks();
        RuleWithOperations rule = new RuleWithOperationsBuilder()
                .withApiGroups("modelengine.huawei.com")
                .withApiVersions("v1")
                .withOperations("CREATE", "UPDATE")
                .withResources("echos")
                .build();
        WebhookRegistrationConfig config = WebhookRegistrationConfig.builder("webhook-service", "operator-system", caBundle)
                .withServicePort(9443)
                .withBaseName("echo-webhook")
                .withFailurePolicy("Fail")
                .withTimeoutSeconds(7)
                .withRules(List.of(rule))
                .build();

        new WebhookSelfRegistration(mocks.client, config).register(List.of("echo"), List.of());

        ArgumentCaptor<ValidatingWebhookConfiguration> captor = ArgumentCaptor.forClass(
                ValidatingWebhookConfiguration.class);
        verify(mocks.validating).resource(captor.capture());
        verify(mocks.validatingResource).createOrReplace();
        ValidatingWebhookConfiguration resource = captor.getValue();
        assertEquals("echo-webhook.echo", resource.getMetadata().getName());
        assertEquals("admissionregistration.k8s.io/v1", resource.getApiVersion());
        assertEquals("ValidatingWebhookConfiguration", resource.getKind());

        ValidatingWebhook webhook = resource.getWebhooks().getFirst();
        assertEquals("echo-webhook.echo", webhook.getName());
        assertEquals("Fail", webhook.getFailurePolicy());
        assertEquals(7, webhook.getTimeoutSeconds());
        assertEquals("None", webhook.getSideEffects());
        assertEquals(List.of("v1"), webhook.getAdmissionReviewVersions());
        assertEquals(List.of(rule), webhook.getRules());
        assertEquals(encodedCa(), webhook.getClientConfig().getCaBundle());
        assertEquals("webhook-service", webhook.getClientConfig().getService().getName());
        assertEquals("operator-system", webhook.getClientConfig().getService().getNamespace());
        assertEquals(9443, webhook.getClientConfig().getService().getPort());
        assertEquals("/validate/echo", webhook.getClientConfig().getService().getPath());
    }

    @Test
    void registerCreatesMutatingWebhookConfiguration() throws Exception {
        Path caBundle = caBundle();
        ClientMocks mocks = clientMocks();
        WebhookRegistrationConfig config = WebhookRegistrationConfig.builder("webhook-service", "operator-system", caBundle)
                .withBaseName("echo-webhook")
                .withMutatingPathPrefix("mutate")
                .build();

        new WebhookSelfRegistration(mocks.client, config).register(List.of(), List.of("echo"));

        ArgumentCaptor<MutatingWebhookConfiguration> captor = ArgumentCaptor.forClass(MutatingWebhookConfiguration.class);
        verify(mocks.mutating).resource(captor.capture());
        verify(mocks.mutatingResource).createOrReplace();
        MutatingWebhookConfiguration resource = captor.getValue();
        assertEquals("echo-webhook.echo", resource.getMetadata().getName());
        assertEquals("admissionregistration.k8s.io/v1", resource.getApiVersion());
        assertEquals("MutatingWebhookConfiguration", resource.getKind());

        MutatingWebhook webhook = resource.getWebhooks().getFirst();
        assertEquals("echo-webhook.echo", webhook.getName());
        assertEquals("Fail", webhook.getFailurePolicy());
        assertEquals(10, webhook.getTimeoutSeconds());
        assertEquals("None", webhook.getSideEffects());
        assertEquals(encodedCa(), webhook.getClientConfig().getCaBundle());
        assertEquals("webhook-service", webhook.getClientConfig().getService().getName());
        assertEquals("operator-system", webhook.getClientConfig().getService().getNamespace());
        assertEquals(8443, webhook.getClientConfig().getService().getPort());
        assertEquals("/mutate/echo", webhook.getClientConfig().getService().getPath());
        assertEquals(List.of("*"), webhook.getRules().getFirst().getOperations());
        assertEquals(List.of("*"), webhook.getRules().getFirst().getResources());
    }

    @Test
    void registerFailsFastWhenCaBundleIsMissing() {
        Path missingCaBundle = tempDir.resolve("missing-ca.crt");
        ClientMocks mocks = clientMocks();
        WebhookRegistrationConfig config = WebhookRegistrationConfig.builder(
                "webhook-service", "operator-system", missingCaBundle).build();

        WebhookSelfRegistration registration = new WebhookSelfRegistration(mocks.client, config);

        assertThrows(IllegalStateException.class, () -> registration.register(List.of("echo"), List.of()));
        verifyNoInteractions(mocks.admission);
    }

    @Test
    void registerFailsFastWhenCaBundleIsEmpty() throws Exception {
        Path emptyCaBundle = tempDir.resolve("empty-ca.crt");
        Files.writeString(emptyCaBundle, "");
        ClientMocks mocks = clientMocks();
        WebhookRegistrationConfig config = WebhookRegistrationConfig.builder(
                "webhook-service", "operator-system", emptyCaBundle).build();

        WebhookSelfRegistration registration = new WebhookSelfRegistration(mocks.client, config);

        assertThrows(IllegalStateException.class, () -> registration.register(List.of("echo"), List.of()));
        verifyNoInteractions(mocks.admission);
    }

    private Path caBundle() throws Exception {
        Path caBundle = tempDir.resolve("ca.crt");
        Files.writeString(caBundle, "test-ca", StandardCharsets.UTF_8);
        return caBundle;
    }

    private String encodedCa() {
        return Base64.getEncoder().encodeToString("test-ca".getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static ClientMocks clientMocks() {
        KubernetesClient client = mock(KubernetesClient.class);
        AdmissionRegistrationAPIGroupDSL admission = mock(AdmissionRegistrationAPIGroupDSL.class);
        V1AdmissionRegistrationAPIGroupDSL v1 = mock(V1AdmissionRegistrationAPIGroupDSL.class);
        NonNamespaceOperation<ValidatingWebhookConfiguration, ValidatingWebhookConfigurationList,
                Resource<ValidatingWebhookConfiguration>> validating = mock(NonNamespaceOperation.class);
        Resource<ValidatingWebhookConfiguration> validatingResource = mock(Resource.class);
        NonNamespaceOperation<MutatingWebhookConfiguration, MutatingWebhookConfigurationList,
                Resource<MutatingWebhookConfiguration>> mutating = mock(NonNamespaceOperation.class);
        Resource<MutatingWebhookConfiguration> mutatingResource = mock(Resource.class);

        when(client.admissionRegistration()).thenReturn(admission);
        when(admission.v1()).thenReturn(v1);
        when(v1.validatingWebhookConfigurations()).thenReturn(validating);
        when(validating.resource(any())).thenReturn(validatingResource);
        when(v1.mutatingWebhookConfigurations()).thenReturn(mutating);
        when(mutating.resource(any())).thenReturn(mutatingResource);

        return new ClientMocks(client, admission, validating, validatingResource, mutating, mutatingResource);
    }

    private record ClientMocks(KubernetesClient client, AdmissionRegistrationAPIGroupDSL admission,
            NonNamespaceOperation<ValidatingWebhookConfiguration, ValidatingWebhookConfigurationList,
                    Resource<ValidatingWebhookConfiguration>> validating,
            Resource<ValidatingWebhookConfiguration> validatingResource,
            NonNamespaceOperation<MutatingWebhookConfiguration, MutatingWebhookConfigurationList,
                    Resource<MutatingWebhookConfiguration>> mutating,
            Resource<MutatingWebhookConfiguration> mutatingResource) {
    }
}
