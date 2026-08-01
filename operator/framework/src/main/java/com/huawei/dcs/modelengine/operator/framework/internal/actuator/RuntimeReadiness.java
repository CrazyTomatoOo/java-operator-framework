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

    public boolean isReady() {
        return readiness.get() == ReadinessState.ACCEPTING_TRAFFIC;
    }

    public boolean isLive() {
        return liveness.get() == LivenessState.CORRECT;
    }

    public void ready() {
        frameworkReady.set(true);
        refreshReadiness();
    }

    public void notReady() {
        frameworkReady.set(false);
        publishReadiness(ReadinessState.REFUSING_TRAFFIC);
    }

    public void live() {
        publishLiveness(LivenessState.CORRECT);
    }

    public void broken() {
        publishLiveness(LivenessState.BROKEN);
    }

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
