package com.huawei.dcs.modelengine.operator.framework.webhook.registration;

import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationList;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperations;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperationsBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationList;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceConversion;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionSpec;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ServiceReferenceBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookClientConfigBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookConversion;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.AdmissionRegistrationAPIGroupDSL;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.V1AdmissionRegistrationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient
class WebhookSelfRegistrationTest {
    @TempDir
    Path tempDir;

    KubernetesMockServer server;
    KubernetesClient mockClient;

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

    @Test
    void patchConversionWebhookClientConfigUpdatesWebhookService() throws Exception {
        String crdName = "widgets.example.com";
        CustomResourceDefinition initial = webhookCrd(crdName, "1");
        CustomResourceDefinition expectedUpdated = webhookCrd(crdName, "2");
        setConversionClientConfig(expectedUpdated, "conversion-ca", "conversion-service", "operator-system", 9443);
        server.expect().post().withPath("/apis/apiextensions.k8s.io/v1/customresourcedefinitions")
                .andReturn(201, initial).once();
        server.expect().get().withPath(crdPath(crdName)).andReturn(200, initial).once();
        server.expect().put().withPath(crdPath(crdName)).andReturn(200, expectedUpdated).once();
        server.expect().get().withPath(crdPath(crdName)).andReturn(200, expectedUpdated).once();
        Path explicitCaBundle = tempDir.resolve("conversion-ca.crt");
        Files.writeString(explicitCaBundle, "conversion-ca", StandardCharsets.UTF_8);

        mockClient.apiextensions().v1().customResourceDefinitions().resource(initial).create();

        registration(mockClient).patchConversionWebhookClientConfig(
                crdName, explicitCaBundle, "conversion-service", "operator-system", 9443);

        CustomResourceDefinition updated = mockClient.apiextensions().v1().customResourceDefinitions()
                .withName(crdName).get();
        io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookClientConfig clientConfig = updated.getSpec()
                .getConversion().getWebhook().getClientConfig();
        assertEquals(Base64.getEncoder().encodeToString("conversion-ca".getBytes(StandardCharsets.UTF_8)),
                clientConfig.getCaBundle());
        assertNotNull(clientConfig.getService());
        assertEquals("conversion-service", clientConfig.getService().getName());
        assertEquals("operator-system", clientConfig.getService().getNamespace());
        assertEquals(9443, clientConfig.getService().getPort());
    }

    @Test
    void patchConversionWebhookClientConfigRetriesConflict() throws Exception {
        String crdName = "widgets.example.com";
        CustomResourceDefinition firstRead = webhookCrd(crdName, "1");
        CustomResourceDefinition secondRead = webhookCrd(crdName, "2");
        CustomResourceDefinition updated = webhookCrd(crdName, "3");
        setConversionClientConfig(updated, "conversion-ca", "conversion-service", "operator-system", 9443);
        server.expect().get().withPath(crdPath(crdName)).andReturn(200, firstRead).once();
        server.expect().put().withPath(crdPath(crdName)).andReturn(409,
                new StatusBuilder().withCode(409).withMessage("conflict").build()).once();
        server.expect().get().withPath(crdPath(crdName)).andReturn(200, secondRead).once();
        server.expect().put().withPath(crdPath(crdName)).andReturn(200, updated).once();
        server.expect().get().withPath(crdPath(crdName)).andReturn(200, updated).once();
        Path explicitCaBundle = tempDir.resolve("conversion-ca.crt");
        Files.writeString(explicitCaBundle, "conversion-ca", StandardCharsets.UTF_8);

        registration(mockClient).patchConversionWebhookClientConfig(
                crdName, explicitCaBundle, "conversion-service", "operator-system", 9443);

        CustomResourceDefinition result = mockClient.apiextensions().v1().customResourceDefinitions()
                .withName(crdName).get();
        assertEquals("conversion-service", result.getSpec().getConversion().getWebhook().getClientConfig()
                .getService().getName());
    }

    @Test
    void patchConversionWebhookClientConfigFailsWhenCrdIsMissing() throws Exception {
        Path explicitCaBundle = tempDir.resolve("conversion-ca.crt");
        Files.writeString(explicitCaBundle, "conversion-ca", StandardCharsets.UTF_8);
        server.expect().get().withPath(crdPath("missing.example.com")).andReturn(404,
                new StatusBuilder().withCode(404).withMessage("not found").build()).once();

        assertThrows(IllegalStateException.class, () -> registration(mockClient)
                .patchConversionWebhookClientConfig(
                        "missing.example.com", explicitCaBundle, "conversion-service", "operator-system", 9443));
    }

    @Test
    void unregisterAdmissionWebhooksDeletesStaleConfigurations() {
        String validatingName = "echo-webhook.validator";
        String mutatingName = "echo-webhook.mutator";
        ValidatingWebhookConfiguration validating = new ValidatingWebhookConfigurationBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(validatingName).build()).build();
        MutatingWebhookConfiguration mutating = new MutatingWebhookConfigurationBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(mutatingName).build()).build();
        server.expect().post().withPath("/apis/admissionregistration.k8s.io/v1/validatingwebhookconfigurations")
                .andReturn(201, validating).once();
        server.expect().post().withPath("/apis/admissionregistration.k8s.io/v1/mutatingwebhookconfigurations")
                .andReturn(201, mutating).once();
        server.expect().delete().withPath(admissionPath("validatingwebhookconfigurations", validatingName))
                .andReturn(200, new StatusBuilder().withCode(200).build()).once();
        server.expect().delete().withPath(admissionPath("mutatingwebhookconfigurations", mutatingName))
                .andReturn(200, new StatusBuilder().withCode(200).build()).once();
        server.expect().get().withPath(admissionPath("validatingwebhookconfigurations", validatingName))
                .andReturn(404, new StatusBuilder().withCode(404).build()).once();
        server.expect().get().withPath(admissionPath("mutatingwebhookconfigurations", mutatingName))
                .andReturn(404, new StatusBuilder().withCode(404).build()).once();

        mockClient.admissionRegistration().v1().validatingWebhookConfigurations().resource(validating).create();
        mockClient.admissionRegistration().v1().mutatingWebhookConfigurations().resource(mutating).create();

        registration(mockClient).unregisterAdmissionWebhooks(
                "echo-webhook", List.of("/validator"), List.of("mutator"));

        assertNull(mockClient.admissionRegistration().v1().validatingWebhookConfigurations()
                .withName(validatingName).get());
        assertNull(mockClient.admissionRegistration().v1().mutatingWebhookConfigurations()
                .withName(mutatingName).get());
    }

    @Test
    void unregisterAdmissionWebhooksPropagatesNonNotFoundErrors() {
        String validatingName = "echo-webhook.validator";
        ClientMocks mocks = clientMocks();
        when(mocks.validating.withName(validatingName)).thenReturn(mocks.validatingResource);
        when(mocks.validatingResource.delete()).thenThrow(
                new KubernetesClientException("server error", 500, null));

        assertThrows(KubernetesClientException.class, () -> new WebhookSelfRegistration(mocks.client,
                WebhookRegistrationConfig.builder("webhook-service", "operator-system", tempDir.resolve("ca.crt"))
                        .build())
                .unregisterAdmissionWebhooks("echo-webhook", List.of("validator"), List.of()));
    }

    private Path caBundle() throws Exception {
        Path caBundle = tempDir.resolve("ca.crt");
        Files.writeString(caBundle, "test-ca", StandardCharsets.UTF_8);
        return caBundle;
    }

    private String encodedCa() {
        return Base64.getEncoder().encodeToString("test-ca".getBytes(StandardCharsets.UTF_8));
    }

    private WebhookSelfRegistration registration(KubernetesClient client) {
        return new WebhookSelfRegistration(client,
                WebhookRegistrationConfig.builder("webhook-service", "operator-system",
                        tempDir.resolve("configured-ca.crt")).build());
    }

    private CustomResourceDefinition webhookCrd(String name, String resourceVersion) {
        CustomResourceConversion conversion = new CustomResourceConversion(
                "Webhook", new WebhookConversion(new WebhookClientConfigBuilder()
                        .withService(new ServiceReferenceBuilder().build()).build(), List.of("v1")));
        CustomResourceDefinitionSpec spec = new CustomResourceDefinitionSpec(
                conversion, "example.com", null, null, "Namespaced", List.of());
        return new CustomResourceDefinition("apiextensions.k8s.io/v1", "CustomResourceDefinition",
                new ObjectMetaBuilder().withName(name).withResourceVersion(resourceVersion).build(), spec, null);
    }

    private void setConversionClientConfig(CustomResourceDefinition crd, String caBundle, String serviceName,
            String serviceNamespace, int servicePort) {
        io.fabric8.kubernetes.api.model.apiextensions.v1.WebhookClientConfig clientConfig = crd.getSpec()
                .getConversion().getWebhook().getClientConfig();
        clientConfig.setCaBundle(Base64.getEncoder().encodeToString(caBundle.getBytes(StandardCharsets.UTF_8)));
        clientConfig.getService().setName(serviceName);
        clientConfig.getService().setNamespace(serviceNamespace);
        clientConfig.getService().setPort(servicePort);
    }

    private static String crdPath(String name) {
        return "/apis/apiextensions.k8s.io/v1/customresourcedefinitions/" + name;
    }

    private static String admissionPath(String resource, String name) {
        return "/apis/admissionregistration.k8s.io/v1/" + resource + "/" + name;
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
