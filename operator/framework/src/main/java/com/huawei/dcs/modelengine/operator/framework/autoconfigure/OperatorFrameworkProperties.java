package com.huawei.dcs.modelengine.operator.framework.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Configuration for the Spring-managed operator runtime. */
@Validated
@ConfigurationProperties("operator.framework")
public class OperatorFrameworkProperties {
    private boolean enabled = true;
    @NotNull
    private Mode mode = Mode.COMBINED;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Controller getController() {
        return controller;
    }

    public LeaderElection getLeaderElection() {
        return leaderElection;
    }

    public Retry getRetry() {
        return retry;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Events getEvents() {
        return events;
    }

    /** Runtime modes supported by the starter. */
    public enum Mode {
        CONTROLLER,
        WEBHOOK,
        COMBINED
    }

    /** Controller worker and informer settings. */
    public static class Controller {
        private String namespace;
        private boolean clusterScoped;
        @Min(1)
        private int workerThreads = 1;
        @NotNull
        private Duration resyncPeriod = Duration.ofSeconds(60);
        private boolean generationChangeFilter = true;
        @NotNull
        private Duration startupRetryDelay = Duration.ofSeconds(5);

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public boolean isClusterScoped() {
            return clusterScoped;
        }

        public void setClusterScoped(boolean clusterScoped) {
            this.clusterScoped = clusterScoped;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }

        public Duration getResyncPeriod() {
            return resyncPeriod;
        }

        public void setResyncPeriod(Duration resyncPeriod) {
            this.resyncPeriod = resyncPeriod;
        }

        public boolean isGenerationChangeFilter() {
            return generationChangeFilter;
        }

        public void setGenerationChangeFilter(boolean generationChangeFilter) {
            this.generationChangeFilter = generationChangeFilter;
        }

        public Duration getStartupRetryDelay() {
            return startupRetryDelay;
        }

        public void setStartupRetryDelay(Duration startupRetryDelay) {
            this.startupRetryDelay = startupRetryDelay;
        }

        @AssertTrue(message = "controller namespace must be blank when cluster-scoped is true")
        public boolean isNamespaceScopeValid() {
            return !clusterScoped || namespace == null || namespace.isBlank();
        }

        @AssertTrue(message = "controller resync-period must be non-negative and startup-retry-delay positive")
        public boolean isTimingValid() {
            return resyncPeriod == null || startupRetryDelay == null
                    || !resyncPeriod.isNegative() && startupRetryDelay.compareTo(Duration.ZERO) > 0;
        }
    }

    /** Lease leader-election settings. */
    public static class LeaderElection {
        private boolean enabled;
        private String leaseName;
        private String namespace;
        @NotNull
        private Duration leaseDuration = Duration.ofSeconds(15);
        @NotNull
        private Duration renewDeadline = Duration.ofSeconds(10);
        @NotNull
        private Duration retryPeriod = Duration.ofSeconds(2);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLeaseName() {
            return leaseName;
        }

        public void setLeaseName(String leaseName) {
            this.leaseName = leaseName;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public Duration getRenewDeadline() {
            return renewDeadline;
        }

        public void setRenewDeadline(Duration renewDeadline) {
            this.renewDeadline = renewDeadline;
        }

        public Duration getRetryPeriod() {
            return retryPeriod;
        }

        public void setRetryPeriod(Duration retryPeriod) {
            this.retryPeriod = retryPeriod;
        }

        @AssertTrue(message = "leader-election must satisfy retry-period < renew-deadline < lease-duration")
        public boolean isTimingValid() {
            return isTimingMissing() || isTimingOrdered();
        }

        @AssertTrue(message = "leader-election lease-name and namespace must be DNS labels")
        public boolean isNamesValid() {
            return isDnsLabel(leaseName) && isDnsLabel(namespace);
        }

        private boolean isDnsLabel(String value) {
            if (value == null || value.isBlank()) {
                return true;
            }
            return value.length() <= 63 && value.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
        }

        private boolean isTimingMissing() {
            return leaseDuration == null || renewDeadline == null || retryPeriod == null;
        }

        private boolean isTimingOrdered() {
            return retryPeriod.compareTo(Duration.ZERO) > 0
                    && renewDeadline.compareTo(retryPeriod) > 0
                    && leaseDuration.compareTo(renewDeadline) > 0;
        }
    }

    /** Exception retry settings. */
    public static class Retry {
        @NotNull
        private Duration initialDelay = Duration.ofMillis(500);
        @NotNull
        private Duration maxDelay = Duration.ofSeconds(30);
        @Min(1)
        private int maxAttempts = 5;

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        @AssertTrue(message = "retry delays must be positive and max-delay must not be less than initial-delay")
        public boolean isRangeValid() {
            return initialDelay == null || maxDelay == null || initialDelay.compareTo(Duration.ZERO) > 0
                    && maxDelay.compareTo(initialDelay) >= 0;
        }
    }

    /** Per-resource reconciliation rate limit. */
    public static class RateLimit {
        @NotNull
        private Duration minimumInterval = Duration.ofSeconds(5);

        public Duration getMinimumInterval() {
            return minimumInterval;
        }

        public void setMinimumInterval(Duration minimumInterval) {
            this.minimumInterval = minimumInterval;
        }

        @AssertTrue(message = "rate-limit.minimum-interval must be non-negative")
        public boolean isMinimumIntervalValid() {
            return minimumInterval == null || !minimumInterval.isNegative();
        }
    }

    /** Kubernetes Event publication settings. */
    public static class Events {
        private boolean enabled = true;
        private String component;
        @NotNull
        private Duration aggregationWindow = Duration.ofMinutes(5);
        @Min(1)
        private int maxCacheEntries = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getComponent() {
            return component;
        }

        public void setComponent(String component) {
            this.component = component;
        }

        public Duration getAggregationWindow() {
            return aggregationWindow;
        }

        public void setAggregationWindow(Duration aggregationWindow) {
            this.aggregationWindow = aggregationWindow;
        }

        public int getMaxCacheEntries() {
            return maxCacheEntries;
        }

        public void setMaxCacheEntries(int maxCacheEntries) {
            this.maxCacheEntries = maxCacheEntries;
        }

        @AssertTrue(message = "events.aggregation-window must be positive")
        public boolean isAggregationWindowValid() {
            return aggregationWindow == null || aggregationWindow.compareTo(Duration.ZERO) > 0;
        }
    }
}
