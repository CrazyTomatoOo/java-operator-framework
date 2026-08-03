/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.actuator;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes framework runtime transitions through Spring Boot availability state.
 *
 * @author z00919064 zhangshjie
 * @since 2026-07-30
 */
public final class RuntimeReadiness implements ApplicationListener<ApplicationReadyEvent> {
    private final ApplicationEventPublisher publisher;
    private final AtomicBoolean applicationReady = new AtomicBoolean();
    private final AtomicBoolean frameworkReady = new AtomicBoolean();
    private final AtomicReference<ReadinessState> readiness =
            new AtomicReference<>(ReadinessState.REFUSING_TRAFFIC);
    private final AtomicReference<LivenessState> liveness =
            new AtomicReference<>(LivenessState.CORRECT);

    /**
     * Creates the tracker, publishing availability changes through the given publisher.
     *
     * @param publisher the Spring event publisher used for availability change events
     * @param initiallyReady whether the framework runtime starts out ready
     */
    public RuntimeReadiness(ApplicationEventPublisher publisher, boolean initiallyReady) {
        this.publisher = publisher;
        frameworkReady.set(initiallyReady);
    }

    RuntimeReadiness(ApplicationEventPublisher publisher) {
        this(publisher, false);
        applicationReady.set(true);
    }

    RuntimeReadiness() {
        this(event -> { });
    }

    /**
     * Reports whether the framework is currently accepting traffic.
     *
     * @return {@code true} when readiness is {@link ReadinessState#ACCEPTING_TRAFFIC}
     */
    public boolean isReady() {
        return readiness.get() == ReadinessState.ACCEPTING_TRAFFIC;
    }

    /**
     * Reports whether the framework is currently live.
     *
     * @return {@code true} when liveness is {@link LivenessState#CORRECT}
     */
    public boolean isLive() {
        return liveness.get() == LivenessState.CORRECT;
    }

    /** Marks the framework runtime ready; readiness flips once the application is also ready. */
    public void ready() {
        frameworkReady.set(true);
        refreshReadiness();
    }

    /** Marks the framework runtime not ready, refusing traffic immediately. */
    public void notReady() {
        frameworkReady.set(false);
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
    }

    /** Marks the framework runtime live. */
    public void live() {
        publishLiveness(LivenessState.CORRECT);
    }

    /** Marks the framework runtime broken, so Kubernetes restarts the pod. */
    public void broken() {
        publishLiveness(LivenessState.BROKEN);
    }

    /**
     * Records that the application finished startup and refreshes the published readiness state.
     *
     * @param event the Spring Boot application-ready event
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        applicationReady.set(true);
        refreshReadiness();
    }

    private void refreshReadiness() {
        var state = applicationReady.get() && frameworkReady.get()
                ? ReadinessState.ACCEPTING_TRAFFIC
                : ReadinessState.REFUSING_TRAFFIC;
        publishReadiness(state);
    }

    private void publishReadiness(ReadinessState state) {
        if (readiness.getAndSet(state) != state) {
            AvailabilityChangeEvent.publish(publisher, this, state);
        }
    }

    private void publishLiveness(LivenessState state) {
        if (liveness.getAndSet(state) != state) {
            AvailabilityChangeEvent.publish(publisher, this, state);
        }
    }
}
