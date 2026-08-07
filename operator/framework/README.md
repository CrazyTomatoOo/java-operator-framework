# Operator Framework Spring Boot Starter

A Spring Boot starter for Kubernetes operators built with Java 21, Spring Boot 3.5.15, and Fabric8 Kubernetes Client 7.3.0.

Chinese documentation: [README.zh-CN.md](README.zh-CN.md)

## Dependency

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The starter is enabled automatically. Spring discovers callback beans, starts the selected runtime after the application context is ready, and shuts it down through `SmartLifecycle`. Applications do not create a framework runtime or manage its lifecycle themselves.

## Minimal controller

Define a typed Spring bean. The generic resource type must be concrete so the starter can discover it.

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
    public ReconcileResult reconcile(ConfigMap resource, ReconciliationContext<ConfigMap> context) {
        return ReconcileResult.done();
    }
}
```

`ReconcileResult.requeueNow()` requests an immediate follow-up. `ReconcileResult.requeueAfter(Duration)` requests a delayed follow-up. Unhandled callback exceptions use the configured exponential retry policy.

For controller-only applications:

```yaml
operator:
  framework:
    mode: controller
```

No registration, startup, or shutdown call is required.

## Advanced controller registration

Use a `ControllerRegistration` bean to override per-controller filtering/resync and add owned resources, arbitrary watches, or Kubernetes Event subscription. The referenced reconciler must also be a Spring bean.

```java
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.api.controller.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.api.controller.Mappers;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class ControllerConfiguration {
    @Bean
    ControllerRegistration<MyResource> myResourceController(MyResourceReconciler reconciler) {
        return ControllerBuilder.forResource(MyResource.class, reconciler)
                .generationFilter(true)
                .resyncPeriod(Duration.ofMinutes(2))
                .labelSelector(Map.of("app", "my-operator"))
                .indexField("secretRef", resource -> resource.getSpec().getSecretRef())
                .owns(Deployment.class)
                .watches("configmaps", ConfigMap.class, Mappers.byLabel("operator.example/primary"))
                .watchesKubernetesEvents()
                .build();
    }
}
```

`labelSelector` and `fieldSelector` narrow the primary watch with server-side equality selectors; calling either again replaces that selector while keeping the other. `indexField` registers an informer index on a primary field, enabling O(1) cache lookups during reconciliation (see Reconciliation helpers below).

`owns` maps owner references. Prefer the typed `Mappers.ownerReferences(Class)` when the primary type is known; the no-argument variant matches every controller owner kind. `watches` uses the supplied `ResourceMapper`; built-in label and annotation mappers inspect both current and previous metadata on updates. They use the secondary namespace for bare names; a cluster-scoped secondary can use `namespace/name` to target a namespaced primary. Built-in mappers also support labels, annotations, and Kubernetes Event involved objects. `watchesKubernetesEvents()` subscribes to `core/v1` Events that refer to the primary resource: the informer is filtered server-side by `involvedObject.kind`/`involvedObject.apiVersion`, and aggregated Event updates (`count` increments) do not trigger reconciliation — only new Events, deletions, and resyncs do. Kubernetes Events are best-effort; do not use them as correctness-critical state and avoid publish/subscribe feedback loops.

## Reconciliation helpers

`ReconciliationContext<T>` carries the resource key, the normalized `triggers()` list (each a `ReconciliationTrigger` with event type, role, and triggering resource identity), and informer caches: `cache()` for the primary type and `cacheFor(Class)` for any type declared through `owns`/`manages`/`watches`. Fields registered through `ControllerBuilder.indexField` are queryable in O(1), for example `context.cache().getByIndex("secretRef", name)` — no API-server round-trip.

In controller-capable modes the `KubernetesClient` is an injectable bean. Static helpers in `api.reconcile` cover the common write paths; each works on a defensive copy, so informer-cached instances are never mutated:

- `Applies.apply(client, desired, fieldManager)` server-side-applies the full desired state — create-or-update in one call, and fields owned by other managers stay untouched. `applyForcibly` additionally takes ownership of conflicting fields. Always pass an explicit, unique field manager.
- `Owners.setController(owner, dependent)` stamps the `controller=true` owner reference on a copy, enabling Kubernetes garbage collection and owner-reference watch mapping.
- `Dependents.apply(client, dependent, primary, context, fieldManager)` computes the desired state from a `DependentResource`, adds the controller owner reference, and applies it. Register the dependent through `ControllerBuilder.manages(dependent)` so its events also trigger reconciliation.
- `Finalizers.isDeleting`/`present`/`add`/`remove` implement the finalizer pattern for cleaning up external resources.
- `StatusUpdates.update(client, resource, status)` JSON-merge-patches the `/status` subresource without touching the resource itself; the CRD must declare the status subresource.

```java
@Component
final class MyResourceReconciler implements Reconciler<MyResource> {
    private static final String FIELD_MANAGER = "my-operator";
    private final KubernetesClient client;
    private final DependentResource<Deployment, MyResource> deployment = new MyDeploymentDependent();

    MyResourceReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public ReconcileResult reconcile(MyResource resource, ReconciliationContext<MyResource> context) {
        if (Finalizers.isDeleting(resource)) {
            return ReconcileResult.done();
        }
        Dependents.apply(this.client, this.deployment, resource, context, FIELD_MANAGER);
        StatusUpdates.update(this.client, resource, new MyResourceStatus("Ready"));
        return ReconcileResult.done();
    }
}
```

## Configuration

All framework-specific settings use the `operator.framework` prefix.

| Property | Default | Meaning |
| --- | ---: | --- |
| `operator.framework.enabled` | `true` | Enables all starter auto-configuration. `false` creates no framework runtime beans. |
| `operator.framework.mode` | `combined` | `controller`, `webhook`, or `combined`; see mode rules below. |
| `operator.framework.controller.namespace` | unset | Watches this namespace; blank falls back to the Fabric8 client namespace, then `default`. |
| `operator.framework.controller.cluster-scoped` | `false` | Watches all namespaces; conflicts with a nonblank controller namespace. |
| `operator.framework.controller.worker-threads` | `1` | Reconciliation workers per controller. |
| `operator.framework.controller.resync-period` | `60s` | Informer resync interval; `0` disables periodic resync. |
| `operator.framework.controller.generation-change-filter` | `true` | Ignores ordinary primary updates when generation, deletion timestamp, and finalizers are unchanged. |
| `operator.framework.controller.filter-events-by-involved-object` | `true` | Narrows the Kubernetes-Event watch server-side by `involvedObject.kind`/`apiVersion`; disable when the API server cannot match those fields (for example the in-memory test server). |
| `operator.framework.controller.startup-retry-delay` | `5s` | Delay before retrying controller or leader-election startup/readiness failures. |
| `operator.framework.leader-election.enabled` | `false` | Enables Fabric8 Lease-based leader election. |
| `operator.framework.leader-election.lease-name` | `${spring.application.name}-leader` | Lease name; the application-name fallback is sanitized for Kubernetes. |
| `operator.framework.leader-election.namespace` | inherited | Uses the controller namespace, then Fabric8 client namespace, then `default`. |
| `operator.framework.leader-election.lease-duration` | `15s` | Lease duration. |
| `operator.framework.leader-election.renew-deadline` | `10s` | Renew deadline. |
| `operator.framework.leader-election.retry-period` | `2s` | Lease retry period. |
| `operator.framework.retry.initial-delay` | `500ms` | Initial delay after a reconciler exception. |
| `operator.framework.retry.max-delay` | `30s` | Maximum exponential retry delay. |
| `operator.framework.retry.max-attempts` | `5` | Failed attempts before the exception becomes terminal. |
| `operator.framework.rate-limit.minimum-interval` | `5s` | Minimum interval per controller/resource key; `0` disables throttling. |
| `operator.framework.events.enabled` | `true` | Creates `KubernetesEventPublisher` in controller-capable modes. |
| `operator.framework.events.component` | `spring.application.name` | Kubernetes Event reporting/source component. |
| `operator.framework.events.aggregation-window` | `5m` | Deterministic aggregation window for identical Kubernetes Events. |
| `operator.framework.events.max-cache-entries` | `1000` | Maximum in-memory aggregation entries. |

Durations use Spring Boot duration syntax. Worker threads, retry attempts, and event cache entries must be positive. Resync and the rate-limit interval may be zero; startup retry, event aggregation, retry delays, and leader-election durations must be positive. Retry `initial-delay` must not exceed `max-delay`, and leader election must satisfy `retry-period < renew-deadline < lease-duration`.

Mode validation is strict:

- `controller`: requires at least one typed `Reconciler` or `ControllerRegistration` bean and does not create webhook MVC routes.
- `webhook`: requires at least one typed admission or conversion callback bean and does not create controller/client/event-publisher infrastructure.
- `combined`: requires both groups and creates both sides.

Missing or ambiguous callback types, duplicate controller resource types, unsafe webhook bean names, and incomplete mode configuration fail application startup.

## Leader election

Enable leader election only for controller-capable modes:

```yaml
spring:
  application:
    name: my-operator
operator:
  framework:
    mode: controller
    leader-election:
      enabled: true
      lease-duration: 15s
      renew-deadline: 10s
      retry-period: 2s
```

The Lease name defaults to the sanitized `spring.application.name` plus `-leader`. Its namespace inherits the controller namespace, then the Fabric8 client namespace, then `default`; both can be overridden with `lease-name` and `namespace`. Identity uses `HOSTNAME` when present. Standby replicas remain live/ready without running informers. A new leader becomes ready after its informers synchronize. Grant Lease read/create/update permissions through deployment RBAC.

## Kubernetes Events

Inject the public publisher into any Spring bean:

```java
import com.huawei.dcs.modelengine.operator.framework.api.event.KubernetesEventPublisher;

@Component
final class StatusReporter {
    private final KubernetesEventPublisher events;

    StatusReporter(KubernetesEventPublisher events) {
        this.events = events;
    }

    void report(MyResource resource) {
        events.normal(resource, "Reconciled", "Dependent resources are current");
        // events.warning(resource, "InvalidSpec", "The requested value is invalid");
    }
}
```

The reporting component is `spring.application.name` (default `operator-framework`). Publishing requires `create`, `get`, and `update` permissions on `events` in the involved object's namespace. Set `operator.framework.events.enabled=false` when event publication is not needed.

## Webhook callbacks and fixed routes

Webhook routing is determined by the Spring bean name. Bean names must match `[a-z0-9][a-z0-9._-]*` — Kubernetes rejects webhook `clientConfig.service.path` segments containing uppercase letters, so the framework enforces lowercase RFC 1123 names at startup.

```java
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionDecision;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionMutator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.AdmissionValidator;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionContext;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.MutationResult;
import com.huawei.dcs.modelengine.operator.framework.api.webhook.ResourceConverter;
import org.springframework.context.annotation.Bean;

@Bean("myresourcevalidator")
AdmissionValidator<MyResource> validator() {
    return (current, context) -> current.getSpec().isValid()
            ? AdmissionDecision.allow()
            : AdmissionDecision.deny("spec is invalid");
}

@Bean("myresourcemutator")
AdmissionMutator<MyResource> mutator() {
    return (current, context) -> {
        current.getMetadata().getLabels().putIfAbsent("managed-by", "my-operator");
        return MutationResult.mutated(current);
    };
}

@Bean("myresourceconverter")
ResourceConverter<MyResource> converter() {
    return (resource, context) -> ConversionResult.converted(convertTo(resource, context.desiredVersion()));
}
```

The fixed HTTP routes are:

- `POST /operator-framework/webhooks/validate/{beanName}`
- `POST /operator-framework/webhooks/mutate/{beanName}`
- `POST /operator-framework/webhooks/convert/{beanName}`

Validation returns `AdmissionDecision.allow()` or `AdmissionDecision.deny(message)`. Mutation returns `MutationResult.unchanged()`, `MutationResult.mutated(resource)`, or `MutationResult.denied(message)`; the transport computes and Base64-encodes the JSON Patch. Conversion returns `ConversionResult.converted(resource)` or `ConversionResult.failed(message)`. Context objects expose stable request identity/version data rather than transport objects; admission callbacks also receive operation-specific `AdmissionReview` options as an immutable JSON-compatible map.

## External TLS and Kubernetes registration

Webhook HTTPS uses Spring Boot's server and standard SSL properties. Mount a certificate and private key supplied by your platform, for example from a Kubernetes Secret:

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

The starter does **not** generate certificates. It also does **not** create or update `ValidatingWebhookConfiguration`, `MutatingWebhookConfiguration`, Services, Secrets, or CRD conversion webhook configuration. Provision those resources with Helm, Kustomize, an admission platform, or another deployment tool, and set their service paths to the fixed routes above. The Kubernetes webhook `caBundle` must trust the externally supplied server certificate.

## Actuator health and Prometheus

The starter contributes `operatorFramework` health and Micrometer metrics to standard Spring Boot Actuator endpoints. Enable probes and expose Prometheus explicitly:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

Endpoints:

- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`

Readiness reflects informer synchronization for an active controller leader and callback availability/failures for webhooks. The starter uses the application `MeterRegistry`; no separate health or metrics server exists.

## Lifecycle and KubernetesClient ownership

- Spring starts and stops the runtime through `SmartLifecycle`.
- Shutdown stops new queue entries, stops informers, drains workers, and then interrupts remaining workers after `spring.lifecycle.timeout-per-shutdown-phase` (default used by the starter: `30s`).
- A missing `KubernetesClient` in controller-capable modes is created by auto-configuration and closed by the starter.
- A user-supplied `KubernetesClient` is reused and is never closed by starter ownership logic. Its declaring application remains responsible for its lifecycle.
- Webhook-only mode does not require or auto-create a Kubernetes client.

## Supported package boundary

Only `com.huawei.dcs.modelengine.operator.framework.api.*` is a supported application API. `...autoconfigure.*` exists for Spring Boot loading/configuration, and `...internal.*` is implementation detail. Production classes are restricted to these three roots; applications must not depend on `internal` classes.

## Testing

The `operator-framework-testing` module provides `OperatorTestKit`: an in-memory Kubernetes API server plus client that can start a real controller runtime from a `ControllerRegistration`, or build cache-backed `ReconciliationContext` instances for direct reconciler invocation.

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework-testing</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

```java
try (var kit = OperatorTestKit.create()) {
    var runtime = kit.controller(registration);
    runtime.start();
    kit.client().configMaps().inNamespace("default").resource(configMap).create();
    // await effects through kit.client()
}
```

Controllers watching Kubernetes Events must set `operator.framework.controller.filter-events-by-involved-object=false`, because the in-memory server cannot match `involvedObject` field selectors. Build the module with `mvn -f operator/testing/pom.xml clean verify`.

## Build and quality gates

From the repository root:

```bash
mvn -f operator/framework/pom.xml clean verify
```

To install the snapshot locally:

```bash
mvn -f operator/framework/pom.xml clean install
```

`verify` runs unit/integration tests, packaging checks, attached source JAR creation, and Checkstyle. Production-source limits are 120 columns, at most 5 parameters, at most 50 non-empty lines per method, and cyclomatic complexity at most 5.

The legacy `example/` and `stress-test/` modules were intentionally removed; `stress-test/` stays absent. The current sample lives in `example/echo-operator`, including a real-cluster end-to-end script (`scripts/e2e-test.sh`).
