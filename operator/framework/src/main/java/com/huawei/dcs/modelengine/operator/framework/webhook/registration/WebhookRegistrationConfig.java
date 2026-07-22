package com.huawei.dcs.modelengine.operator.framework.webhook.registration;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperations;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.RuleWithOperationsBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for registering Kubernetes admission webhook resources.
 */
public final class WebhookRegistrationConfig {
    public static final int DEFAULT_SERVICE_PORT = 8443;
    public static final String DEFAULT_FAILURE_POLICY = "Fail";
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;
    public static final String DEFAULT_SIDE_EFFECTS = "None";
    public static final String DEFAULT_VALIDATING_PATH_PREFIX = "/validate";
    public static final String DEFAULT_MUTATING_PATH_PREFIX = "/mutate";

    private final String serviceName;
    private final String serviceNamespace;
    private final int servicePort;
    private final Path caBundlePath;
    private final String failurePolicy;
    private final int timeoutSeconds;
    private final String sideEffects;
    private final List<RuleWithOperations> rules;
    private final LabelSelector namespaceSelector;
    private final LabelSelector objectSelector;
    private final String validatingPathPrefix;
    private final String mutatingPathPrefix;
    private final String baseName;

    private WebhookRegistrationConfig(Builder builder) {
        serviceName = requireText(builder.serviceName, "serviceName");
        serviceNamespace = requireText(builder.serviceNamespace, "serviceNamespace");
        servicePort = requirePort(builder.servicePort);
        caBundlePath = Objects.requireNonNull(builder.caBundlePath, "caBundlePath must not be null");
        failurePolicy = requireText(builder.failurePolicy, "failurePolicy");
        timeoutSeconds = requirePositive(builder.timeoutSeconds, "timeoutSeconds");
        sideEffects = requireText(builder.sideEffects, "sideEffects");
        rules = List.copyOf(builder.rules == null || builder.rules.isEmpty() ? defaultRules() : builder.rules);
        namespaceSelector = builder.namespaceSelector;
        objectSelector = builder.objectSelector;
        validatingPathPrefix = normalizePrefix(builder.validatingPathPrefix, "validatingPathPrefix");
        mutatingPathPrefix = normalizePrefix(builder.mutatingPathPrefix, "mutatingPathPrefix");
        baseName = builder.baseName == null || builder.baseName.isBlank()
                ? serviceName + "." + serviceNamespace
                : requireText(builder.baseName, "baseName");
    }

    public static Builder builder(String serviceName, String serviceNamespace, Path caBundlePath) {
        return new Builder(serviceName, serviceNamespace, caBundlePath);
    }

    public String serviceName() {
        return serviceName;
    }

    public String serviceNamespace() {
        return serviceNamespace;
    }

    public int servicePort() {
        return servicePort;
    }

    public Path caBundlePath() {
        return caBundlePath;
    }

    public String failurePolicy() {
        return failurePolicy;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public String sideEffects() {
        return sideEffects;
    }

    public List<RuleWithOperations> rules() {
        return rules;
    }

    public LabelSelector namespaceSelector() {
        return namespaceSelector;
    }

    public LabelSelector objectSelector() {
        return objectSelector;
    }

    public String validatingPathPrefix() {
        return validatingPathPrefix;
    }

    public String mutatingPathPrefix() {
        return mutatingPathPrefix;
    }

    public String baseName() {
        return baseName;
    }

    private static List<RuleWithOperations> defaultRules() {
        return List.of(new RuleWithOperationsBuilder()
                .withApiGroups("*")
                .withApiVersions("*")
                .withOperations("*")
                .withResources("*")
                .withScope("*")
                .build());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("servicePort must be between 1 and 65535");
        }
        return port;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String normalizePrefix(String prefix, String name) {
        String normalized = requireText(prefix, name);
        return normalized.startsWith("/") ? withoutTrailingSlash(normalized) : withoutTrailingSlash("/" + normalized);
    }

    private static String withoutTrailingSlash(String value) {
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static final class Builder {
        private final String serviceName;
        private final String serviceNamespace;
        private final Path caBundlePath;
        private int servicePort = DEFAULT_SERVICE_PORT;
        private String failurePolicy = DEFAULT_FAILURE_POLICY;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        private String sideEffects = DEFAULT_SIDE_EFFECTS;
        private List<RuleWithOperations> rules = defaultRules();
        private LabelSelector namespaceSelector;
        private LabelSelector objectSelector;
        private String validatingPathPrefix = DEFAULT_VALIDATING_PATH_PREFIX;
        private String mutatingPathPrefix = DEFAULT_MUTATING_PATH_PREFIX;
        private String baseName;

        private Builder(String serviceName, String serviceNamespace, Path caBundlePath) {
            this.serviceName = serviceName;
            this.serviceNamespace = serviceNamespace;
            this.caBundlePath = caBundlePath;
        }

        public Builder withServicePort(int servicePort) {
            this.servicePort = servicePort;
            return this;
        }

        public Builder withFailurePolicy(String failurePolicy) {
            this.failurePolicy = failurePolicy;
            return this;
        }

        public Builder withTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder withSideEffects(String sideEffects) {
            this.sideEffects = sideEffects;
            return this;
        }

        public Builder withRules(List<RuleWithOperations> rules) {
            this.rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
            return this;
        }

        public Builder withNamespaceSelector(LabelSelector namespaceSelector) {
            this.namespaceSelector = namespaceSelector;
            return this;
        }

        public Builder withObjectSelector(LabelSelector objectSelector) {
            this.objectSelector = objectSelector;
            return this;
        }

        public Builder withValidatingPathPrefix(String validatingPathPrefix) {
            this.validatingPathPrefix = validatingPathPrefix;
            return this;
        }

        public Builder withMutatingPathPrefix(String mutatingPathPrefix) {
            this.mutatingPathPrefix = mutatingPathPrefix;
            return this;
        }

        public Builder withBaseName(String baseName) {
            this.baseName = baseName;
            return this;
        }

        public WebhookRegistrationConfig build() {
            return new WebhookRegistrationConfig(this);
        }
    }
}
