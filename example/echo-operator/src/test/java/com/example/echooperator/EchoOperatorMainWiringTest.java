package com.example.echooperator;

import com.example.echooperator.controller.EchoReconciler;
import com.huawei.dcs.modelengine.operator.framework.Operator;
import com.huawei.dcs.modelengine.operator.framework.event.EventRecorder;
import com.huawei.dcs.modelengine.operator.framework.leader.LeaderElectionManager;
import com.huawei.dcs.modelengine.operator.framework.metrics.MetricsHealthServer;
import com.huawei.dcs.modelengine.operator.framework.source.ResourceEventSource;
import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookRegistrationConfig;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookSelfRegistration;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperations;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfigurationList;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationList;
import io.fabric8.kubernetes.client.AdmissionRegistrationAPIGroupDSL;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1AdmissionRegistrationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EchoOperatorMainWiringTest {

    @TempDir
    Path tempDir;

    @Test
    void splitControllerOwnsOnlyControllerResourcesAndUsesControllerReadiness()
            throws IOException, InterruptedException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", false, false,
                "configured-service", "service-ns", tempDir.resolve("unused-certs"),
                tempDir.resolve("fallback-ca.crt"), true, false, false, null, null);

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<EventRecorder> recorders = org.mockito.Mockito.mockConstruction(EventRecorder.class);
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            Operator operator = operators.constructed().getFirst();
            ResourceEventSource eventSource = mock(ResourceEventSource.class);
            io.fabric8.kubernetes.client.informers.SharedIndexInformer informer =
                    mock(io.fabric8.kubernetes.client.informers.SharedIndexInformer.class);
            when(eventSource.getInformer()).thenReturn(informer);
            when(operator.eventSources()).thenReturn(List.of(eventSource));

            assertNull(main.webhookServer());
            assertNull(main.admissionHandler());
            assertNull(main.conversionHandler());
            assertNull(main.webhookSelfRegistration());
            assertEquals(1, operators.constructed().size());
            assertEquals(1, recorders.constructed().size());
            assertTrue(registrations.constructed().isEmpty());
            assertTrue(!main.metricsHealthServer().healthServer().isReady());

            main.start();
            HttpResponse<Void> notReady = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + main.metricsHealthServer().address().getPort() + "/readyz"))
                    .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(503, notReady.statusCode());
            when(informer.hasSynced()).thenReturn(true);
            HttpResponse<Void> ready = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + main.metricsHealthServer().address().getPort() + "/readyz"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(200, ready.statusCode());
            assertTrue(registrations.constructed().isEmpty());
            verify(operator).start();
            assertTrue(main.metricsHealthServer().address() instanceof InetSocketAddress);

            main.stop();
            main.stop();
            verify(operator).stop();
            verify(recorders.constructed().getFirst()).close();
            verify(fixture.client()).close();
        }
    }

    @Test
    void webhookOnlyOwnsOnlyWebhookResourcesAndBecomesReadyAfterPostStartOperations() throws IOException {
        ClientFixture fixture = client();
        when(fixture.validating().withName("helm-validating")).thenReturn(fixture.validatingResource());
        when(fixture.validatingResource().get()).thenReturn(new ValidatingWebhookConfiguration(), null);
        when(fixture.mutating().withName("helm-mutating")).thenReturn(fixture.mutatingResource());
        when(fixture.mutatingResource().get()).thenReturn(null);
        Path certDirectory = tempDir.resolve("webhook-only-certs");
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, true,
                "configured-service", "service-ns", certDirectory, tempDir.resolve("fallback-ca.crt"),
                false, false, true, "helm-validating", "helm-mutating", true);
        List<Long> pollDelays = new ArrayList<>();
        AtomicReference<EchoOperatorMain> mainReference = new AtomicReference<>();

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<EventRecorder> recorders = org.mockito.Mockito.mockConstruction(EventRecorder.class);
                MockedConstruction<EchoReconciler> reconcilers =
                        org.mockito.Mockito.mockConstruction(EchoReconciler.class);
                MockedConstruction<LeaderElectionManager> leaders =
                        org.mockito.Mockito.mockConstruction(LeaderElectionManager.class);
                MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                        (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config, delay -> {
                pollDelays.add(delay);
                assertTrue(!mainReference.get().metricsHealthServer().healthServer().isReady());
            });
            mainReference.set(main);

            assertNull(main.operator());
            assertTrue(operators.constructed().isEmpty());
            assertTrue(recorders.constructed().isEmpty());
            assertTrue(reconcilers.constructed().isEmpty());
            assertTrue(leaders.constructed().isEmpty());
            assertTrue(!main.metricsHealthServer().healthServer().isReady());
            assertTrue(Files.exists(certDirectory.resolve("tls.crt")));
            assertTrue(main.webhookServer() != null);

            WebhookSelfRegistration registration = registrations.constructed().getFirst();
            org.mockito.Mockito.doAnswer(invocation -> {
                assertTrue(!main.metricsHealthServer().healthServer().isReady());
                return null;
            }).when(registration).register(any());

            main.start();

            verify(registration).patchConversionWebhookClientConfig(eq("echoresources.example.com"), any(Path.class),
                    eq("configured-service"), eq("service-ns"), eq(9443));
            verify(registration).register(main.admissionHandler());
            org.mockito.InOrder startOrder = org.mockito.Mockito.inOrder(servers.constructed().getFirst(), registration);
            startOrder.verify(servers.constructed().getFirst()).start();
            startOrder.verify(registration).patchConversionWebhookClientConfig(eq("echoresources.example.com"),
                    any(Path.class), eq("configured-service"), eq("service-ns"), eq(9443));
            startOrder.verify(registration).register(main.admissionHandler());
            assertEquals(List.of(1_000L), pollDelays);
            verify(fixture.validating(), org.mockito.Mockito.times(2)).withName("helm-validating");
            verify(fixture.mutating(), org.mockito.Mockito.times(2)).withName("helm-mutating");
            assertTrue(main.metricsHealthServer().healthServer().isReady());
            assertTrue(leaders.constructed().isEmpty());

            main.stop();
            main.stop();
            verify(servers.constructed().getFirst()).stop();
            verify(fixture.client()).close();
        }
    }

    @Test
    void helmOwnedWebhookDoesNotWaitForOrDeleteHelmPredecessorNames() throws IOException {
        ClientFixture fixture = client();
        when(fixture.validating().withName("helm-validating")).thenReturn(fixture.validatingResource());
        when(fixture.validatingResource().get()).thenReturn(new ValidatingWebhookConfiguration());
        when(fixture.mutating().withName("helm-mutating")).thenReturn(fixture.mutatingResource());
        when(fixture.mutatingResource().get()).thenReturn(new MutatingWebhookConfiguration());
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, false,
                "configured-service", "service-ns", tempDir.resolve("helm-owned-certs"),
                tempDir.resolve("helm-owned-ca.crt"), false, true, false,
                "helm-validating", "helm-mutating");

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config,
                    delay -> {
                        throw new AssertionError("Helm-owned mode must not poll predecessor names");
                    });
            WebhookServer server = servers.constructed().getFirst();
            WebhookSelfRegistration registration = registrations.constructed().getFirst();
            org.mockito.Mockito.doAnswer(invocation -> {
                verify(server).start();
                assertTrue(!main.metricsHealthServer().healthServer().isReady());
                return null;
            }).when(registration).unregisterAdmissionWebhooks(anyString(), any(), any());

            main.start();

            verify(fixture.validating(), org.mockito.Mockito.never()).withName("helm-validating");
            verify(fixture.mutating(), org.mockito.Mockito.never()).withName("helm-mutating");
            verify(registration).unregisterAdmissionWebhooks("echo-operator.watched-ns",
                    List.of("echo.example.com"), List.of("echo.example.com"));
            verify(registration, org.mockito.Mockito.never()).register(any());
            verify(registration, org.mockito.Mockito.never()).patchConversionWebhookClientConfig(anyString(),
                    any(Path.class), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
            assertTrue(main.metricsHealthServer().healthServer().isReady());

            main.stop();
            verify(server).stop();
            verify(fixture.client()).close();
        }
    }

    @Test
    void disabledAdmissionEndpointsCleanupOnlyStableRuntimeNames() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = new EchoOperatorMain.OperatorConfig(
                "watched-ns", "pod-ns", 0, false, "watched-ns", "echo-operator-lock",
                tempDir.resolve("disabled-endpoints-ca.crt"), true, false, false, false,
                false, "echo-operator-webhook-ca", "configured-service", "service-ns",
                tempDir.resolve("disabled-endpoints-certs"), 0, 9443, false, false, true, null, null);

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            WebhookSelfRegistration registration = registrations.constructed().getFirst();

            main.start();

            verify(registration).unregisterAdmissionWebhooks("echo-operator.watched-ns",
                    List.of("echo.example.com"), List.of());
            verify(registration).unregisterAdmissionWebhooks("echo-operator.watched-ns",
                    List.of(), List.of("echo.example.com"));
            verify(registration).register(main.admissionHandler());
            verify(registration, org.mockito.Mockito.never()).patchConversionWebhookClientConfig(anyString(),
                    any(Path.class), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
            verify(fixture.validating(), org.mockito.Mockito.never()).withName("contract-echo-operator-validating");
            verify(fixture.mutating(), org.mockito.Mockito.never()).withName("contract-echo-operator-mutating");

            main.stop();
            verify(servers.constructed().getFirst()).stop();
        }
    }

    @Test
    void combinedControllerOnlyLazilyCreatesCleanupWhenExplicitlyEnabled() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", false, false,
                "configured-service", "service-ns", tempDir.resolve("cleanup-only-certs"),
                tempDir.resolve("cleanup-only-ca.crt"), true, true, false, null, null);

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);

            assertNull(main.webhookSelfRegistration());
            assertTrue(registrations.constructed().isEmpty());
            assertTrue(Files.notExists(tempDir.resolve("cleanup-only-certs")));

            main.start();

            WebhookSelfRegistration cleanup = registrations.constructed().getFirst();
            verify(cleanup).unregisterAdmissionWebhooks("echo-operator.watched-ns",
                    List.of("echo.example.com"), List.of("echo.example.com"));
            verify(operators.constructed().getFirst()).start();
            main.stop();
        }
    }

    @Test
    void predecessorBarrierUsesExactNamesAndTimesOutNotReadyAfterSixtyAttempts() throws IOException {
        ClientFixture fixture = client();
        when(fixture.validating().withName("helm-validating")).thenReturn(fixture.validatingResource());
        when(fixture.validatingResource().get()).thenReturn(new ValidatingWebhookConfiguration());
        when(fixture.mutating().withName("helm-mutating")).thenReturn(fixture.mutatingResource());
        when(fixture.mutatingResource().get()).thenReturn(null);
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, true,
                "configured-service", "service-ns", tempDir.resolve("barrier-certs"),
                tempDir.resolve("fallback-ca.crt"), false, false, true,
                "helm-validating", "helm-mutating");
        List<Long> pollDelays = new ArrayList<>();

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class);
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config, pollDelays::add);

            IllegalStateException exception = assertThrows(IllegalStateException.class, main::start);

            assertTrue(exception.getMessage().contains("helm-validating"));
            assertTrue(exception.getMessage().contains("60"));
            assertEquals(59, pollDelays.size());
            assertTrue(pollDelays.stream().allMatch(delay -> delay == 1_000L));
            verify(fixture.validating(), org.mockito.Mockito.times(60)).withName("helm-validating");
            verify(fixture.mutating(), org.mockito.Mockito.times(60)).withName("helm-mutating");
            verify(servers.constructed().getFirst(), org.mockito.Mockito.never()).start();
            verify(registrations.constructed().getFirst(), org.mockito.Mockito.never()).register(any());
            assertTrue(!main.metricsHealthServer().healthServer().isReady());
        }
    }

    @Test
    void predecessorBarrierKeepsReadinessEndpointAvailableAndNotReady() throws Exception {
        ClientFixture fixture = client();
        when(fixture.validating().withName("helm-validating")).thenReturn(fixture.validatingResource());
        when(fixture.validatingResource().get()).thenReturn(new ValidatingWebhookConfiguration(), null);
        when(fixture.mutating().withName("helm-mutating")).thenReturn(fixture.mutatingResource());
        when(fixture.mutatingResource().get()).thenReturn(new MutatingWebhookConfiguration(), null);
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, false,
                "configured-service", "service-ns", tempDir.resolve("ready-barrier-certs"),
                tempDir.resolve("ready-barrier-ca.crt"), false, false, true, "helm-validating", "helm-mutating");
        CountDownLatch predecessorObserved = new CountDownLatch(1);
        CountDownLatch releasePredecessor = new CountDownLatch(1);
        ExecutorService starter = Executors.newSingleThreadExecutor();
        EchoOperatorMain main = null;

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            main = EchoOperatorMain.create(fixture.client(), config, delay -> {
                predecessorObserved.countDown();
                try {
                    releasePredecessor.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            });
            Future<?> start = starter.submit(main::start);
            assertTrue(predecessorObserved.await(2, TimeUnit.SECONDS));

            HttpResponse<Void> readiness = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + main.metricsHealthServer().address().getPort() + "/readyz"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());

            assertEquals(503, readiness.statusCode());
            releasePredecessor.countDown();
            start.get(2, TimeUnit.SECONDS);
            main.stop();
        } finally {
            releasePredecessor.countDown();
            if (main != null) {
                main.stop();
            }
            starter.shutdownNow();
        }
    }

    @Test
    void combinedHelmOwnedModeCleansRuntimeRegistrationsAndRequiresBothCapabilitiesReady()
            throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, false,
                "configured-service", "service-ns", tempDir.resolve("combined-certs"),
                tempDir.resolve("combined-ca.crt"), true, true, false, null, null);

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<EventRecorder> recorders = org.mockito.Mockito.mockConstruction(EventRecorder.class);
                MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                        (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            Operator operator = operators.constructed().getFirst();
            ResourceEventSource eventSource = mock(ResourceEventSource.class);
            io.fabric8.kubernetes.client.informers.SharedIndexInformer informer =
                    mock(io.fabric8.kubernetes.client.informers.SharedIndexInformer.class);
            when(eventSource.getInformer()).thenReturn(informer);
            when(operator.eventSources()).thenReturn(List.of(eventSource));

            assertEquals(1, operators.constructed().size());
            assertEquals(1, recorders.constructed().size());
            assertEquals(1, servers.constructed().size());
            assertEquals(1, registrations.constructed().size());

            main.start();

            WebhookServer server = servers.constructed().getFirst();
            WebhookSelfRegistration registration = registrations.constructed().getFirst();
            org.mockito.InOrder startOrder = org.mockito.Mockito.inOrder(server, registration, operator);
            startOrder.verify(server).start();
            startOrder.verify(registration).unregisterAdmissionWebhooks("echo-operator.watched-ns",
                    List.of("echo.example.com"), List.of("echo.example.com"));
            startOrder.verify(operator).start();
            verify(registration, org.mockito.Mockito.never()).register(any());
            verify(registration, org.mockito.Mockito.never()).patchConversionWebhookClientConfig(anyString(),
                    any(Path.class), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
            assertTrue(!main.metricsHealthServer().healthServer().isReady());
            when(informer.hasSynced()).thenReturn(true);
            assertTrue(main.metricsHealthServer().healthServer().isReady());

            main.stop();
            main.stop();
            verify(server).stop();
            verify(recorders.constructed().getFirst()).close();
            verify(operator).stop();
            verify(fixture.client()).close();
        }
    }

    @Test
    void failedAdmissionCleanupKeepsWebhookNotReadyAndStopsTheStartedServer() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, false,
                "configured-service", "service-ns", tempDir.resolve("failed-cleanup-certs"),
                tempDir.resolve("failed-cleanup-ca.crt"), false, true, false, null, null);

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class, (registration, context) ->
                                org.mockito.Mockito.doThrow(new IllegalStateException("admission cleanup failed"))
                                        .when(registration).unregisterAdmissionWebhooks(anyString(), any(), any()))) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            WebhookServer server = servers.constructed().getFirst();
            WebhookSelfRegistration registration = registrations.constructed().getFirst();

            IllegalStateException exception = assertThrows(IllegalStateException.class, main::start);

            assertEquals("admission cleanup failed", exception.getMessage());
            verify(server).start();
            verify(server).stop();
            verify(registration, org.mockito.Mockito.never()).register(any());
            verify(fixture.client()).close();
            assertTrue(!main.metricsHealthServer().healthServer().isReady());
        }
    }

    @Test
    void failedTlsStartKeepsWebhookNotReadyAndStopsTheServer() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, false,
                "configured-service", "service-ns", tempDir.resolve("failed-tls-certs"),
                tempDir.resolve("failed-tls-ca.crt"), false, false, true, null, null);

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                (server, context) -> org.mockito.Mockito.doThrow(new IllegalStateException("TLS start failed"))
                        .when(server).start());
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            WebhookServer server = servers.constructed().getFirst();

            IllegalStateException exception = assertThrows(IllegalStateException.class, main::start);

            assertEquals("TLS start failed", exception.getMessage());
            main.stop();
            verify(server).stop();
            org.mockito.Mockito.verifyNoInteractions(registrations.constructed().getFirst());
            verify(fixture.client()).close();
            assertTrue(!main.metricsHealthServer().healthServer().isReady());
        }
    }

    @Test
    void failedConversionPatchKeepsWebhookNotReadyAndStopsTheStartedServer() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, true,
                "configured-service", "service-ns", tempDir.resolve("failed-conversion-certs"),
                tempDir.resolve("failed-conversion-ca.crt"), false, false, true, null, null);

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class, (registration, context) ->
                                org.mockito.Mockito.doThrow(new IllegalStateException("conversion patch failed"))
                                        .when(registration).patchConversionWebhookClientConfig(anyString(),
                                                any(Path.class), anyString(), anyString(),
                                                org.mockito.ArgumentMatchers.anyInt()))) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            WebhookServer server = servers.constructed().getFirst();
            WebhookSelfRegistration registration = registrations.constructed().getFirst();

            IllegalStateException exception = assertThrows(IllegalStateException.class, main::start);

            assertEquals("conversion patch failed", exception.getMessage());
            main.stop();
            verify(server).start();
            verify(server).stop();
            verify(registration, org.mockito.Mockito.never()).register(any());
            verify(fixture.client()).close();
            assertTrue(!main.metricsHealthServer().healthServer().isReady());
        }
    }

    @Test
    void failedAdmissionRegistrationKeepsWebhookNotReadyAndStopsTheStartedServer() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, false,
                "configured-service", "service-ns", tempDir.resolve("failed-registration-certs"),
                tempDir.resolve("failed-registration-ca.crt"), false, false, true, null, null);

        try (MockedConstruction<WebhookServer> servers = org.mockito.Mockito.mockConstruction(WebhookServer.class,
                (server, context) -> when(server.address()).thenReturn(new InetSocketAddress(0)));
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class, (registration, context) ->
                                org.mockito.Mockito.doThrow(new IllegalStateException("admission registration failed"))
                                        .when(registration).register(any()))) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            WebhookServer server = servers.constructed().getFirst();

            IllegalStateException exception = assertThrows(IllegalStateException.class, main::start);

            assertEquals("admission registration failed", exception.getMessage());
            main.stop();
            verify(server).start();
            verify(server).stop();
            verify(fixture.client()).close();
            assertTrue(!main.metricsHealthServer().healthServer().isReady());
        }
    }

    @Test
    void createFailureAfterWebhookBindingReleasesBothServerPorts() throws IOException {
        ClientFixture fixture = client();
        int metricsPort;
        int webhookPort;
        try (ServerSocket metricsReservation = new ServerSocket(0);
                ServerSocket webhookReservation = new ServerSocket(0)) {
            metricsPort = metricsReservation.getLocalPort();
            webhookPort = webhookReservation.getLocalPort();
        }
        EchoOperatorMain.OperatorConfig config = new EchoOperatorMain.OperatorConfig(
                "watched-ns", "pod-ns", metricsPort, false, "watched-ns", "echo-operator-lock",
                tempDir.resolve("create-failure-ca.crt"), true, true, true, true,
                true, "echo-operator-webhook-ca", "configured-service", "service-ns",
                tempDir.resolve("create-failure-certs"), webhookPort, 9443,
                false, false, true, null, null);

        try (MockedConstruction<WebhookSelfRegistration> registrations = org.mockito.Mockito.mockConstruction(
                WebhookSelfRegistration.class, (registration, context) -> {
                    throw new IllegalStateException("registration construction failed");
                })) {
            assertThrows(RuntimeException.class, () -> EchoOperatorMain.create(fixture.client(), config));
        }

        try (ServerSocket metricsProbe = new ServerSocket(metricsPort);
                ServerSocket webhookProbe = new ServerSocket(webhookPort)) {
            assertTrue(metricsProbe.isBound());
            assertTrue(webhookProbe.isBound());
        }
    }

    @Test
    void stopBeforeStartClosesEveryControllerOwnedResourceAtMostOnce() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", false, false,
                "configured-service", "service-ns", tempDir.resolve("pre-start-certs"),
                tempDir.resolve("pre-start-ca.crt"), true, false, false, null, null);

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<EventRecorder> recorders = org.mockito.Mockito.mockConstruction(EventRecorder.class);
                MockedConstruction<MetricsHealthServer> metrics =
                        org.mockito.Mockito.mockConstruction(MetricsHealthServer.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);

            main.stop();
            main.stop();

            verify(recorders.constructed().getFirst()).close();
            verify(operators.constructed().getFirst()).stop();
            verify(metrics.constructed().getFirst()).close();
            verify(fixture.client(), org.mockito.Mockito.times(1)).close();
        }
    }

    @Test
    void controllerStartupFailureBeforeOperatorStartClosesSharedClientExactlyOnce() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", false, false,
                "configured-service", "service-ns", tempDir.resolve("failed-controller-certs"),
                tempDir.resolve("failed-controller-ca.crt"), true, false, false, null, null);

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<EventRecorder> recorders = org.mockito.Mockito.mockConstruction(EventRecorder.class);
                MockedConstruction<MetricsHealthServer> metrics = org.mockito.Mockito.mockConstruction(
                        MetricsHealthServer.class, (server, context) -> org.mockito.Mockito
                                .doThrow(new IllegalStateException("metrics start failed")).when(server).start())) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);

            IllegalStateException exception = assertThrows(IllegalStateException.class, main::start);
            main.stop();

            assertEquals("metrics start failed", exception.getMessage());
            verify(recorders.constructed().getFirst()).close();
            verify(operators.constructed().getFirst()).stop();
            verify(metrics.constructed().getFirst()).close();
            verify(fixture.client(), org.mockito.Mockito.times(1)).close();
        }
    }

    @Test
    void leaderElectionControllerStopIsIdempotentWithoutLeaderManagerCloseContract() throws IOException {
        ClientFixture fixture = client();
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", false, false,
                "configured-service", "service-ns", tempDir.resolve("leader-certs"),
                tempDir.resolve("leader-ca.crt"), true, false, false, null, null, true);

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<EventRecorder> recorders = org.mockito.Mockito.mockConstruction(EventRecorder.class);
                MockedConstruction<LeaderElectionManager> leaders = org.mockito.Mockito.mockConstruction(
                        LeaderElectionManager.class, (leader, context) -> org.mockito.Mockito.doAnswer(invocation -> {
                            invocation.<Runnable>getArgument(0).run();
                            return null;
                        }).when(leader).run(any(Runnable.class)))) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);
            Operator operator = operators.constructed().getFirst();

            main.start();
            main.stop();
            main.stop();

            verify(leaders.constructed().getFirst()).run(any(Runnable.class));
            verify(operator).start();
            verify(operator).stop();
            verify(recorders.constructed().getFirst()).close();
            verify(fixture.client()).close();
        }
    }

    @Test
    void autoGeneratedCertificatesUsePodNamespaceAndStartPatchesConfiguredConversionService() throws IOException {
        ClientFixture fixture = client();
        Path certDirectory = tempDir.resolve("generated-certs");
        EchoOperatorMain.OperatorConfig config = config("watched-ns", "pod-ns", true, true,
                "configured-service", "service-ns", certDirectory, tempDir.resolve("fallback-ca.crt"));

        try (MockedConstruction<Operator> operators = mockOperatorConstruction(fixture.client());
                MockedConstruction<WebhookSelfRegistration> registrations =
                        org.mockito.Mockito.mockConstruction(WebhookSelfRegistration.class)) {
            EchoOperatorMain main = EchoOperatorMain.create(fixture.client(), config);

            assertTrue(Files.exists(certDirectory.resolve("ca.crt")));
            assertTrue(Files.exists(certDirectory.resolve("tls.crt")));
            assertTrue(Files.exists(certDirectory.resolve("tls.key")));

            ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
            verify(fixture.namespacedSecrets()).resource(secretCaptor.capture());
            assertEquals("pod-ns", secretCaptor.getValue().getMetadata().getNamespace());

            main.start();
            WebhookSelfRegistration registration = registrations.constructed().getFirst();
            ArgumentCaptor<Path> caPath = ArgumentCaptor.forClass(Path.class);
            verify(registration).patchConversionWebhookClientConfig(eq("echoresources.example.com"), caPath.capture(),
                    eq("configured-service"), eq("service-ns"), eq(9443));
            assertTrue(caPath.getValue().startsWith(certDirectory));
            verify(operators.constructed().getFirst()).start();

            main.stop();
        }
    }

    @Test
    void admissionRegistrationUsesConfiguredServiceIdentityWithStableBaseName() throws IOException {
        ClientFixture firstFixture = client();
        ClientFixture secondFixture = client();
        Path firstCaPath = tempDir.resolve("first-ca.crt");
        Path secondCaPath = tempDir.resolve("second-ca.crt");
        Files.writeString(firstCaPath, "first-ca");
        Files.writeString(secondCaPath, "second-ca");
        EchoOperatorMain.OperatorConfig firstConfig = config("watched-ns", "pod-ns", true, true,
                "first-service", "first-service-ns", tempDir.resolve("first-certs"), firstCaPath);
        EchoOperatorMain.OperatorConfig secondConfig = config("watched-ns", "pod-ns", true, true,
                "second-service", "second-service-ns", tempDir.resolve("second-certs"), secondCaPath);
        List<WebhookRegistrationConfig> registrationConfigs = new ArrayList<>();
        List<EchoOperatorMain> mains = new ArrayList<>();

        try (MockedConstruction<WebhookSelfRegistration> registrations = org.mockito.Mockito.mockConstruction(
                WebhookSelfRegistration.class, (mock, context) -> registrationConfigs.add(
                        (WebhookRegistrationConfig) context.arguments().get(1)))) {
            mains.add(EchoOperatorMain.create(firstFixture.client(), firstConfig));
            mains.add(EchoOperatorMain.create(secondFixture.client(), secondConfig));

            assertEquals(2, registrationConfigs.size());
            assertRegistrationConfig(registrationConfigs.get(0), firstConfig);
            assertRegistrationConfig(registrationConfigs.get(1), secondConfig);
        }

        new WebhookSelfRegistration(firstFixture.client(), registrationConfigs.get(0))
                .register(mains.get(0).admissionHandler());
        new WebhookSelfRegistration(secondFixture.client(), registrationConfigs.get(1))
                .register(mains.get(1).admissionHandler());
        assertAdmissionRegistration(firstFixture, "first-service", "first-service-ns");
        assertAdmissionRegistration(secondFixture, "second-service", "second-service-ns");

        mains.get(0).stop();
        mains.get(1).stop();
    }

    private void assertRegistrationConfig(WebhookRegistrationConfig registrationConfig,
            EchoOperatorMain.OperatorConfig config) {
        assertEquals(config.webhookServiceName(), registrationConfig.serviceName());
        assertEquals(config.webhookServiceNamespace(), registrationConfig.serviceNamespace());
        assertEquals("echo-operator." + config.operatorNamespace(), registrationConfig.baseName());
        RuleWithOperations rule = registrationConfig.rules().getFirst();
        assertEquals(List.of("example.com"), rule.getApiGroups());
        assertEquals(List.of("v1alpha1", "v1alpha2"), rule.getApiVersions());
        assertEquals(List.of("CREATE", "UPDATE"), rule.getOperations());
        assertEquals(List.of("echoresources"), rule.getResources());
        assertEquals("Namespaced", rule.getScope());
    }

    private void assertAdmissionRegistration(ClientFixture fixture, String serviceName, String serviceNamespace) {
        ArgumentCaptor<ValidatingWebhookConfiguration> validatingCaptor = ArgumentCaptor.forClass(
                ValidatingWebhookConfiguration.class);
        verify(fixture.validating()).resource(validatingCaptor.capture());
        verify(fixture.validatingResource()).createOrReplace();
        ValidatingWebhookConfiguration validating = validatingCaptor.getValue();
        ValidatingWebhook validatingWebhook = validating.getWebhooks().getFirst();
        assertEquals("echo-operator.watched-ns.echo.example.com", validating.getMetadata().getName());
        assertEquals("echo-operator.watched-ns.echo.example.com", validatingWebhook.getName());
        assertEquals(serviceName, validatingWebhook.getClientConfig().getService().getName());
        assertEquals(serviceNamespace, validatingWebhook.getClientConfig().getService().getNamespace());
        assertEquals(9443, validatingWebhook.getClientConfig().getService().getPort());

        ArgumentCaptor<MutatingWebhookConfiguration> mutatingCaptor = ArgumentCaptor.forClass(
                MutatingWebhookConfiguration.class);
        verify(fixture.mutating()).resource(mutatingCaptor.capture());
        verify(fixture.mutatingResource()).createOrReplace();
        MutatingWebhookConfiguration mutating = mutatingCaptor.getValue();
        MutatingWebhook mutatingWebhook = mutating.getWebhooks().getFirst();
        assertEquals("echo-operator.watched-ns.echo.example.com", mutating.getMetadata().getName());
        assertEquals("echo-operator.watched-ns.echo.example.com", mutatingWebhook.getName());
        assertEquals(serviceName, mutatingWebhook.getClientConfig().getService().getName());
        assertEquals(serviceNamespace, mutatingWebhook.getClientConfig().getService().getNamespace());
        assertEquals(9443, mutatingWebhook.getClientConfig().getService().getPort());
    }

    private EchoOperatorMain.OperatorConfig config(String operatorNamespace, String operatorPodNamespace,
            boolean webhookEnabled, boolean autoGenerate, String serviceName, String serviceNamespace,
            Path certDirectory, Path caBundlePath) {
        return config(operatorNamespace, operatorPodNamespace, webhookEnabled, autoGenerate, serviceName,
                serviceNamespace, certDirectory, caBundlePath, true, true, true, null, null);
    }

    private EchoOperatorMain.OperatorConfig config(String operatorNamespace, String operatorPodNamespace,
            boolean webhookEnabled, boolean autoGenerate, String serviceName, String serviceNamespace,
            Path certDirectory, Path caBundlePath, boolean controllerEnabled, boolean cleanupEnabled,
            boolean selfRegistrationEnabled, String predecessorValidatingName, String predecessorMutatingName) {
        return config(operatorNamespace, operatorPodNamespace, webhookEnabled, autoGenerate, serviceName,
                serviceNamespace, certDirectory, caBundlePath, controllerEnabled, cleanupEnabled,
                selfRegistrationEnabled, predecessorValidatingName, predecessorMutatingName, false);
    }

    private EchoOperatorMain.OperatorConfig config(String operatorNamespace, String operatorPodNamespace,
            boolean webhookEnabled, boolean autoGenerate, String serviceName, String serviceNamespace,
            Path certDirectory, Path caBundlePath, boolean controllerEnabled, boolean cleanupEnabled,
            boolean selfRegistrationEnabled, String predecessorValidatingName, String predecessorMutatingName,
            boolean leaderElectionEnabled) {
        return new EchoOperatorMain.OperatorConfig(operatorNamespace, operatorPodNamespace, 0, leaderElectionEnabled,
                operatorNamespace, "echo-operator-lock", caBundlePath, webhookEnabled,
                true, true, true, autoGenerate,
                "echo-operator-webhook-ca", serviceName, serviceNamespace, certDirectory, 0, 9443,
                controllerEnabled, cleanupEnabled, selfRegistrationEnabled, predecessorValidatingName,
                predecessorMutatingName);
    }

    private MockedConstruction<Operator> mockOperatorConstruction(KubernetesClient client) {
        return org.mockito.Mockito.mockConstruction(Operator.class,
                (operator, context) -> {
                    AtomicBoolean started = new AtomicBoolean();
                    when(operator.withNamespace(anyString())).thenReturn(operator);
                    org.mockito.Mockito.doAnswer(invocation -> {
                        started.set(true);
                        return null;
                    }).when(operator).start();
                    org.mockito.Mockito.doAnswer(invocation -> {
                        if (started.get()) {
                            client.close();
                        }
                        return null;
                    }).when(operator).stop();
                });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ClientFixture client() {
        KubernetesClient client = mock(KubernetesClient.class);
        MixedOperation<Secret, SecretList, Resource<Secret>> secrets = mock(MixedOperation.class);
        NonNamespaceOperation<Secret, SecretList, Resource<Secret>> namespacedSecrets = mock(NonNamespaceOperation.class);
        Resource<Secret> secretResource = mock(Resource.class);
        AdmissionRegistrationAPIGroupDSL admission = mock(AdmissionRegistrationAPIGroupDSL.class);
        V1AdmissionRegistrationAPIGroupDSL admissionV1 = mock(V1AdmissionRegistrationAPIGroupDSL.class);
        NonNamespaceOperation<ValidatingWebhookConfiguration, ValidatingWebhookConfigurationList,
                Resource<ValidatingWebhookConfiguration>> validating = mock(NonNamespaceOperation.class);
        Resource<ValidatingWebhookConfiguration> validatingResource = mock(Resource.class);
        NonNamespaceOperation<MutatingWebhookConfiguration, MutatingWebhookConfigurationList,
                Resource<MutatingWebhookConfiguration>> mutating = mock(NonNamespaceOperation.class);
        Resource<MutatingWebhookConfiguration> mutatingResource = mock(Resource.class);
        when(client.secrets()).thenReturn(secrets);
        when(secrets.inNamespace(anyString())).thenReturn(namespacedSecrets);
        when(namespacedSecrets.withName(anyString())).thenReturn(secretResource);
        when(namespacedSecrets.resource(any(Secret.class))).thenReturn(secretResource);
        when(secretResource.get()).thenReturn(null);
        when(client.admissionRegistration()).thenReturn(admission);
        when(admission.v1()).thenReturn(admissionV1);
        when(admissionV1.validatingWebhookConfigurations()).thenReturn(validating);
        when(validating.resource(any(ValidatingWebhookConfiguration.class))).thenReturn(validatingResource);
        when(admissionV1.mutatingWebhookConfigurations()).thenReturn(mutating);
        when(mutating.resource(any(MutatingWebhookConfiguration.class))).thenReturn(mutatingResource);
        when(client.getKubernetesSerialization()).thenReturn(new KubernetesSerialization());
        return new ClientFixture(client, namespacedSecrets, validating, validatingResource, mutating, mutatingResource);
    }

    private record ClientFixture(KubernetesClient client,
            NonNamespaceOperation<Secret, SecretList, Resource<Secret>> namespacedSecrets,
            NonNamespaceOperation<ValidatingWebhookConfiguration, ValidatingWebhookConfigurationList,
                    Resource<ValidatingWebhookConfiguration>> validating,
            Resource<ValidatingWebhookConfiguration> validatingResource,
            NonNamespaceOperation<MutatingWebhookConfiguration, MutatingWebhookConfigurationList,
                    Resource<MutatingWebhookConfiguration>> mutating,
            Resource<MutatingWebhookConfiguration> mutatingResource) {
    }
}
