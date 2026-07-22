package com.huawei.dcs.modelengine.operator.framework.source;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces pending reconciliation requests by primary resource key while preserving trigger history.
 */
public final class ReconciliationQueue extends AbstractQueue<Request> implements BlockingQueue<Request> {
    private final BlockingQueue<Request> queue;
    private final ConcurrentHashMap<Request, Request> pending;

    public ReconciliationQueue() {
        this(new LinkedBlockingQueue<>(), new ConcurrentHashMap<>());
    }

    ReconciliationQueue(BlockingQueue<Request> queue, ConcurrentHashMap<Request, Request> pending) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.pending = Objects.requireNonNull(pending, "pending");
    }

    @Override
    public boolean offer(Request request) {
        Objects.requireNonNull(request, "request");
        synchronized (this.pending) {
            Request existing = this.pending.get(request);
            if (existing != null) {
                this.pending.put(request, merge(existing, request));
                return true;
            }
            if (!this.queue.offer(request)) {
                return false;
            }
            this.pending.put(request, request);
            return true;
        }
    }

    @Override
    public void put(Request request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        synchronized (this.pending) {
            Request existing = this.pending.get(request);
            if (existing != null) {
                this.pending.put(request, merge(existing, request));
                return;
            }
            this.queue.put(request);
            this.pending.put(request, request);
        }
    }

    @Override
    public boolean offer(Request request, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(unit, "unit");
        synchronized (this.pending) {
            Request existing = this.pending.get(request);
            if (existing != null) {
                this.pending.put(request, merge(existing, request));
                return true;
            }
            if (!this.queue.offer(request, timeout, unit)) {
                return false;
            }
            this.pending.put(request, request);
            return true;
        }
    }

    @Override
    public Request poll() {
        Request request = this.queue.poll();
        if (request == null) {
            return null;
        }
        return removePending(request);
    }

    @Override
    public Request take() throws InterruptedException {
        return removePending(this.queue.take());
    }

    @Override
    public Request poll(long timeout, TimeUnit unit) throws InterruptedException {
        Request request = this.queue.poll(timeout, unit);
        if (request == null) {
            return null;
        }
        return removePending(request);
    }

    @Override
    public Request peek() {
        Request request = this.queue.peek();
        if (request == null) {
            return null;
        }
        return this.pending.getOrDefault(request, request);
    }

    @Override
    public int remainingCapacity() {
        return this.queue.remainingCapacity();
    }

    @Override
    public int drainTo(Collection<? super Request> collection) {
        return drainTo(collection, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super Request> collection, int maxElements) {
        Objects.requireNonNull(collection, "collection");
        if (collection == this) {
            throw new IllegalArgumentException("Cannot drain queue to itself");
        }
        int drained = 0;
        while (drained < maxElements) {
            Request request = poll();
            if (request == null) {
                break;
            }
            collection.add(request);
            drained++;
        }
        return drained;
    }

    @Override
    public Iterator<Request> iterator() {
        return this.queue.stream()
                .map(request -> this.pending.getOrDefault(request, request))
                .iterator();
    }

    @Override
    public int size() {
        return this.queue.size();
    }

    private Request removePending(Request request) {
        synchronized (this.pending) {
            Request merged = this.pending.remove(request);
            return merged == null ? request : merged;
        }
    }

    private static Request merge(Request existing, Request incoming) {
        Request merged = existing;
        for (Trigger trigger : incoming.triggers()) {
            merged = merged.withTrigger(trigger);
        }
        return merged;
    }
}
