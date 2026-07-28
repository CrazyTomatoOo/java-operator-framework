package com.huawei.dcs.modelengine.operator.framework.webhook.conversion;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionRequest;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReview;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReviewBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionHandlerTest {
    private static final String V1ALPHA1 = "example.com/v1alpha1";
    private static final String V1ALPHA2 = "example.com/v1alpha2";

    private final KubernetesSerialization serialization = new KubernetesSerialization();

    @Test
    void convertsV1Alpha1ToV1Alpha2ThroughHttpEndpoint() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        handler.register(V1ALPHA1, V1ALPHA2, (desiredVersion, resource) -> ConversionResult.converted(
                resource(desiredVersion, resource.getMetadata().getName(), "INFO")));

        ConversionReview response = post(handler, review("alpha1-uid", V1ALPHA2,
                resource(V1ALPHA1, "echo-alpha1", null)));

        assertEquals("alpha1-uid", response.getResponse().getUid());
        assertEquals("Success", response.getResponse().getResult().getStatus());
        GenericKubernetesResource converted = convertedObject(response);
        assertEquals(V1ALPHA2, converted.getApiVersion());
        assertEquals("echo-alpha1", converted.getMetadata().getName());
        assertEquals("INFO", spec(converted).get("logLevel"));
    }

    @Test
    void convertsV1Alpha2ToV1Alpha1ThroughHttpEndpoint() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        handler.register(V1ALPHA2, V1ALPHA1, (desiredVersion, resource) -> ConversionResult.converted(
                resource(desiredVersion, resource.getMetadata().getName(), null)));

        ConversionReview response = post(handler, review("alpha2-uid", V1ALPHA1,
                resource(V1ALPHA2, "echo-alpha2", "DEBUG")));

        assertEquals("alpha2-uid", response.getResponse().getUid());
        assertEquals("Success", response.getResponse().getResult().getStatus());
        GenericKubernetesResource converted = convertedObject(response);
        assertEquals(V1ALPHA1, converted.getApiVersion());
        assertEquals("echo-alpha2", converted.getMetadata().getName());
        assertEquals(false, spec(converted).containsKey("logLevel"));
    }

    @Test
    void unregisteredVersionPairReturnsFailureStatus() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);

        ConversionReview response = post(handler, review("missing-uid", V1ALPHA2,
                resource(V1ALPHA1, "missing", null)));

        assertEquals("missing-uid", response.getResponse().getUid());
        assertEquals("Failure", response.getResponse().getResult().getStatus());
        assertEquals("No converter registered for example.com/v1alpha1 -> example.com/v1alpha2",
                response.getResponse().getResult().getMessage());
    }

    @Test
    void sameSourceAndTargetVersionReturnsObjectWithoutRegisteredConverter() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);

        ConversionReview response = post(handler, review("same-uid", V1ALPHA1,
                resource(V1ALPHA1, "same-version", "TRACE")));

        assertEquals("same-uid", response.getResponse().getUid());
        assertEquals("Success", response.getResponse().getResult().getStatus());
        GenericKubernetesResource converted = convertedObject(response);
        assertEquals(V1ALPHA1, converted.getApiVersion());
        assertEquals("same-version", converted.getMetadata().getName());
        assertEquals("TRACE", spec(converted).get("logLevel"));
    }

    @Test
    void convertsEveryObjectInOneConversionRequest() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        handler.register(V1ALPHA1, V1ALPHA2, (desiredVersion, resource) -> ConversionResult.converted(
                resource(desiredVersion, resource.getMetadata().getName(), "INFO")));

        ConversionReview response = post(handler, review("batch-uid", V1ALPHA2, List.of(
                resource(V1ALPHA1, "first", null),
                resource(V1ALPHA1, "second", null))));

        assertEquals("batch-uid", response.getResponse().getUid());
        assertEquals("Success", response.getResponse().getResult().getStatus());
        assertNotNull(response.getResponse().getConvertedObjects());
        assertEquals(2, response.getResponse().getConvertedObjects().size());
        GenericKubernetesResource first = convertedObject(response, 0);
        GenericKubernetesResource second = convertedObject(response, 1);
        assertEquals(V1ALPHA2, first.getApiVersion());
        assertEquals("first", first.getMetadata().getName());
        assertEquals("INFO", spec(first).get("logLevel"));
        assertEquals(V1ALPHA2, second.getApiVersion());
        assertEquals("second", second.getMetadata().getName());
        assertEquals("INFO", spec(second).get("logLevel"));
    }

    @Test
    void converterReturningNullReturnsFailureStatus() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        handler.register(V1ALPHA1, V1ALPHA2, (desiredVersion, resource) -> null);

        ConversionReview response = post(handler, review("null-result-uid", V1ALPHA2,
                resource(V1ALPHA1, "null-result", null)));

        assertEquals("null-result-uid", response.getResponse().getUid());
        assertEquals("Failure", response.getResponse().getResult().getStatus());
        assertEquals("Converter returned null for example.com/v1alpha1 -> example.com/v1alpha2",
                response.getResponse().getResult().getMessage());
    }

    @Test
    void missingDesiredApiVersionReturnsFailureStatus() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        ConversionRequest request = new ConversionRequest();
        request.setUid("missing-version-uid");
        request.setObjects(List.of(resource(V1ALPHA1, "missing-version", null)));

        ConversionReview response = post(handler, review(request));

        assertEquals("missing-version-uid", response.getResponse().getUid());
        assertEquals("Failure", response.getResponse().getResult().getStatus());
        assertEquals("ConversionRequest desiredAPIVersion is missing", response.getResponse().getResult().getMessage());
    }

    @Test
    void missingObjectsReturnsFailureStatus() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        ConversionRequest request = new ConversionRequest();
        request.setUid("missing-objects-uid");
        request.setDesiredAPIVersion(V1ALPHA2);

        ConversionReview response = post(handler, review(request));

        assertEquals("missing-objects-uid", response.getResponse().getUid());
        assertEquals("Failure", response.getResponse().getResult().getStatus());
        assertEquals("ConversionRequest objects are missing", response.getResponse().getResult().getMessage());
    }

    @Test
    void malformedJsonReturnsFailureStatusWithExtractedUid() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);

        ConversionReview response = postRaw(handler, "{\"request\":{\"uid\":\"malformed-uid\",\"objects\":");

        assertEquals("malformed-uid", response.getResponse().getUid());
        assertEquals("Failure", response.getResponse().getResult().getStatus());
    }

    @Test
    void conversionWebhookHandlerCanBeUsedAsFunctionalInterface() {
        GenericKubernetesResource resource = resource(V1ALPHA1, "functional", null);
        ConversionWebhookHandler handler = (desiredVersion, input) -> ConversionResult.converted(
                resource(desiredVersion, input.getMetadata().getName(), "INFO"));

        ConversionResult result = handler.convert(V1ALPHA2, resource);

        assertEquals(true, result.successful());
        assertEquals(V1ALPHA2, result.convertedObject().getApiVersion());
        assertEquals("functional", result.convertedObject().getMetadata().getName());
    }

    @Test
    void disabledHandlerReturnsFailureStatus() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        handler.register(V1ALPHA1, V1ALPHA2, (desiredVersion, resource) -> ConversionResult.converted(
                resource(desiredVersion, resource.getMetadata().getName(), "INFO")));
        handler.disable();

        ConversionReview response = post(handler, review("disabled-uid", V1ALPHA2,
                resource(V1ALPHA1, "echo-disabled", null)));

        assertEquals("disabled-uid", response.getResponse().getUid());
        assertEquals("Failure", response.getResponse().getResult().getStatus());
        assertEquals("Conversion webhook is disabled", response.getResponse().getResult().getMessage());
    }

    @Test
    void reEnablingHandlerResumesConversions() throws Exception {
        ConversionHandler handler = new ConversionHandler(serialization);
        handler.register(V1ALPHA1, V1ALPHA2, (desiredVersion, resource) -> ConversionResult.converted(
                resource(desiredVersion, resource.getMetadata().getName(), "INFO")));
        handler.disable();

        handler.enable();
        ConversionReview response = post(handler, review("re-enabled-uid", V1ALPHA2,
                resource(V1ALPHA1, "echo-re-enabled", null)));

        assertEquals("re-enabled-uid", response.getResponse().getUid());
        assertEquals("Success", response.getResponse().getResult().getStatus());
    }

    @Test
    void isEnabledReflectsCurrentState() {
        ConversionHandler handler = new ConversionHandler(serialization);

        assertTrue(handler.isEnabled());
        handler.disable();
        assertFalse(handler.isEnabled());
        handler.enable();
        assertTrue(handler.isEnabled());
    }

    private ConversionReview post(ConversionHandler handler, ConversionReview review) throws Exception {
        return postRaw(handler, serialization.asJson(review));
    }

    private ConversionReview postRaw(ConversionHandler handler, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/convert", handler);
        server.start();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/convert"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
            return serialization.unmarshal(response.body(), ConversionReview.class);
        } finally {
            server.stop(0);
        }
    }

    private ConversionReview review(String uid, String desiredVersion, GenericKubernetesResource resource) {
        return review(uid, desiredVersion, List.of(resource));
    }

    private ConversionReview review(String uid, String desiredVersion, List<GenericKubernetesResource> resources) {
        ConversionRequest request = new ConversionRequest();
        request.setUid(uid);
        request.setDesiredAPIVersion(desiredVersion);
        request.setObjects(resources.stream().map(resource -> (Object) resource).toList());
        return review(request);
    }

    private ConversionReview review(ConversionRequest request) {
        return new ConversionReviewBuilder()
                .withApiVersion("apiextensions.k8s.io/v1")
                .withKind("ConversionReview")
                .withRequest(request)
                .build();
    }

    private GenericKubernetesResource convertedObject(ConversionReview response) {
        assertNotNull(response.getResponse().getConvertedObjects());
        assertEquals(1, response.getResponse().getConvertedObjects().size());
        return convertedObject(response, 0);
    }

    private GenericKubernetesResource convertedObject(ConversionReview response, int index) {
        return serialization.convertValue(response.getResponse().getConvertedObjects().get(index), GenericKubernetesResource.class);
    }

    private GenericKubernetesResource resource(String apiVersion, String name, String logLevel) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion(apiVersion);
        resource.setKind("EchoResource");
        resource.setMetadata(new ObjectMetaBuilder().withName(name).build());
        if (logLevel == null) {
            resource.setAdditionalProperty("spec", Map.of("message", "hello"));
        } else {
            resource.setAdditionalProperty("spec", Map.of("message", "hello", "logLevel", logLevel));
        }
        return resource;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> spec(GenericKubernetesResource resource) {
        return (Map<String, Object>) resource.getAdditionalProperties().get("spec");
    }
}
