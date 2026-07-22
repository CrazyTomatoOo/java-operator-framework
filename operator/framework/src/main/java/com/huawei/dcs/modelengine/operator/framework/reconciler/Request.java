package com.huawei.dcs.modelengine.operator.framework.reconciler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Identifies a Kubernetes resource instance that needs reconciliation. */
public final class Request {
    private final String namespace;
    private final String name;
    private final List<Trigger> triggers;

    public Request(String namespace, String name) {
        this(namespace, name, List.of());
    }

    public Request(String namespace, String name, Trigger trigger) {
        this(namespace, name, List.of(trigger));
    }

    public Request(String namespace, String name, List<Trigger> triggers) {
        this.namespace = namespace;
        this.name = name;
        this.triggers = List.copyOf(triggers);
    }

    public String namespace() {
        return this.namespace;
    }

    public String name() {
        return this.name;
    }

    public List<Trigger> triggers() {
        return this.triggers;
    }

    public Optional<Trigger> trigger() {
        return this.triggers.stream().findFirst();
    }

    public boolean triggeredByPrimary() {
        return this.trigger().map(trigger -> trigger.role() == TriggerRole.PRIMARY).orElse(false);
    }

    public Request withTrigger(Trigger trigger) {
        List<Trigger> appendedTriggers = new ArrayList<>(this.triggers);
        appendedTriggers.add(trigger);
        return new Request(this.namespace, this.name, appendedTriggers);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Request)) {
            return false;
        }
        Request request = (Request) other;
        return Objects.equals(this.namespace, request.namespace) && Objects.equals(this.name, request.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.namespace, this.name);
    }
}
