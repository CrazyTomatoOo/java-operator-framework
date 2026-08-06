/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.dcs.modelengine.operator.framework.internal.controller;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationTrigger;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ResourceKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Coalescing work queue with one in-flight callback per resource key.
 *
 * @author z00919064 zhangshijie
 * @since 2026-07-30
 */
final class ReconciliationQueue {
    private static final int MAX_TRIGGER_HISTORY = 32;

    private final BlockingQueue<ResourceKey> keys = new LinkedBlockingQueue<>();
    private final Map<ResourceKey, List<ReconciliationTrigger>> pending = new LinkedHashMap<>();
    private final Set<ResourceKey> queued = new LinkedHashSet<>();
    private final Set<ResourceKey> inFlight = new LinkedHashSet<>();
    private boolean accepting = true;

    synchronized void offer(ResourceKey key, ReconciliationTrigger trigger) {
        if (!accepting) {
            return;
        }
        append(pending.computeIfAbsent(key, ignored -> new ArrayList<>()), trigger);
        if (!inFlight.contains(key) && queued.add(key)) {
            keys.offer(key);
        }
    }

    Optional<Work> poll(DurationMillis timeout) throws InterruptedException {
        var key = keys.poll(timeout.value(), TimeUnit.MILLISECONDS);
        return key == null ? Optional.empty() : begin(key);
    }

    synchronized void complete(ResourceKey key) {
        inFlight.remove(key);
        if (pending.containsKey(key) && queued.add(key)) {
            keys.offer(key);
        }
    }

    synchronized int size() {
        return pending.size();
    }

    synchronized void stopAccepting() {
        accepting = false;
    }

    synchronized void discardPending() {
        stopAccepting();
        keys.clear();
        pending.clear();
        queued.clear();
    }

    synchronized boolean isDrained() {
        return pending.isEmpty() && inFlight.isEmpty();
    }

    private synchronized Optional<Work> begin(ResourceKey key) {
        queued.remove(key);
        var triggers = pending.remove(key);
        if (triggers == null) {
            return Optional.empty();
        }
        inFlight.add(key);
        return Optional.of(new Work(key, List.copyOf(triggers)));
    }

    private void append(List<ReconciliationTrigger> triggers, ReconciliationTrigger trigger) {
        if (triggers.size() == MAX_TRIGGER_HISTORY) {
            triggers.removeFirst();
        }
        triggers.add(trigger);
    }

    record Work(ResourceKey key, List<ReconciliationTrigger> triggers) {
    }

    record DurationMillis(long value) {
    }
}
