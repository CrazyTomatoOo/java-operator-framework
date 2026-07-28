# Operator Framework

A minimal, fabric8-based Java SDK for building Kubernetes operators. It gives you the runtime pieces you need without pulling in Quarkus, Spring Boot, or the Java Operator SDK.

## Features

- `Operator` launcher with namespace-scoped or cluster-scoped informers
- `Reconciler<T>` interface plus `Request` and `Result`
- `ResourceEventSource<T>` wrapping a fabric8 `SharedIndexInformer`
- `LeaderElectionManager` built on fabric8 leader election
- Combined `MetricsHealthServer` exposing `/metrics`, `/healthz`, and `/readyz`
- `RetryPolicy` / `ExponentialBackoffRetryPolicy` and `RateLimiter`
- `OwnerReferenceHelper` and `FinalizerHelper` utilities
- Java 21, Maven-based

## Maven coordinates

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Core API

### Operator

`com.huawei.dcs.modelengine.operator.framework.Operator` registers controllers and runs informer/worker loops.

```java
Operator operator = new Operator()
    .withNamespace("default")
    .withWorkerThreads(2);
operator.register(MyResource.class, new MyReconciler(client));
operator.start();
```

Call `operator.stop()` or use try-with-resources to shut down informers and workers.

### ControllerBuilder

Use `ControllerBuilder` to register a controller with secondary resource watches. This example watches `ConfigMap` resources that are linked to a `MyResource` by the `my-resource-name` label:

```java
import com.huawei.dcs.modelengine.operator.framework.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.source.Mappers;
import io.fabric8.kubernetes.api.model.ConfigMap;

ControllerRegistration<MyResource> registration = ControllerBuilder.forResource(MyResource.class)
    .withReconciler(new MyReconciler())
    .watches("configmaps", ConfigMap.class, Mappers.byLabel("my-resource-name"))
    .build();

operator.register(registration);
```

When the secondary `ConfigMap` changes, the operator enqueues the matching `MyResource` with a `SECONDARY` trigger. If you only need the primary resource, `operator.register(MyResource.class, new MyReconciler())` still works.

### Generation-change filtering

By default every update event on the primary resource enqueues a reconcile, including status writebacks. For controllers that write status, this causes self-triggered "echo" reconciles that waste worker threads.

Use `.withGenerationChangeFilter()` to filter update events at the source:

```java
ControllerRegistration<MyResource> registration = ControllerBuilder.forResource(MyResource.class)
    .withReconciler(new MyReconciler())
    .withGenerationChangeFilter()
    .withResyncPeriod(Duration.ZERO) // optional: disable periodic resync
    .build();
```

When enabled, an update event on the primary resource is enqueued only when:

- the resource's `generation` changed (spec update),
- deletion was requested (`deletionTimestamp` newly set), or
- the `finalizers` changed.

Add and delete events always enqueue, and secondary source events are never filtered. With the filter on, the periodic resync no longer re-enqueues unchanged resources; pass `.withResyncPeriod(Duration.ZERO)` to disable the default 60-second resync entirely, or keep it as a cache self-heal fallback. `.withGenerationChangeFilter(boolean)` is also available for configuration-driven toggling.

The filter is off by default, so existing controllers are unaffected: `operator.register(MyResource.class, reconciler)` and `ControllerBuilder` usage without `.withGenerationChangeFilter()` behave exactly as before.

Note: for CRDs without the status subresource, status writes still bump `generation`, so the filter has no effect there.

### Reconciler

```java
public interface Reconciler<T extends HasMetadata> {
    Result reconcile(Request request, T resource);
}
```

Return values:

- `Result.done()` - finished, clear retry state
- `Result.requeueNow()` - put back on the queue immediately
- `Result.requeueAfter(Duration)` - requeue after a delay
- `Result.error(Throwable)` - failed, retry with the configured policy

### ResourceEventSource

`ResourceEventSource<T>` translates add/update/delete events from a fabric8 informer into `Request` objects on an internal blocking queue. Default resync interval is 60 seconds.

### LeaderElectionManager

`LeaderElectionManager` wraps fabric8 `LeaderElector`. Defaults:

- lease duration: 15s
- renew deadline: 10s
- retry period: 2s

```java
LeaderElectionManager leader = new LeaderElectionManager(client, "my-lock", "default")
    .withLeaseDuration(Duration.ofSeconds(15));
leader.run(() -> operator.start());
```

### MetricsHealthServer

`MetricsHealthServer` starts a single JDK `HttpServer` on port 8080 by default and exposes:

- `/metrics` - Prometheus exposition format
- `/healthz` - liveness, always returns 200
- `/readyz` - readiness, 200 when all checks pass, otherwise 503

```java
MetricsHealthServer server = new MetricsHealthServer(8080);
server.addReadinessCheck(() -> operator.eventSources().stream()
    .allMatch(s -> s.getInformer().hasSynced()));
server.start();
```

### Retry and rate limiting

`ExponentialBackoffRetryPolicy` defaults: initial interval 500ms, max interval 30s, max attempts 5.

`RateLimiter` limits how often the same resource key is processed. Default minimum interval is 5 seconds.

### Helpers

- `OwnerReferenceHelper.createControllerOwnerReference(owner)` returns an `OwnerReference` with `controller=true` and `blockOwnerDeletion=true`.
- `FinalizerHelper.hasFinalizer(resource, finalizer)`, `addFinalizer(...)`, `removeFinalizer(...)`.

## Build

Install the SDK into your local Maven repository:

```bash
mvn -f operator/framework/pom.xml clean install
```

Run the test suite:

```bash
mvn -f operator/framework/pom.xml test
```

Expected result for both commands is `BUILD SUCCESS`.

## Admission webhooks

`WebhookServer` is a TLS server built on JDK `HttpsServer`. It defaults to `0.0.0.0:8443`, loads a certificate chain and private key from PEM files, and optionally watches `tls.crt`, `tls.key`, and `ca.crt` for changes.

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import java.nio.file.Path;

WebhookServer webhookServer = WebhookServer.withCertWatcher(
    WebhookServer.DEFAULT_HOST, 8443,
    Path.of("/etc/operator/certs/tls.crt"),
    Path.of("/etc/operator/certs/tls.key"),
    Path.of("/etc/operator/certs/ca.crt"),
    CertWatcher.DEFAULT_POLLING_INTERVAL);
```

Admission webhooks are implemented with `AdmissionValidator<T>` and `AdmissionMutator<T>`.

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionValidator;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

public class MyValidator implements AdmissionValidator<MyResource> {
    @Override
    public AdmissionResponse validate(AdmissionRequest request, MyResource resource) {
        if (resource.getSpec() == null) {
            return AdmissionResult.denied("spec is required");
        }
        return AdmissionResult.allowed();
    }
}

AdmissionHandler handler = new AdmissionHandler(client);
handler.registerValidator("my.example.com", MyResource.class, new MyValidator());
handler.registerMutator("my.example.com", MyResource.class, new MyMutator());
handler.register(webhookServer);
webhookServer.start();
```

`AdmissionHandler.register(WebhookServer)` exposes `/validate/{name}` and `/mutate/{name}` for every registered validator/mutator. Mutators return a raw JSON Patch string from `AdmissionResult.jsonPatch(...)`; the handler base64-encodes it and sets `patchType` to `JSONPatch`.

Each validator and mutator can be individually enabled or disabled at runtime. Disabled webhooks are excluded from Kubernetes registration (`enabledValidatorNames()` / `enabledMutatorNames()`) and return a deny response if the HTTP endpoint is called:

```java
admissionHandler.disableValidator("my.example.com"); // returns deny if called
admissionHandler.enableValidator("my.example.com");  // re-enables
admissionHandler.isValidatorEnabled("my.example.com"); // check state
admissionHandler.enabledValidatorNames(); // only enabled, for K8s registration
```

To register webhook configurations in Kubernetes at startup, use `WebhookSelfRegistration` with `WebhookRegistrationConfig`:

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookRegistrationConfig;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookSelfRegistration;

WebhookRegistrationConfig config = WebhookRegistrationConfig.builder(
    // file-based CA bundle fallback
    "my-operator", "my-namespace", Path.of("/etc/operator/certs/ca.crt")
    .withServicePort(443)
    .withFailurePolicy("Fail")
    .withTimeoutSeconds(10)
    .withSideEffects("None")
    .build();

WebhookSelfRegistration registration = new WebhookSelfRegistration(client, config);
registration.register(handler);
```

`register(handler)` reads the CA bundle from disk, base64-encodes it, and creates or replaces one `ValidatingWebhookConfiguration` and one `MutatingWebhookConfiguration` per registered webhook name.

You can also generate the CA bundle and server certificate automatically with `WebhookCertificateGenerator` (`com.huawei.dcs.modelengine.operator.framework.webhook.cert`). It creates `ca.crt`, `tls.crt`, and `tls.key` with SANs for the service name and its namespace-scoped FQDN variants, and sets the `serverAuth` extended key usage on the server certificate. `EchoOperatorMain` uses this generator by default, writing certificates to `WEBHOOK_CERT_DIRECTORY`. The file-based example above is still available when `WEBHOOK_CERT_AUTO_GENERATE` is set to `false`.

## Conversion webhooks

For multi-version CRDs, the SDK provides a conversion webhook handler that mounts on the same `WebhookServer`.

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionWebhookHandler;
import io.fabric8.kubernetes.api.model.HasMetadata;

ConversionHandler conversionHandler = new ConversionHandler(client);
conversionHandler.register("example.com/v1alpha1", "example.com/v1alpha2",
    (desiredVersion, resource) -> ConversionResult.converted(convertToV2(resource)));
conversionHandler.register("example.com/v1alpha2", "example.com/v1alpha1",
    (desiredVersion, resource) -> ConversionResult.converted(convertToV1(resource)));
conversionHandler.register(webhookServer);
```

`ConversionWebhookHandler.convert(desiredVersion, HasMetadata)` returns a `ConversionResult` from `ConversionResult.converted(...)` or `ConversionResult.failed(...)`. The handler unmarshals `apiextensions.k8s.io/v1 ConversionReview`, dispatches by `(source apiVersion, desired apiVersion)`, and returns a review response. Same-version requests pass through unchanged; unregistered pairs return a failure status in the conversion response.

The conversion handler can be enabled or disabled at runtime. When disabled, `dispatch` returns a failure response:

```java
conversionHandler.disable();   // returns failure if called
conversionHandler.enable();    // re-enables
conversionHandler.isEnabled(); // check state
```

Mark versions in the resource class with fabric8 `@Version`:

```java
@Version(value = "v1alpha1", storage = false, served = true, deprecated = true)
public class MyResourceV1 extends CustomResource<MySpecV1, MyStatusV1> implements Namespaced {
}

@Version(value = "v1alpha2", storage = true, served = true)
public class MyResourceV2 extends CustomResource<MySpecV2, MyStatusV2> implements Namespaced {
}
```
