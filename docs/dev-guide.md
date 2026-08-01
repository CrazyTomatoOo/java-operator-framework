# Developer Guide

This guide describes the current Spring Boot starter. It targets Java 21, Spring Boot 3.5.15, Fabric8 Kubernetes Client 7.3.0, and Lombok 1.18.32.

Chinese documentation: [dev-guide.zh-CN.md](dev-guide.zh-CN.md)

## 1. Add the starter

Build this repository when using the snapshot locally:

```bash
mvn -f operator/framework/pom.xml clean install
```

Add the starter to a Spring Boot 3.5.16 application:

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Use Java 21. The starter already supplies Spring Web, Actuator, Micrometer Prometheus support, and Fabric8 Kubernetes Client 7.8.0. Add Fabric8 generator dependencies/plugins only if your application actually generates CRDs or Java models; they are not required by the runtime.

The starter uses Spring Boot auto-configuration. There is no enable annotation and no application-owned framework lifecycle object.

## 2. Define a resource and Reconciler bean

Use any concrete Fabric8 `HasMetadata` type. This can be a built-in type such as `ConfigMap` or your own `CustomResource<Spec, Status>`.

The smallest controller is a typed component:

```java
package com.example.operator;

import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconcileResult;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.ReconciliationContext;
import io.fabric8.kubernetes.api.model.ConfigMap;
import org.springframework.stereotype.Component;

@Component
public final class ConfigMapReconciler implements Reconciler<ConfigMap> {
    @Override
    public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext context) throws Exception {
        // Read desired state from resource and converge Kubernetes state.
        return ReconcileResult.done();
    }
}
```

Choose controller mode for an application with no webhook callbacks:

```yaml
operator:
  framework:
    mode: controller
```

Spring discovers the generic type, creates the controller, starts informers/workers, and shuts them down. Do not perform framework registration or lifecycle calls from application code.

A reconciliation receives:

- the current primary resource;
- `context.resourceKey()` with namespace/name;
- `context.triggers()` with event type, role, and resource reference.

Return one of:

```java
ReconcileResult.done();
ReconcileResult.requeueNow();
ReconcileResult.requeueAfter(Duration.ofSeconds(30));
```

Let exceptions propagate. The starter converts non-terminal callback exceptions into delayed retries according to `operator.framework.retry.*`; after the configured failed-attempt limit, the failure is terminal and is recorded by metrics/logging.

### Manage finalizers and status

Reconcilers that own external resources use the Kubernetes finalizer pattern and persist progress through the `/status` subresource. The starter ships static helpers in the `api.reconcile` package; inject the `KubernetesClient` (the starter creates and owns one when absent) and use them directly:

```java
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.Finalizers;
import com.huawei.dcs.modelengine.operator.framework.api.reconcile.StatusUpdates;

@Override
public ReconcileResult reconcile(MyResource resource, ReconciliationContext context) {
    if (Finalizers.isDeleting(resource)) {
        cleanupExternal(resource);
        Finalizers.remove(client, resource, "example.com/cleanup");
        return ReconcileResult.done();
    }
    Finalizers.add(client, resource, "example.com/cleanup");
    var status = new MyResourceStatus();
    status.setPhase("Ready");
    StatusUpdates.update(client, resource, status);
    return ReconcileResult.done();
}
```

`Finalizers.add`/`remove` apply a server-side JSON patch (idempotent and safe under concurrent reconciles). `StatusUpdates.update` merges the given status object into the `/status` subresource via JSON merge patch and never mutates the passed (informer-cached) resource; it requires the CRD to declare a `status` subresource.

For resources the operator owns (ConfigMaps, Deployments, Secrets, ...), submit the full desired state with server-side apply instead of hand-rolled create-or-update:

```java
Applies.apply(client, desiredConfigMap, "my-operator");
// Applies.applyForcibly(client, desiredConfigMap, "my-operator"); // also takes over conflicting fields
```

`Applies.apply` sends the whole desired object as an `application/apply-patch+yaml` patch: the apiserver creates the resource when absent and updates only the fields the given field manager owns. Keep the field-manager name stable per operator (the application name is a good choice); fabric8 otherwise falls back to `fabric8`, and managers sharing one name silently take over each other's fields. Always pass a freshly built desired object — never a mutated informer-cached instance.

## 3. Configure an advanced controller

A plain `Reconciler<T>` bean gets default controller settings. Define one `ControllerRegistration<T>` Bean when the controller needs explicit sources or per-controller overrides.

```java
package com.example.operator;

import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class OperatorConfiguration {
    @Bean
    ControllerRegistration<MyResource> myResourceController(MyResourceReconciler reconciler) {
        return ControllerBuilder.forResource(MyResource.class, reconciler)
                .generationFilter(true)
                .resyncPeriod(Duration.ofMinutes(2))
                .owns(Deployment.class)
                .watches("configuration", ConfigMap.class, Mappers.byLabel("operator.example/primary"))
                .watchesKubernetesEvents()
                .build();
    }
}
```

The referenced reconciler must be a Spring Bean so AOP retry/rate-limit/observation behavior remains active.

- `generationFilter(boolean)` overrides the global generation filter.
- `resyncPeriod(Duration)` overrides global resync; zero disables periodic resync.
- `owns(Deployment.class)` watches owned resources and maps Owner References to primary keys.
- `watches(name, type, mapper)` watches arbitrary secondary resources. Watch names must be unique in one registration.
- `watchesKubernetesEvents()` watches `core/v1` Events and maps `involvedObject` (filtered server-side by kind/apiVersion) to a primary key. Aggregated Event `count` increments do not trigger reconciliation; only new Events, deletions, and resyncs do.

Built-in `Mappers` provide `ownerReferences()`, `byLabel(key)`, `byAnnotation(key)`, and `involvedObject()`. A custom mapper receives the current `ResourceEvent<S>` and returns `Collection<ResourceKey>`:

```java
ResourceMapper<ConfigMap, MyResource> mapper = event -> List.of(
        new ResourceKey(event.resource().getMetadata().getNamespace(), "primary-name"));
```

One primary resource type may have only one effective registration. An explicit registration replaces automatic discovery for that type; duplicate automatic/explicit resource types fail startup. Kubernetes Events are lossy and TTL-bound, so never use Event subscription as a correctness boundary. If a reconciler publishes Events for its own primary resource, avoid an unfiltered subscription loop.

## 4. Configure the runtime

Every framework-specific property and default is listed below.

| Property | Default | Semantics |
| --- | ---: | --- |
| `operator.framework.enabled` | `true` | Enables auto-configuration. `false` creates no framework runtime/configuration beans. |
| `operator.framework.mode` | `combined` | Selects controller, webhook, or both runtime sides. |
| `operator.framework.controller.namespace` | unset | Watches this namespace; blank falls back to the Fabric8 client namespace, then `default`. |
| `operator.framework.controller.cluster-scoped` | `false` | Watches every namespace; conflicts with a nonblank controller namespace. |
| `operator.framework.controller.worker-threads` | `1` | Worker count created for each controller registration. |
| `operator.framework.controller.resync-period` | `60s` | Default informer resync; zero disables periodic resync. |
| `operator.framework.controller.generation-change-filter` | `true` | Filters primary updates unless generation, deletion timestamp, or finalizers changed; add/delete/resync and secondary events still enqueue. |
| `operator.framework.controller.filter-events-by-involved-object` | `true` | Narrows the Kubernetes-Event watch with involvedObject field selectors; disable when the API server (or in-memory test server) cannot match them. |
| `operator.framework.controller.startup-retry-delay` | `5s` | Supervisor retry/check interval after startup or informer readiness failure. |
| `operator.framework.leader-election.enabled` | `false` | Enables Fabric8 Lease leader election for the controller runtime. |
| `operator.framework.leader-election.lease-name` | `${spring.application.name}-leader` | Lease name; the application-name fallback is sanitized for Kubernetes. |
| `operator.framework.leader-election.namespace` | inherited | Uses the controller namespace, then Fabric8 client namespace, then `default`. |
| `operator.framework.leader-election.lease-duration` | `15s` | Lease duration. |
| `operator.framework.leader-election.renew-deadline` | `10s` | Renew deadline. |
| `operator.framework.leader-election.retry-period` | `2s` | Election retry period. |
| `operator.framework.retry.initial-delay` | `500ms` | First delay after a reconciler exception. |
| `operator.framework.retry.max-delay` | `30s` | Maximum exponential retry delay. |
| `operator.framework.retry.max-attempts` | `5` | Number of failed invocations at which the exception becomes terminal. |
| `operator.framework.rate-limit.minimum-interval` | `5s` | Minimum per-controller/per-resource interval; zero disables throttling. |
| `operator.framework.events.enabled` | `true` | Creates the public Event publisher in controller/combined mode. |
| `operator.framework.events.component` | `spring.application.name` | Kubernetes Event reporting/source component. |
| `operator.framework.events.aggregation-window` | `5m` | Time window used in the deterministic identity of equivalent Events. |
| `operator.framework.events.max-cache-entries` | `1000` | LRU aggregation cache bound. |

Spring Boot duration values such as `500ms`, `5s`, and `2m` are accepted. Worker threads, retry attempts, and event cache entries must be positive. Resync and the rate-limit interval may be zero; startup retry, event aggregation, retry delays, and leader-election durations must be positive. Retry `initial-delay` must not exceed `max-delay`, and leader timings must satisfy `retry-period < renew-deadline < lease-duration`.

Mode behavior:

| Mode | Required application Beans | Auto-configured infrastructure |
| --- | --- | --- |
| `controller` | A typed `Reconciler` or `ControllerRegistration` | Kubernetes client (if missing), informers/workers, event publisher (if enabled), lifecycle, health/metrics |
| `webhook` | A typed `AdmissionValidator`, `AdmissionMutator`, or `ResourceConverter` | MVC webhook routes, callback registry, health/metrics; no automatic Kubernetes client |
| `combined` | Both controller and webhook groups | Both sides |

The default `combined` mode is intentionally strict: an application that supplies only one group must select its matching mode. Raw/unresolved callback generics and duplicate registrations are configuration errors, not silently ignored features.

Example complete controller configuration:

```yaml
spring:
  application:
    name: inventory-operator
  lifecycle:
    timeout-per-shutdown-phase: 30s
operator:
  framework:
    mode: controller
    controller:
      namespace: operators
      cluster-scoped: false
      worker-threads: 2
      resync-period: 60s
      generation-change-filter: true
      startup-retry-delay: 5s
    retry:
      initial-delay: 500ms
      max-delay: 30s
      max-attempts: 5
    rate-limit:
      minimum-interval: 5s
    events:
      enabled: true
      component: inventory-operator
      aggregation-window: 5m
      max-cache-entries: 1000
```

## 5. Enable leader election

```yaml
spring:
  application:
    name: inventory-operator
operator:
  framework:
    mode: controller
    leader-election:
      enabled: true
      lease-duration: 15s
      renew-deadline: 10s
      retry-period: 2s
```

The Lease namespace inherits the controller namespace, then the Fabric8 client namespace, then `default`; `leader-election.namespace` overrides it. The Lease name defaults to the lower-case, DNS-safe `spring.application.name` plus `-leader`; `leader-election.lease-name` overrides it. Identity uses the pod `HOSTNAME`, or the JVM runtime identity outside Kubernetes. The elector releases its Lease during shutdown.

Only the leader runs controller informers/workers. A standby is live and ready; after gaining leadership it becomes unready until all informers synchronize. On leadership loss it stops accepting new work, drains/stops the active runtime, and returns to standby readiness. RBAC must allow the Lease operations required by Fabric8.

## 6. Publish Kubernetes Events

In controller/combined mode with events enabled, inject the public interface:

```java
import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;
import org.springframework.stereotype.Component;

@Component
final class EventReporter {
    private final KubernetesEventPublisher publisher;

    EventReporter(KubernetesEventPublisher publisher) {
        this.publisher = publisher;
    }

    void reconciled(MyResource resource) {
        publisher.normal(resource, "Reconciled", "Desired state applied");
    }

    void invalid(MyResource resource) {
        publisher.warning(resource, "InvalidSpec", "spec is invalid");
    }
}
```

Equivalent Events in one aggregation window update an existing `core/v1` Event count. The bounded cache is flushed on shutdown. `spring.application.name` is the reporting component, with `operator-framework` as fallback. Grant `create`, `get`, and `update` on `events`. Publication failures are logged/metricized and do not make Kubernetes Events reliable state storage.

## 7. Implement webhook callbacks

Select `webhook` or `combined` mode and declare typed Spring callback Beans. The Bean name is the fixed route key and must match `[a-z0-9][a-z0-9._-]*` — Kubernetes rejects webhook `clientConfig.service.path` segments containing uppercase letters, so the framework enforces lowercase RFC 1123 names at startup.

### Validation

```java
@Bean("myresourcevalidator")
AdmissionValidator<MyResource> myresourcevalidator() {
    return (current, context) -> current.getSpec().isValid()
            ? AdmissionDecision.allow()
            : AdmissionDecision.deny("spec is invalid");
}
```

The exact API is `AdmissionDecision validate(T current, AdmissionContext context) throws Exception`. `AdmissionContext` contains request UID, operation, stable `ResourceReference`, dry-run flag, and user identity.

Route: `POST /operator-framework/webhooks/validate/myresourcevalidator`

### Mutation

```java
@Bean("myresourcemutator")
AdmissionMutator<MyResource> myresourcemutator() {
    return (current, context) -> MutationResult.unchanged();
}
```

The exact API is `MutationResult<T> mutate(T current, AdmissionContext context) throws Exception`. Return `unchanged()`, `mutated(resource)`, or `denied(message)`. For a mutated resource, the framework computes the JSON Patch against the input and Base64-encodes it in the `AdmissionResponse`.

Route: `POST /operator-framework/webhooks/mutate/myresourcemutator`

### Conversion

```java
@Bean("myresourceconverter")
ResourceConverter<MyResource> myresourceconverter() {
    return (resource, context) -> {
        MyResource converted = convert(resource, context.desiredVersion());
        return ConversionResult.converted(converted);
    };
}
```

The exact API is `ConversionResult<T> convert(T resource, ConversionContext context) throws Exception`. `ConversionContext` contains source and desired API versions. Return `converted(resource)` or `failed(message)`. Same-version resources pass through without invoking the callback.

Route: `POST /operator-framework/webhooks/convert/myresourceconverter`

The transport accepts Kubernetes `admission.k8s.io/v1` and `apiextensions.k8s.io/v1` review objects, keeps response UIDs, and returns safe callback-failure responses. Invalid/unknown routes receive HTTP 400.

## 8. Supply external webhook TLS

The webhook routes run on the application Spring Web server. Configure standard Spring Boot HTTPS properties and mount platform-managed PEM material:

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    certificate: file:/etc/operator/tls/tls.crt
    certificate-private-key: file:/etc/operator/tls/tls.key
operator:
  framework:
    mode: webhook
```

The starter intentionally provides no certificate generator, CA persistence, certificate reload subsystem, or Kubernetes webhook self-registration. Deployment tooling must provision and rotate the certificate/Secret, Service, `ValidatingWebhookConfiguration`, `MutatingWebhookConfiguration`, and CRD conversion webhook configuration. Configure each Kubernetes webhook service path to the Bean-name route and put the signing CA in its `caBundle`.

Standard Spring Boot SSL bundle properties may be used instead of the direct PEM properties when that better matches the platform.

## 9. Expose liveness, readiness, and Prometheus

The starter uses Spring Boot Actuator and the application Micrometer registry. Enable and expose the standard endpoints:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  endpoints:
    web:
      exposure:
        include: health,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

Probe/metrics URLs:

```text
/actuator/health/liveness
/actuator/health/readiness
/actuator/prometheus
```

The `operatorFramework` health contribution reports mode, liveness/readiness, controller running/informer/leadership state, and webhook callback counts/last failure. `show-details` is optional and only controls response visibility. No framework-specific HTTP health/metrics server or port exists.

## 10. Understand lifecycle and client ownership

The runtime is a Spring `SmartLifecycle` and starts automatically. Controller startup is non-blocking and retries transient startup/readiness failures at `operator.framework.controller.startup-retry-delay`.

Shutdown performs this sequence:

1. mark readiness false and stop leader election;
2. reject new queue work and stop informers/schedulers;
3. drain in-flight workers;
4. interrupt workers remaining after `spring.lifecycle.timeout-per-shutdown-phase`.

When no application `KubernetesClient` Bean exists in a controller-capable mode, the starter builds one and records ownership so it is closed after framework shutdown. When the application supplies a client, the starter reuses it and its ownership component does not close it. The application controls that Bean's own destroy policy. Webhook-only mode neither requires nor creates a client.

## 11. Respect the public package boundary

Application code may depend only on:

```text
com.huawei.dcs.modelengine.operator.framework.api.*
```

The `autoconfigure` root is for Spring Boot loading and configuration metadata. The `internal` root is not a compatibility contract. The production JAR intentionally contains only `api`, `autoconfigure`, and `internal` under the framework package.

## 12. Test with the testing kit

The `operator-framework-testing` module ships an in-memory CRUD API server and a small kit, so operator tests need neither a cluster nor mock-server plumbing:

```xml
<dependency>
    <groupId>com.huawei.dcs.modelengine</groupId>
    <artifactId>operator-framework-testing</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Drive the real controller runtime — informers, workers, queue, and secondary caches — against the in-memory server:

```java
try (var kit = OperatorTestKit.create()) {
    var runtime = kit.controller(registration);
    runtime.start();
    kit.client().configMaps().inNamespace(kit.client().getNamespace()).resource(configMap).create();
    // await effects through kit.client()
}
```

For direct reconciler invocations, `OperatorTestKit.context(primary)` returns a `ReconciliationContext` with the primary cache seeded, so by-index/get-by-key paths work without a runtime. The in-memory server's client namespace is `test` — use `kit.client().getNamespace()` instead of hard-coding one. Operators that watch Kubernetes Events must disable involvedObject field-selector filtering (`operator.framework.controller.filter-events-by-involved-object: false`), which the in-memory server cannot match. `example/echo-operator` contains a complete kit test (`EchoOperatorKitTest`).

## 13. Build and verify

Run the complete gate from the repository root:

```bash
mvn -f operator/framework/pom.xml clean verify
```

The gate runs tests, post-package integration checks, configuration metadata/resource checks, JAR/package-boundary checks, source JAR generation, and Checkstyle (production sources only: 120-column lines, no more than 5 method/constructor parameters, methods no longer than 50 non-empty lines, cyclomatic complexity no greater than 5). JaCoCo then enforces a bundle-level coverage floor — line coverage at least 75% and branch coverage at least 50% — with the HTML report under `target/site/jacoco/`; `operator/testing` applies the same thresholds.

For a faster test-only cycle:

```bash
mvn -f operator/framework/pom.xml test
```

The legacy `example/` and `stress-test/` modules were deleted deliberately; `stress-test/` stays absent. The current sample is `example/echo-operator`, a Spring Boot application built on the starter with unit tests, MockMvc admission endpoint tests, and a real-cluster end-to-end script:

```bash
example/echo-operator/scripts/e2e-test.sh
```

It deploys to a throwaway namespace with RBAC/TLS, registers real admission webhook configurations, and verifies mutation, validation, reconcile, event publication, garbage collection, health, and metrics against a live API server.

## Appendix A. Key interfaces at a glance

All types live under `com.huawei.dcs.modelengine.operator.framework.api` unless noted.

### Reconcile (`api.reconcile`)

| Type | Key members | Purpose |
| --- | --- | --- |
| `Reconciler<T>` | `ReconcileResult reconcile(T resource, ReconciliationContext<T> context) throws Exception` | User bean invoked once per work-queue key (§2) |
| `ReconciliationContext<T>` | record `(resourceKey, triggers, cache, caches)`; `cacheFor(Class<S>)`; `withoutCache(...)` | Per-request data: key, triggering events, primary informer cache, secondary caches for owned/watched types |
| `ReconcileResult` | `done()`, `requeueNow()`, `requeueAfter(Duration)` | Scheduling outcome of a reconcile |
| `Finalizers` | `isDeleting`, `present`, `add`, `remove` | Server-side finalizer JSON patch (§2) |
| `StatusUpdates` | `update(client, resource, status)` | JSON-merge-patch of the `/status` subresource (§2) |
| `Applies` | `apply(client, desired, fieldManager)`, `applyForcibly(...)` | Server-side apply of full desired state (§2) |
| Value types | `ReconciliationTrigger(eventType, role, resource)`, `ResourceKey(namespace, name)`, `ResourceReference.from(HasMetadata)`, enums `ResourceEventType`, `TriggerRole` | Describe why a reconcile fired and which resource it targets |

### Controller (`api.controller`)

| Type | Key members | Purpose |
| --- | --- | --- |
| `ControllerBuilder<T>` | `forResource(Class<T>, Reconciler<T>)` → `owns`, `watches`, `watchesKubernetesEvents`, `generationFilter`, `resyncPeriod`, `labelSelector`, `fieldSelector`, `indexField` → `build()` | Fluent controller definition (§3) |
| `ControllerRegistration<T>` | accessors: `resourceType`, `reconciler`, `ownedResources`, `secondaryWatches`, `indexFields`, ... | Immutable descriptor consumed by the runtime and the test kit |
| `ResourceMapper<S, T>` | `Collection<ResourceKey> map(ResourceEvent<S> event)` | Maps a secondary-resource event to primary keys; prefabs in `Mappers`: `ownerReferences`, `byLabel`, `byAnnotation`, `involvedObject` |
| `ResourceEvent<S>` | `added`, `updated`, `deleted`, `resync`; record `(type, resource, previousResource)` | Informer event handed to a mapper |

### Events (`api.event`)

| Type | Key members | Purpose |
| --- | --- | --- |
| `KubernetesEventPublisher` | `normal(obj, reason, message)`, `warning(obj, reason, message)` | Publishes Kubernetes Events for the involved object (§6) |

### Webhooks (`api.webhook`)

| Type | Key members | Purpose |
| --- | --- | --- |
| `AdmissionValidator<T>` | `AdmissionDecision validate(T current, AdmissionContext)` | Validating webhook bean; answer via `AdmissionDecision.allow()/deny(msg)` (§7) |
| `AdmissionMutator<T>` | `MutationResult<T> mutate(T current, AdmissionContext)` | Mutating webhook bean; answer via `MutationResult.unchanged()/mutated(resource)/denied(msg)` |
| `ResourceConverter<T>` | `ConversionResult<T> convert(T resource, ConversionContext)` | CRD version conversion; answer via `ConversionResult.converted(resource)/failed(msg)` |
| Context records | `AdmissionContext(uid, operation, resource, dryRun, user)`, `ConversionContext(sourceVersion, desiredVersion)` | Request metadata handed to webhook beans |

### Testing (`operator-framework-testing`)

| Type | Key members | Purpose |
| --- | --- | --- |
| `OperatorTestKit` | `create()`, `client()`, `controller(registrations...)`, `context(primary)`, `close()` | In-memory API server plus real controller runtime for user tests (§12) |
