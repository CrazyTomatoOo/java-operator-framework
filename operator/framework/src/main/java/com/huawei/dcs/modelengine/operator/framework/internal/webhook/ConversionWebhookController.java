/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.webhook;

import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;
import com.huawei.dcs.modelengine.operator.framework.internal.actuator.OperatorFrameworkMetrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionRequest;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionResponse;
import io.fabric8.kubernetes.api.model.apiextensions.v1.ConversionReview;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Kubernetes apiextensions/v1 conversion transport for typed Spring callback beans.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
@RestController
@RequiredArgsConstructor
public final class ConversionWebhookController {
    private static final String CALLBACK_FAILED = "webhook callback failed";

    private final WebhookCallbackRegistry callbacks;
    private final ObjectMapper objectMapper;
    private final OperatorFrameworkMetrics metrics;


    /**
     * Handles a ConversionReview by dispatching its objects to the named converter callback.
     *
     * @param name the converter route name, matching the callback bean name
     * @param review the conversion review carrying the objects to convert
     * @return the review response with the converted objects, or 400 for an unknown name or a
     *     malformed review
     */
    @PostMapping("/operator-framework/webhooks/convert/{name}")
    public ResponseEntity<ConversionReview> convert(
            @PathVariable String name,
            @RequestBody ConversionReview review) {
        var callback = callbacks.converter(name);
        if (callback.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        ConversionRequest request;
        try {
            request = requireRequest(review);
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(convert(request, callback.orElseThrow()));
    }

    private ConversionReview convert(ConversionRequest request, WebhookCallbackRegistry.Callback callback) {
        var started = System.nanoTime();
        var outcome = "error";
        try {
            var converted = new ArrayList<Object>(request.getObjects().size());
            for (var resource : request.getObjects()) {
                converted.add(convertOne(resource, request.getDesiredAPIVersion(), callback));
            }
            outcome = "converted";
            return response(request.getUid(), converted, null);
        } catch (Exception exception) {
            callbacks.recordFailure("converter", callback.name());
            return response(request.getUid(), List.of(), CALLBACK_FAILED);
        } finally {
            metrics.callback("converter", callback.name(), outcome, System.nanoTime() - started);
        }
    }

    private Object convertOne(
            Object source,
            String desiredVersion,
            WebhookCallbackRegistry.Callback callback) throws Exception {
        var tree = objectMapper.valueToTree(source);
        var sourceVersion = requireVersion(tree);
        requireSourceType(tree, sourceVersion, callback.resourceType());
        if (desiredVersion.equals(sourceVersion)) {
            return source;
        }
        var resource = objectMapper.convertValue(source, callback.resourceType());
        var context = new ConversionContext(sourceVersion, desiredVersion);
        var result = invokeConverter(callback, resource, context);
        if (!result.isConverted()) {
            throw new ConversionFailedException(result.message().orElse("conversion failed"));
        }
        var converted = result.resource().orElseThrow();
        requireDesiredVersion(converted, desiredVersion);
        requireIdentity(tree, objectMapper.valueToTree(converted));
        return converted;
    }

    private void requireDesiredVersion(Object resource, String desiredVersion) throws ConversionFailedException {
        var convertedVersion = requireVersion(objectMapper.valueToTree(resource));
        if (!desiredVersion.equals(convertedVersion)) {
            throw new ConversionFailedException("converter returned an unexpected apiVersion");
        }
    }

    private void requireSourceType(
            JsonNode resource,
            String apiVersion,
            Class<? extends HasMetadata> resourceType) throws ConversionFailedException {
        var expectedKind = HasMetadata.getKind(resourceType);
        var expectedGroup = group(HasMetadata.getApiVersion(resourceType));
        if (!Objects.equals(text(resource, "/kind"), expectedKind)
                || !Objects.equals(group(apiVersion), expectedGroup)) {
            throw new ConversionFailedException("conversion source type does not match callback");
        }
    }

    private String group(String apiVersion) {
        var separator = apiVersion.indexOf('/');
        return separator < 0 ? "" : apiVersion.substring(0, separator);
    }

    private void requireIdentity(JsonNode source, JsonNode converted) throws ConversionFailedException {
        for (var path : List.of("/kind", "/metadata/name", "/metadata/namespace", "/metadata/uid")) {
            if (!Objects.equals(text(source, path), text(converted, path))) {
                throw new ConversionFailedException("converter changed resource identity");
            }
        }
    }

    private String text(JsonNode resource, String path) {
        var value = resource.at(path);
        return value.isTextual() ? value.asText() : null;
    }

    private ConversionRequest requireRequest(ConversionReview review) {
        var request = Objects.requireNonNull(review, "review must not be null").getRequest();
        Objects.requireNonNull(request, "conversion request must not be null");
        requireText(request.getUid(), "conversion uid");
        requireText(request.getDesiredAPIVersion(), "desired API version");
        Objects.requireNonNull(request.getObjects(), "conversion objects must not be null");
        return request;
    }

    private String requireVersion(JsonNode resource) {
        if (resource == null || !resource.hasNonNull("apiVersion")) {
            throw new IllegalArgumentException("converted resource apiVersion is missing");
        }
        var version = resource.get("apiVersion").asText();
        requireText(version, "source API version");
        return version;
    }

    private ConversionReview response(String uid, List<Object> resources, String failure) {
        var conversionResponse = new ConversionResponse();
        conversionResponse.setUid(uid);
        conversionResponse.setConvertedObjects(resources);
        conversionResponse.setResult(new StatusBuilder()
                .withStatus(failure == null ? "Success" : "Failure")
                .withMessage(failure)
                .build());
        var review = new ConversionReview();
        review.setApiVersion("apiextensions.k8s.io/v1");
        review.setKind("ConversionReview");
        review.setResponse(conversionResponse);
        return review;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ConversionResult<?> invokeConverter(
            WebhookCallbackRegistry.Callback callback,
            HasMetadata resource,
            ConversionContext context) throws Exception {
        return Objects.requireNonNull(((ResourceConverter) callback.bean()).convert(resource, context),
                "converter result must not be null");
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static final class ConversionFailedException extends Exception {
        private ConversionFailedException(String message) {
            super(message);
        }
    }
}
