/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.webhook;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.MutationResult;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionReview;
import io.fabric8.zjsonpatch.JsonDiff;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Kubernetes v1 admission transport for typed Spring callback beans.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@RestController
@RequiredArgsConstructor
public final class AdmissionWebhookController {
    private static final String BASE_PATH = "/operator-framework/webhooks";
    private static final String CALLBACK_FAILED = "webhook callback failed";

    private final WebhookCallbackRegistry callbacks;
    private final ObjectMapper objectMapper;
    private final OperatorFrameworkMetrics metrics;


    /**
     * Handles a validating AdmissionReview by dispatching it to the named validator callback.
     *
     * @param name the validator route name, matching the callback bean name
     * @param review the admission review carrying the resource under admission
     * @return the review response with the admission decision, or 400 for an unknown name or a
     *     malformed review
     */
    @PostMapping(BASE_PATH + "/validate/{name}")
    public ResponseEntity<AdmissionReview> validate(
            @PathVariable String name,
            @RequestBody AdmissionReview review) {
        var callback = callbacks.validator(name);
        if (callback.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(validate(review, callback.orElseThrow()));
        } catch (MalformedReviewException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Handles a mutating AdmissionReview by dispatching it to the named mutator callback.
     *
     * @param name the mutator route name, matching the callback bean name
     * @param review the admission review carrying the resource under admission
     * @return the review response with the admission decision and optional JSON patch, or 400 for an
     *     unknown name or a malformed review
     */
    @PostMapping(BASE_PATH + "/mutate/{name}")
    public ResponseEntity<AdmissionReview> mutate(
            @PathVariable String name,
            @RequestBody AdmissionReview review) {
        var callback = callbacks.mutator(name);
        if (callback.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(mutate(review, callback.orElseThrow()));
        } catch (MalformedReviewException exception) {
            return ResponseEntity.badRequest().build();
        }
    }

    private AdmissionReview validate(AdmissionReview review, WebhookCallbackRegistry.Callback callback) {
        var invocation = invocation(review, callback);
        var started = System.nanoTime();
        var outcome = "error";
        try {
            requireResourceType(invocation.current(), callback);
            var decision = invokeValidator(callback, invocation);
            outcome = decision.isAllowed() ? "allowed" : "denied";
            return decision.isAllowed()
                    ? response(invocation.request().getUid(), true, null, null)
                    : response(invocation.request().getUid(), false, decision.message().orElse("denied"), null);
        } catch (ResourceTypeMismatchException exception) {
            outcome = "denied";
            return response(invocation.request().getUid(), false, "resource type does not match callback", null);
        } catch (Exception exception) {
            callbacks.recordFailure("validator", callback.name());
            return response(invocation.request().getUid(), false, CALLBACK_FAILED, null);
        } finally {
            metrics.callback("validator", callback.name(), outcome, System.nanoTime() - started);
        }
    }

    private AdmissionReview mutate(AdmissionReview review, WebhookCallbackRegistry.Callback callback) {
        var invocation = invocation(review, callback);
        var started = System.nanoTime();
        var outcome = "error";
        try {
            requireResourceType(invocation.current(), callback);
            var result = invokeMutator(callback, invocation);
            // ponytail: enum names already match the metric outcomes (MUTATED/DENIED/UNCHANGED)
            outcome = result.status().name().toLowerCase(java.util.Locale.ROOT);
            return mutationResponse(invocation, result, callback);
        } catch (ResourceTypeMismatchException exception) {
            outcome = "denied";
            return response(invocation.request().getUid(), false, "resource type does not match callback", null);
        } catch (Exception exception) {
            callbacks.recordFailure("mutator", callback.name());
            return response(invocation.request().getUid(), false, CALLBACK_FAILED, null);
        } finally {
            metrics.callback("mutator", callback.name(), outcome, System.nanoTime() - started);
        }
    }

    private AdmissionReview mutationResponse(
            Invocation invocation,
            MutationResult<?> result,
            WebhookCallbackRegistry.Callback callback) throws Exception {
        Objects.requireNonNull(result, "mutator result must not be null");
        return switch (result.status()) {
            case UNCHANGED -> response(invocation.request().getUid(), true, null, null);
            case DENIED -> response(invocation.request().getUid(), false, result.message().orElse("denied"), null);
            case MUTATED -> mutatedResponse(invocation, result.resource().orElseThrow(), callback);
        };
    }

    private AdmissionReview mutatedResponse(
            Invocation invocation,
            Object mutated,
            WebhookCallbackRegistry.Callback callback) throws Exception {
        requireMutationOutput(invocation.current(), mutated, callback);
        return response(invocation.request().getUid(), true, null, patch(invocation.original(), mutated));
    }

    private void requireMutationOutput(
            HasMetadata current,
            Object mutated,
            WebhookCallbackRegistry.Callback callback) {
        if (!callback.resourceType().isInstance(mutated)) {
            throw new ResourceTypeMismatchException();
        }
        var original = objectMapper.valueToTree(current);
        var changed = objectMapper.valueToTree(mutated);
        for (var path : List.of("/apiVersion", "/kind", "/metadata/name",
                "/metadata/namespace", "/metadata/uid")) {
            if (!Objects.equals(original.at(path), changed.at(path))) {
                throw new ResourceTypeMismatchException();
            }
        }
    }

    private Invocation invocation(AdmissionReview review, WebhookCallbackRegistry.Callback callback) {
        try {
            var request = requireRequest(review);
            var source = request.getObject() == null ? request.getOldObject() : request.getObject();
            if (source == null) {
                throw new IllegalArgumentException("admission object is missing");
            }
            var current = objectMapper.convertValue(source, callback.resourceType());
            var original = objectMapper.valueToTree(current);
            return new Invocation(request, current, original, context(request, current));
        } catch (RuntimeException exception) {
            throw new MalformedReviewException(exception);
        }
    }

    private void requireResourceType(HasMetadata resource, WebhookCallbackRegistry.Callback callback) {
        var expectedApiVersion = HasMetadata.getApiVersion(callback.resourceType());
        var expectedKind = HasMetadata.getKind(callback.resourceType());
        if (!Objects.equals(resource.getApiVersion(), expectedApiVersion)
                || !Objects.equals(resource.getKind(), expectedKind)) {
            throw new ResourceTypeMismatchException();
        }
    }

    private AdmissionRequest requireRequest(AdmissionReview review) {
        var request = Objects.requireNonNull(review, "review must not be null").getRequest();
        Objects.requireNonNull(request, "admission request must not be null");
        requireText(request.getUid(), "admission uid");
        requireText(request.getOperation(), "admission operation");
        return request;
    }

    private AdmissionContext context(AdmissionRequest request, HasMetadata current) {
        var user = Objects.requireNonNull(request.getUserInfo(), "user info must not be null");
        var identity = new AdmissionContext.UserIdentity(
                user.getUsername(), user.getUid(), list(user.getGroups()), extra(user.getExtra()));
        return new AdmissionContext(request.getUid(), request.getOperation(), ResourceReference.from(current),
                Boolean.TRUE.equals(request.getDryRun()), identity);
    }

    private AdmissionReview response(String uid, boolean allowed, String message, String patch) {
        var admissionResponse = new AdmissionResponse();
        admissionResponse.setUid(uid);
        admissionResponse.setAllowed(allowed);
        if (message != null) {
            admissionResponse.setStatus(new StatusBuilder().withStatus("Failure").withMessage(message).build());
        }
        if (patch != null) {
            admissionResponse.setPatchType("JSONPatch");
            admissionResponse.setPatch(patch);
        }
        var review = new AdmissionReview();
        review.setApiVersion("admission.k8s.io/v1");
        review.setKind("AdmissionReview");
        review.setResponse(admissionResponse);
        return review;
    }

    private String patch(JsonNode original, Object mutated) throws Exception {
        var diff = JsonDiff.asJson(original, objectMapper.valueToTree(mutated));
        if (diff.isEmpty()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(diff));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private AdmissionDecision invokeValidator(
            WebhookCallbackRegistry.Callback callback,
            Invocation invocation) throws Exception {
        return Objects.requireNonNull(((AdmissionValidator) callback.bean())
                .validate(invocation.current(), invocation.context()), "validator result must not be null");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MutationResult<?> invokeMutator(
            WebhookCallbackRegistry.Callback callback,
            Invocation invocation) throws Exception {
        return (MutationResult<?>) ((AdmissionMutator) callback.bean())
                .mutate(invocation.current(), invocation.context());
    }

    private List<String> list(List<String> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, List<String>> extra(Map<String, List<String>> values) {
        return values == null ? Map.of() : values;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private record Invocation(
            AdmissionRequest request,
            HasMetadata current,
            JsonNode original,
            AdmissionContext context) {
    }

    private static final class ResourceTypeMismatchException extends RuntimeException {
    }

    private static final class MalformedReviewException extends RuntimeException {
        private MalformedReviewException(RuntimeException cause) {
            super(cause);
        }
    }
}
