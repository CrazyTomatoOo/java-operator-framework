package com.huawei.dcs.modelengine.operator.framework.webhook.admission;

import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponseBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dispatches Kubernetes admission reviews to registered validators and mutators.
 */
public final class AdmissionHandler {
    private static final String API_VERSION = "admission.k8s.io/v1";
    private static final String KIND = "AdmissionReview";
    private static final String JSON_PATCH = "JSONPatch";

    private final KubernetesSerialization serialization;
    private final Map<String, ValidatorRegistration<?>> validators = new ConcurrentHashMap<>();
    private final Map<String, MutatorRegistration<?>> mutators = new ConcurrentHashMap<>();

    public AdmissionHandler(KubernetesClient client) {
        this(Objects.requireNonNull(client, "client must not be null").getKubernetesSerialization());
    }

    AdmissionHandler(KubernetesSerialization serialization) {
        this.serialization = Objects.requireNonNull(serialization, "serialization must not be null");
    }

    public <T extends HasMetadata> void registerValidator(String name, Class<T> resourceType,
            AdmissionValidator<T> validator) {
        validators.put(normalize(name), new ValidatorRegistration<>(resourceType, validator));
    }

    public <T extends HasMetadata> void registerMutator(String name, Class<T> resourceType, AdmissionMutator<T> mutator) {
        mutators.put(normalize(name), new MutatorRegistration<>(resourceType, mutator));
    }

    public void register(WebhookServer server) {
        Objects.requireNonNull(server, "server must not be null");
        validators.keySet().forEach(name -> server.register("/validate/" + name, validatingHandler(name)));
        mutators.keySet().forEach(name -> server.register("/mutate/" + name, mutatingHandler(name)));
    }

    public Set<String> validatorNames() {
        return Set.copyOf(validators.keySet());
    }

    public Set<String> mutatorNames() {
        return Set.copyOf(mutators.keySet());
    }

    public HttpHandler validatingHandler(String name) {
        return exchange -> handle(exchange, normalize(name), Operation.VALIDATE);
    }

    public HttpHandler mutatingHandler(String name) {
        return exchange -> handle(exchange, normalize(name), Operation.MUTATE);
    }

    public HttpHandler validatingHandler() {
        return exchange -> handle(exchange, lastPathSegment(exchange), Operation.VALIDATE);
    }

    public HttpHandler mutatingHandler() {
        return exchange -> handle(exchange, lastPathSegment(exchange), Operation.MUTATE);
    }

    private void handle(HttpExchange exchange, String name, Operation operation) throws IOException {
        AdmissionReview responseReview;
        try {
            AdmissionReview review = serialization.unmarshal(exchange.getRequestBody(), AdmissionReview.class);
            AdmissionRequest request = review == null ? null : review.getRequest();
            AdmissionResponse response = dispatch(name, request, operation);
            responseReview = new AdmissionReviewBuilder()
                    .withApiVersion(API_VERSION)
                    .withKind(KIND)
                    .withResponse(response)
                    .build();
        } catch (RuntimeException exception) {
            responseReview = responseReview(deny(null, "Failed to handle admission review: " + exception.getMessage()));
        }
        writeJson(exchange, responseReview);
    }

    private AdmissionResponse dispatch(String name, AdmissionRequest request, Operation operation) {
        if (request == null) {
            return deny(null, "AdmissionReview request is missing");
        }
        if (operation == Operation.VALIDATE) {
            ValidatorRegistration<?> registration = validators.get(name);
            if (registration == null) {
                return deny(request.getUid(), "No admission validator registered for path '" + name + "'");
            }
            return withUid(request, registration.validate(request, resource(request, registration.resourceType())));
        }
        MutatorRegistration<?> registration = mutators.get(name);
        if (registration == null) {
            return deny(request.getUid(), "No admission mutator registered for path '" + name + "'");
        }
        return withEncodedPatch(request, registration.mutate(request, resource(request, registration.resourceType())));
    }

    private <T extends HasMetadata> T resource(AdmissionRequest request, Class<T> resourceType) {
        Object object = request.getObject();
        if (object == null) {
            throw new IllegalArgumentException("AdmissionRequest object is missing");
        }
        return serialization.convertValue(object, resourceType);
    }

    private AdmissionResponse withUid(AdmissionRequest request, AdmissionResponse response) {
        AdmissionResponseBuilder builder = new AdmissionResponseBuilder(response == null ? AdmissionResult.allowed() : response)
                .withUid(request.getUid());
        if (builder.getAllowed() == null) {
            builder.withAllowed(true);
        }
        return builder.build();
    }

    private AdmissionResponse withEncodedPatch(AdmissionRequest request, AdmissionResponse response) {
        AdmissionResponseBuilder builder = new AdmissionResponseBuilder(withUid(request, response));
        String patch = builder.getPatch();
        if (patch != null && !patch.isEmpty()) {
            builder.withPatch(Base64.getEncoder().encodeToString(patch.getBytes(StandardCharsets.UTF_8)))
                    .withPatchType(JSON_PATCH);
        }
        return builder.build();
    }

    private static AdmissionReview responseReview(AdmissionResponse response) {
        return new AdmissionReviewBuilder().withApiVersion(API_VERSION).withKind(KIND).withResponse(response).build();
    }

    private static AdmissionResponse deny(String uid, String message) {
        return new AdmissionResponseBuilder()
                .withUid(uid)
                .withAllowed(false)
                .withStatus(new StatusBuilder().withMessage(message).build())
                .build();
    }

    private void writeJson(HttpExchange exchange, AdmissionReview responseReview) throws IOException {
        byte[] bytes = serialization.asJson(responseReview).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private static String normalize(String name) {
        String normalized = Objects.requireNonNull(name, "name must not be null").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return normalized.startsWith("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
    }

    private static String lastPathSegment(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        int index = path.lastIndexOf('/');
        return normalize(index >= 0 ? path.substring(index + 1) : path);
    }

    private enum Operation {
        VALIDATE,
        MUTATE
    }

    private record ValidatorRegistration<T extends HasMetadata>(Class<T> resourceType, AdmissionValidator<T> validator) {
        private ValidatorRegistration {
            Objects.requireNonNull(resourceType, "resourceType must not be null");
            Objects.requireNonNull(validator, "validator must not be null");
        }

        private AdmissionResponse validate(AdmissionRequest request, HasMetadata resource) {
            return validator.validate(request, resourceType.cast(resource));
        }
    }

    private record MutatorRegistration<T extends HasMetadata>(Class<T> resourceType, AdmissionMutator<T> mutator) {
        private MutatorRegistration {
            Objects.requireNonNull(resourceType, "resourceType must not be null");
            Objects.requireNonNull(mutator, "mutator must not be null");
        }

        private AdmissionResponse mutate(AdmissionRequest request, HasMetadata resource) {
            return mutator.mutate(request, resourceType.cast(resource));
        }
    }
}
