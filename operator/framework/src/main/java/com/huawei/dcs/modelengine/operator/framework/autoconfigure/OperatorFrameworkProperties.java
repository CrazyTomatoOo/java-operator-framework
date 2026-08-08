/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the Spring-managed operator runtime.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
@Validated
@ConfigurationProperties("operator.framework")
public class OperatorFrameworkProperties {
    @Valid
    private final Controller controller = new Controller();

    @Valid
    private final LeaderElection leaderElection = new LeaderElection();

    @Valid
    private final Retry retry = new Retry();

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    @Valid
    private final Events events = new Events();

    private boolean enabled = true;

    @NotNull
    private Mode mode = Mode.COMBINED;

    /**
     * Gets whether the operator framework is enabled.
     *
     * @return whether the operator framework is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether the operator framework is enabled.
     *
     * @param enabled whether the operator framework is enabled
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Gets the runtime mode of the operator.
     *
     * @return the runtime mode of the operator
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Sets the runtime mode of the operator.
     *
     * @param mode the runtime mode of the operator
     */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /**
     * Gets the controller worker and informer settings.
     *
     * @return the controller worker and informer settings
     */
    public Controller getController() {
        return controller;
    }

    /**
     * Gets the lease leader-election settings.
     *
     * @return the lease leader-election settings
     */
    public LeaderElection getLeaderElection() {
        return leaderElection;
    }

    /**
     * Gets the exception retry settings.
     *
     * @return the exception retry settings
     */
    public Retry getRetry() {
        return retry;
    }

    /**
     * Gets the per-resource reconciliation rate limit settings.
     *
     * @return the per-resource reconciliation rate limit settings
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * Gets the Kubernetes Event publication settings.
     *
     * @return the Kubernetes Event publication settings
     */
    public Events getEvents() {
        return events;
    }

    /** Controller worker and informer settings. */
    public static class Controller {
        private static final Duration DEFAULT_RESYNC_PERIOD = Duration.ofSeconds(60);

        private static final Duration DEFAULT_STARTUP_RETRY_DELAY = Duration.ofSeconds(5);

        private String namespace;

        private boolean clusterScoped;

        @Min(1)
        private int workerThreads = 1;

        @NotNull
        private Duration resyncPeriod = DEFAULT_RESYNC_PERIOD;

        private boolean generationChangeFilter = true;

        private boolean filterEventsByInvolvedObject = true;

        @NotNull
        private Duration startupRetryDelay = DEFAULT_STARTUP_RETRY_DELAY;

        /**
         * Gets the namespace the controller watches.
         *
         * @return the namespace the controller watches
         */
        public String getNamespace() {
            return namespace;
        }

        /**
         * Sets the namespace the controller watches.
         *
         * @param namespace the namespace the controller watches
         */
        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        /**
         * Gets whether the controller watches all namespaces.
         *
         * @return whether the controller watches all namespaces
         */
        public boolean isClusterScoped() {
            return clusterScoped;
        }

        /**
         * Sets whether the controller watches all namespaces.
         *
         * @param clusterScoped whether the controller watches all namespaces
         */
        public void setClusterScoped(boolean clusterScoped) {
            this.clusterScoped = clusterScoped;
        }

        /**
         * Gets the number of reconciliation worker threads.
         *
         * @return the number of reconciliation worker threads
         */
        public int getWorkerThreads() {
            return workerThreads;
        }

        /**
         * Sets the number of reconciliation worker threads.
         *
         * @param workerThreads the number of reconciliation worker threads
         */
        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        /**
         * Gets the informer resync period.
         *
         * @return the informer resync period
         */
        public Duration getResyncPeriod() {
            return resyncPeriod;
        }

        /**
         * Sets the informer resync period.
         *
         * @param resyncPeriod the informer resync period
         */
        public void setResyncPeriod(Duration resyncPeriod) {
            this.resyncPeriod = resyncPeriod;
        }

        /**
         * Gets whether reconciliation is skipped when only the resource generation is unchanged.
         *
         * @return whether reconciliation is skipped when only the resource generation is unchanged
         */
        public boolean isGenerationChangeFilter() {
            return generationChangeFilter;
        }

        /**
         * Sets whether reconciliation is skipped when only the resource generation is unchanged.
         *
         * @param generationChangeFilter whether reconciliation is skipped when only the resource generation is
         *     unchanged
         */
        public void setGenerationChangeFilter(boolean generationChangeFilter) {
            this.generationChangeFilter = generationChangeFilter;
        }

        /**
         * Whether the Kubernetes-Event watch narrows with involvedObject field selectors. Disable
         * when the API server (or the fabric8 crud mock server) cannot match those selectors.
         *
         * @return whether the event watch narrows with involvedObject field selectors
         */
        public boolean isFilterEventsByInvolvedObject() {
            return filterEventsByInvolvedObject;
        }

        /**
         * Sets whether the Kubernetes-Event watch narrows with involvedObject field selectors.
         *
         * @param filterEventsByInvolvedObject whether the Kubernetes-Event watch narrows with involvedObject field
         *     selectors
         */
        public void setFilterEventsByInvolvedObject(boolean filterEventsByInvolvedObject) {
            this.filterEventsByInvolvedObject = filterEventsByInvolvedObject;
        }

        /**
         * Gets the delay before retrying a failed controller startup.
         *
         * @return the delay before retrying a failed controller startup
         */
        public Duration getStartupRetryDelay() {
            return startupRetryDelay;
        }

        /**
         * Sets the delay before retrying a failed controller startup.
         *
         * @param startupRetryDelay the delay before retrying a failed controller startup
         */
        public void setStartupRetryDelay(Duration startupRetryDelay) {
            this.startupRetryDelay = startupRetryDelay;
        }

        /**
         * Checks that the namespace is null or blank when the controller is cluster-scoped.
         *
         * @return whether the namespace scope combination is valid
         */
        @AssertTrue(message = "controller namespace must be blank when cluster-scoped is true")
        public boolean isNamespaceScopeValid() {
            return !clusterScoped || namespace == null || namespace.isBlank();
        }

        /**
         * Checks that the resync period is non-negative and the startup retry delay is positive.
         *
         * @return whether the controller timing settings are valid
         */
        @AssertTrue(message = "controller resync-period must be non-negative and startup-retry-delay positive")
        public boolean isTimingValid() {
            return resyncPeriod == null || startupRetryDelay == null
                || !resyncPeriod.isNegative() && startupRetryDelay.compareTo(Duration.ZERO) > 0;
        }
    }

    /** Lease leader-election settings. */
    public static class LeaderElection {
        private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(15);

        private static final Duration DEFAULT_RENEW_DEADLINE = Duration.ofSeconds(10);

        private static final int MAX_DNS_LABEL_LENGTH = 63;

        private boolean enabled;

        private String leaseName;

        private String namespace;

        @NotNull
        private Duration leaseDuration = DEFAULT_LEASE_DURATION;

        @NotNull
        private Duration renewDeadline = DEFAULT_RENEW_DEADLINE;

        @NotNull
        private Duration retryPeriod = Duration.ofSeconds(2);

        /**
         * Gets whether leader election is enabled.
         *
         * @return whether leader election is enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether leader election is enabled.
         *
         * @param enabled whether leader election is enabled
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Gets the name of the leader-election lease.
         *
         * @return the name of the leader-election lease
         */
        public String getLeaseName() {
            return leaseName;
        }

        /**
         * Sets the name of the leader-election lease.
         *
         * @param leaseName the name of the leader-election lease
         */
        public void setLeaseName(String leaseName) {
            this.leaseName = leaseName;
        }

        /**
         * Gets the namespace of the leader-election lease.
         *
         * @return the namespace of the leader-election lease
         */
        public String getNamespace() {
            return namespace;
        }

        /**
         * Sets the namespace of the leader-election lease.
         *
         * @param namespace the namespace of the leader-election lease
         */
        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        /**
         * Gets the duration non-leaders wait before forcing a leader change.
         *
         * @return the leader-election lease duration
         */
        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        /**
         * Sets the duration non-leaders wait before forcing a leader change.
         *
         * @param leaseDuration the leader-election lease duration
         */
        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        /**
         * Gets the deadline for the leader to renew the lease.
         *
         * @return the leader-election renew deadline
         */
        public Duration getRenewDeadline() {
            return renewDeadline;
        }

        /**
         * Sets the deadline for the leader to renew the lease.
         *
         * @param renewDeadline the leader-election renew deadline
         */
        public void setRenewDeadline(Duration renewDeadline) {
            this.renewDeadline = renewDeadline;
        }

        /**
         * Gets the interval between leader-election retry attempts.
         *
         * @return the leader-election retry period
         */
        public Duration getRetryPeriod() {
            return retryPeriod;
        }

        /**
         * Sets the interval between leader-election retry attempts.
         *
         * @param retryPeriod the leader-election retry period
         */
        public void setRetryPeriod(Duration retryPeriod) {
            this.retryPeriod = retryPeriod;
        }

        /**
         * Checks that the timing settings satisfy retry-period &lt; renew-deadline &lt; lease-duration.
         *
         * @return whether the leader-election timing settings are valid
         */
        @AssertTrue(message = "leader-election must satisfy retry-period < renew-deadline < lease-duration")
        public boolean isTimingValid() {
            return isTimingMissing() || isTimingOrdered();
        }

        private boolean isTimingMissing() {
            return leaseDuration == null || renewDeadline == null || retryPeriod == null;
        }

        private boolean isTimingOrdered() {
            return retryPeriod.compareTo(Duration.ZERO) > 0 && renewDeadline.compareTo(retryPeriod) > 0
                && leaseDuration.compareTo(renewDeadline) > 0;
        }

        /**
         * Checks that the lease name and namespace are valid DNS labels.
         *
         * @return whether the leader-election lease name and namespace are valid
         */
        @AssertTrue(message = "leader-election lease-name and namespace must be DNS labels")
        public boolean isNamesValid() {
            return isDnsLabel(leaseName) && isDnsLabel(namespace);
        }

        private boolean isDnsLabel(String value) {
            if (value == null || value.isBlank()) {
                return true;
            }
            return value.length() <= MAX_DNS_LABEL_LENGTH && value.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
        }
    }

    /** Exception retry settings. */
    public static class Retry {
        private static final Duration DEFAULT_INITIAL_DELAY = Duration.ofMillis(500);

        private static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30);

        private static final int DEFAULT_MAX_ATTEMPTS = 5;

        @NotNull
        private Duration initialDelay = DEFAULT_INITIAL_DELAY;

        @NotNull
        private Duration maxDelay = DEFAULT_MAX_DELAY;

        @Min(1)
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

        /**
         * Gets the delay before the first retry attempt.
         *
         * @return the initial retry delay
         */
        public Duration getInitialDelay() {
            return initialDelay;
        }

        /**
         * Sets the delay before the first retry attempt.
         *
         * @param initialDelay the initial retry delay
         */
        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        /**
         * Gets the maximum delay between retry attempts.
         *
         * @return the maximum retry delay
         */
        public Duration getMaxDelay() {
            return maxDelay;
        }

        /**
         * Sets the maximum delay between retry attempts.
         *
         * @param maxDelay the maximum retry delay
         */
        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        /**
         * Gets the maximum number of retry attempts.
         *
         * @return the maximum number of retry attempts
         */
        public int getMaxAttempts() {
            return maxAttempts;
        }

        /**
         * Sets the maximum number of retry attempts.
         *
         * @param maxAttempts the maximum number of retry attempts
         */
        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        /**
         * Checks that the delays are positive and the max delay is not less than the initial delay.
         *
         * @return whether the retry delay range is valid
         */
        @AssertTrue(message = "retry delays must be positive and max-delay must not be less than initial-delay")
        public boolean isRangeValid() {
            return initialDelay == null || maxDelay == null
                || initialDelay.compareTo(Duration.ZERO) > 0 && maxDelay.compareTo(initialDelay) >= 0;
        }
    }

    /** Per-resource reconciliation rate limit. */
    public static class RateLimit {
        private static final Duration DEFAULT_MINIMUM_INTERVAL = Duration.ofSeconds(5);

        @NotNull
        private Duration minimumInterval = DEFAULT_MINIMUM_INTERVAL;

        /**
         * Gets the minimum interval between reconciliations of the same resource.
         *
         * @return the minimum reconciliation interval
         */
        public Duration getMinimumInterval() {
            return minimumInterval;
        }

        /**
         * Sets the minimum interval between reconciliations of the same resource.
         *
         * @param minimumInterval the minimum reconciliation interval
         */
        public void setMinimumInterval(Duration minimumInterval) {
            this.minimumInterval = minimumInterval;
        }

        /**
         * Checks that the minimum interval is non-negative.
         *
         * @return whether the minimum reconciliation interval is valid
         */
        @AssertTrue(message = "rate-limit.minimum-interval must be non-negative")
        public boolean isMinimumIntervalValid() {
            return minimumInterval == null || !minimumInterval.isNegative();
        }
    }

    /** Kubernetes Event publication settings. */
    public static class Events {
        private static final Duration DEFAULT_AGGREGATION_WINDOW = Duration.ofMinutes(5);

        private static final int DEFAULT_MAX_CACHE_ENTRIES = 1000;

        private boolean enabled = true;

        private String component;

        @NotNull
        private Duration aggregationWindow = DEFAULT_AGGREGATION_WINDOW;

        @Min(1)
        private int maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES;

        /**
         * Gets whether Kubernetes Event publication is enabled.
         *
         * @return whether Kubernetes Event publication is enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether Kubernetes Event publication is enabled.
         *
         * @param enabled whether Kubernetes Event publication is enabled
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Gets the component name reported on published events.
         *
         * @return the component name reported on published events
         */
        public String getComponent() {
            return component;
        }

        /**
         * Sets the component name reported on published events.
         *
         * @param component the component name reported on published events
         */
        public void setComponent(String component) {
            this.component = component;
        }

        /**
         * Gets the time window over which duplicate events are aggregated.
         *
         * @return the event aggregation window
         */
        public Duration getAggregationWindow() {
            return aggregationWindow;
        }

        /**
         * Sets the time window over which duplicate events are aggregated.
         *
         * @param aggregationWindow the event aggregation window
         */
        public void setAggregationWindow(Duration aggregationWindow) {
            this.aggregationWindow = aggregationWindow;
        }

        /**
         * Gets the maximum number of entries in the event aggregation cache.
         *
         * @return the maximum number of event cache entries
         */
        public int getMaxCacheEntries() {
            return maxCacheEntries;
        }

        /**
         * Sets the maximum number of entries in the event aggregation cache.
         *
         * @param maxCacheEntries the maximum number of event cache entries
         */
        public void setMaxCacheEntries(int maxCacheEntries) {
            this.maxCacheEntries = maxCacheEntries;
        }

        /**
         * Checks that the aggregation window is positive.
         *
         * @return whether the event aggregation window is valid
         */
        @AssertTrue(message = "events.aggregation-window must be positive")
        public boolean isAggregationWindowValid() {
            return aggregationWindow == null || aggregationWindow.compareTo(Duration.ZERO) > 0;
        }
    }

    /** Runtime modes supported by the starter. */
    public enum Mode {
        CONTROLLER,
        WEBHOOK,
        COMBINED
    }
}
