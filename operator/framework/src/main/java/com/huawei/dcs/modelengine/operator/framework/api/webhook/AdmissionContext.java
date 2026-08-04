/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.api.webhook;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceReference;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stable, transport-neutral information for an admission callback.
 *
 * @param uid admission request UID
 * @param operation admission operation
 * @param resource resource identity
 * @param dryRun whether the request is a dry run
 * @param user requesting user identity
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
public record AdmissionContext(
        String uid,
        String operation,
        ResourceReference resource,
        boolean dryRun,
        UserIdentity user) {
    /**
     * Validates the admission context.
     *
     * @param uid admission request UID
     * @param operation admission operation
     * @param resource resource identity
     * @param dryRun whether the request is a dry run
     * @param user requesting user identity
     * @throws IllegalArgumentException if the UID or operation is null or blank
     * @throws NullPointerException if the resource or user is null
     */
    public AdmissionContext {
        requireText(uid, "uid");
        requireText(operation, "operation");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(user, "user must not be null");
    }

    /**
     * Immutable identity supplied by the Kubernetes API server.
     *
     * @param username authenticated username
     * @param uid authenticated user UID
     * @param groups authenticated user groups
     * @param extra additional user attributes
     */
    public record UserIdentity(String username, String uid, List<String> groups, Map<String, List<String>> extra) {
        /**
         * Validates the username and copies collection values into immutable collections.
         *
         * @param username authenticated username
         * @param uid authenticated user UID
         * @param groups authenticated user groups, or {@code null}
         * @param extra additional user attributes, or {@code null}
         * @throws IllegalArgumentException if {@code username} is null or blank
         */
        public UserIdentity {
            requireText(username, "username");
            groups = groups == null ? List.of() : List.copyOf(groups);
            extra = immutableExtra(extra);
        }

        private static Map<String, List<String>> immutableExtra(Map<String, List<String>> values) {
            if (values == null) {
                return Map.of();
            }
            var copy = new java.util.LinkedHashMap<String, List<String>>();
            values.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
            return Map.copyOf(copy);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
