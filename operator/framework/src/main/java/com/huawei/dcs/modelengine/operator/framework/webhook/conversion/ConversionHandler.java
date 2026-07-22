package com.huawei.dcs.modelengine.operator.framework.webhook.conversion;

import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionRequest;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionResponse;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionResponseBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReview;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP handler for Kubernetes CRD conversion webhook requests.
 */
public final class ConversionHandler implements HttpHandler {
    private static final String API_VERSION = "apiextensions.k8s.io/v1";
    private static final String KIND = "ConversionReview";
    private static final String SUCCESS = "Success";
    private static final String FAILURE = "Failure";
    private static final Pattern UID_PATTERN = Pattern.compile("\\\"uid\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final KubernetesSerialization serialization;
    private final Map<VersionPair, ConversionWebhookHandler> converters = new ConcurrentHashMap<>();

    public ConversionHandler(KubernetesClient client) {
        this(Objects.requireNonNull(client, "client must not be null").getKubernetesSerialization());
    }

    ConversionHandler(KubernetesSerialization serialization) {
        this.serialization = Objects.requireNonNull(serialization, "serialization must not be null");
    }

    public void register(String sourceVersion, String targetVersion, ConversionWebhookHandler handler) {
        converters.put(new VersionPair(sourceVersion, targetVersion),
                Objects.requireNonNull(handler, "handler must not be null"));
    }

    public void register(WebhookServer server) {
        Objects.requireNonNull(server, "server must not be null").register("/convert", this);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        ConversionReview responseReview;
        try {
            ConversionReview review = serialization.unmarshal(new ByteArrayInputStream(requestBody), ConversionReview.class);
            ConversionResponse response = dispatch(review == null ? null : review.getRequest());
            responseReview = responseReview(response);
        } catch (RuntimeException exception) {
            String uid = extractUid(new String(requestBody, StandardCharsets.UTF_8));
            responseReview = responseReview(failure(uid, "Failed to handle conversion review: " + exception.getMessage()));
        }
        writeJson(exchange, responseReview);
    }

    private ConversionResponse dispatch(ConversionRequest request) {
        if (request == null) {
            return failure(null, "ConversionReview request is missing");
        }
        String desiredVersion = request.getDesiredAPIVersion();
        if (desiredVersion == null || desiredVersion.isBlank()) {
            return failure(request.getUid(), "ConversionRequest desiredAPIVersion is missing");
        }
        List<Object> objects = request.getObjects();
        if (objects == null || objects.isEmpty()) {
            return failure(request.getUid(), "ConversionRequest objects are missing");
        }

        List<Object> convertedObjects = new ArrayList<>(objects.size());
        for (Object object : objects) {
            HasMetadata resource = resource(object);
            String sourceVersion = resource.getApiVersion();
            if (sourceVersion == null || sourceVersion.isBlank()) {
                return failure(request.getUid(), "ConversionRequest object apiVersion is missing");
            }
            ConversionResult result = convert(sourceVersion, desiredVersion, resource);
            if (!result.successful()) {
                return failure(request.getUid(), String.join("; ", result.errors()));
            }
            convertedObjects.add(result.convertedObject());
        }
        return new ConversionResponseBuilder()
                .withUid(request.getUid())
                .withConvertedObjects(convertedObjects)
                .withResult(new StatusBuilder().withStatus(SUCCESS).build())
                .build();
    }

    private ConversionResult convert(String sourceVersion, String desiredVersion, HasMetadata resource) {
        if (sourceVersion.equals(desiredVersion)) {
            return ConversionResult.converted(resource);
        }
        ConversionWebhookHandler handler = converters.get(new VersionPair(sourceVersion, desiredVersion));
        if (handler == null) {
            return ConversionResult.failed("No converter registered for " + sourceVersion + " -> " + desiredVersion);
        }
        ConversionResult result = handler.convert(desiredVersion, resource);
        return result == null ? ConversionResult.failed("Converter returned null for " + sourceVersion + " -> "
                + desiredVersion) : result;
    }

    private HasMetadata resource(Object object) {
        if (object == null) {
            throw new IllegalArgumentException("ConversionRequest object is missing");
        }
        if (object instanceof HasMetadata resource) {
            return resource;
        }
        return serialization.convertValue(object, GenericKubernetesResource.class);
    }

    private static ConversionReview responseReview(ConversionResponse response) {
        return new ConversionReviewBuilder().withApiVersion(API_VERSION).withKind(KIND).withResponse(response).build();
    }

    private static ConversionResponse failure(String uid, String message) {
        return new ConversionResponseBuilder()
                .withUid(uid)
                .withResult(new StatusBuilder().withStatus(FAILURE).withMessage(message).build())
                .build();
    }

    private void writeJson(HttpExchange exchange, ConversionReview responseReview) throws IOException {
        byte[] bytes = serialization.asJson(responseReview).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private static String extractUid(String requestBody) {
        Matcher matcher = UID_PATTERN.matcher(requestBody);
        return matcher.find() ? matcher.group(1) : null;
    }

    private record VersionPair(String sourceVersion, String targetVersion) {
        private VersionPair {
            sourceVersion = normalizeVersion(sourceVersion, "sourceVersion");
            targetVersion = normalizeVersion(targetVersion, "targetVersion");
        }

        private static String normalizeVersion(String version, String name) {
            String normalized = Objects.requireNonNull(version, name + " must not be null").strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return normalized;
        }
    }
}
