# Developer Guide

This guide shows how to build a new Kubernetes operator using `operator-framework`.

## 1. Install the SDK

From the repository root:

```bash
mvn -f operator/framework/pom.xml clean install
```

This installs `com.huawei.dcs.modelengine:operator-framework:0.1.0-SNAPSHOT` into your local Maven repository.

## 2. Create a Maven project

Create a new Maven project with Java 21. Add the SDK dependency:

```xml
<dependency>
  <groupId>com.huawei.dcs.modelengine</groupId>
  <artifactId>operator-framework</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Also add the fabric8 `kubernetes-client` and `generator-annotations` dependencies at version `7.7.0`.

Add the `crd-generator-maven-plugin` and `java-generator-maven-plugin` to generate CRDs and Java classes:

```xml
<plugin>
  <groupId>io.fabric8</groupId>
  <artifactId>crd-generator-maven-plugin</artifactId>
  <version>${fabric8.version}</version>
  <executions>
    <execution>
      <phase>compile</phase>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
</plugin>

<plugin>
  <groupId>io.fabric8</groupId>
  <artifactId>java-generator-maven-plugin</artifactId>
  <version>${fabric8.version}</version>
  <executions>
    <execution><goals><goal>generate</goal></goals></execution>
  </executions>
  <configuration>
    <source>${project.basedir}/src/main/resources/crd</source>
    <target>${project.build.directory}/generated-sources/java</target>
  </configuration>
</plugin>
```

## 3. Define CRD Java classes

Create a resource class:

```java
package com.example.myoperator.api.v1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("example.com")
@Version("v1alpha1")
@Kind("MyResource")
@Plural("myresources")
@ShortNames({"my"})
public class MyResource extends CustomResource<MySpec, MyStatus> implements Namespaced {
}
```

Create plain spec and status classes. Use `@Required` and `@Default` from `io.fabric8.generator.annotation` to control the generated OpenAPI schema.

## 4. Write a Reconciler

```java
package com.example.myoperator;

import com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Request;
import com.huawei.dcs.modelengine.operator.framework.reconciler.Result;

public class MyReconciler implements Reconciler<MyResource> {
    @Override
    public Result reconcile(Request request, MyResource resource) {
        try {
            // create or update child resources
            return Result.done();
        } catch (Exception e) {
            return Result.error(e);
        }
    }
}
```

Use `Result.requeueNow()` or `Result.requeueAfter(Duration)` when you need another pass. The operator applies the configured retry policy automatically for `Result.error(...)`.

## 5. Primary and Secondary Resources

The *primary resource* is the type you registered the reconciler for. When a primary `MyResource` changes, the operator creates a `Request` for it and calls your reconciler.

A *secondary resource* is any other Kubernetes resource whose changes should also enqueue a primary resource. For example, you might want to reconcile a `MyResource` when one of its `ConfigMap` dependencies changes.

Register a controller with a secondary `ConfigMap` watch using `ControllerBuilder`:

```java
import com.huawei.dcs.modelengine.operator.framework.ControllerBuilder;
import com.huawei.dcs.modelengine.operator.framework.ControllerRegistration;
import com.huawei.dcs.modelengine.operator.framework.Operator;
import com.huawei.dcs.modelengine.operator.framework.source.Mappers;
import io.fabric8.kubernetes.api.model.ConfigMap;

ControllerRegistration<MyResource> registration = ControllerBuilder.forResource(MyResource.class)
    .withReconciler(new MyReconciler())
    .watches("configmaps", ConfigMap.class, Mappers.byLabel("my-resource-name"))
    .build();

Operator operator = new Operator().withNamespace("default");
operator.register(registration);
operator.start();
```

`watches` registers a secondary informer. The third argument is a `ResourceMapper<S, P>` that translates a secondary event into one or more primary `Request`s. The example above uses `Mappers.byLabel(...)`, which maps the secondary resource to the primary resource named by the label value.

### `owns` vs `watches`

Use `owns` when the reconciler creates secondary resources and sets an owner reference on them. The framework then uses owner references to map the secondary event back to the primary resource:

```java
ControllerBuilder.forResource(MyResource.class)
    .withReconciler(new MyReconciler())
    .owns(Deployment.class)
    .build();
```

Use `watches` for arbitrary resources that are linked by labels, annotations, or any other custom logic. The `ResourceMapper` interface lets you provide the mapping yourself:

```java
ResourceMapper<ConfigMap, MyResource> mapper = (configMap, event) -> {
    // return a collection of primary Request objects
    return List.of(new Request("default", configMap.getMetadata().getLabels().get("my-resource-name")));
};
```

### Inspecting the trigger

Each `Request` carries one or more `Trigger` objects describing what caused the reconciliation. The reconciler can inspect them to decide what changed.

```java
import com.huawei.dcs.modelengine.operator.framework.reconciler.Trigger;
import com.huawei.dcs.modelengine.operator.framework.reconciler.TriggerRole;

public Result reconcile(Request request, MyResource resource) {
    if (request.triggeredByPrimary()) {
        // the primary resource itself changed
    } else if (request.trigger().map(Trigger::role).orElse(null) == TriggerRole.SECONDARY) {
        // triggered by a secondary resource
        for (Trigger trigger : request.triggers()) {
            System.out.println(trigger.kind() + " " + trigger.eventType());
        }
    }
    return Result.done();
}
```

A `Trigger` exposes the event type, resource kind, namespace, name, UID, and role (`TriggerRole.PRIMARY` or `TriggerRole.SECONDARY`).

### Migration note

The existing `Operator.register(Class, Reconciler)` API is unchanged and still works:

```java
operator.register(MyResource.class, new MyReconciler());
```

This creates a registration with no secondary watches. You only need to switch to `ControllerBuilder` when you want to add secondary resource watches.

## 6. Configure leader election

```java
import com.huawei.dcs.modelengine.operator.framework.leader.LeaderElectionManager;

LeaderElectionManager leader = new LeaderElectionManager(client, "my-lock", namespace)
    .withLeaseDuration(Duration.ofSeconds(15))
    .withRenewDeadline(Duration.ofSeconds(10))
    .withRetryPeriod(Duration.ofSeconds(2));
leader.run(() -> operator.start());
```

Only the leader starts the operator. When leadership is lost, the operator runnable is interrupted.

## 7. Add metrics and health

```java
import com.huawei.dcs.modelengine.operator.framework.metrics.MetricsHealthServer;

MetricsHealthServer server = new MetricsHealthServer(8080);
server.start();
```

Endpoints:

- `GET /healthz` - liveness, returns 200
- `GET /readyz` - readiness, returns 200 or 503
- `GET /metrics` - Prometheus metrics

Add custom readiness checks:

```java
server.addReadinessCheck(() -> operator.eventSources().stream()
    .allMatch(s -> s.getInformer().hasSynced()));
```

The `MetricsHealthServer.metricsRegistry()` is a Micrometer `MeterRegistry` you can pass to reconcilers for custom counters and timers.

## 8. Generate CRD YAML from Java classes

Compile the project:

```bash
mvn -f example/echo-operator/pom.xml clean compile
```

The CRD generator writes the YAML to:

```text
example/echo-operator/target/classes/META-INF/fabric8/echoresources.example.com-v1.yml
```

Verify:

```bash
ls example/echo-operator/target/classes/META-INF/fabric8/
```

## 9. Generate Java classes from CRD YAML

Place a CRD YAML file under:

```text
src/main/resources/crd/
```

Then compile:

```bash
mvn -f example/echo-operator/pom.xml clean compile
```

The java-generator plugin writes generated classes to:

```text
example/echo-operator/target/generated-sources/java/
```

Verify:

```bash
ls example/echo-operator/target/generated-sources/java/
```

These generated classes are useful for importing CRDs authored by another team or for bootstrapping a new operator from an existing schema.

## 10. Run the operator

Use the `exec-maven-plugin` or package a runnable jar. The Echo Operator example uses:

```bash
mvn -f example/echo-operator/pom.xml exec:java -Dexec.mainClass=com.example.echooperator.EchoOperatorMain
```

Or use the helper script:

```bash
example/echo-operator/scripts/local-run.sh
```

## 11. Verify endpoints

When the operator is running:

```bash
curl -s http://localhost:8080/healthz
curl -s http://localhost:8080/readyz
curl -s http://localhost:8080/metrics
```

## 12. Add admission webhooks

Admission webhooks run on a separate TLS server so Kubernetes can call them over HTTPS.

Create the TLS server:

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer;
import com.huawei.dcs.modelengine.operator.framework.webhook.cert.CertWatcher;
import java.nio.file.Path;

WebhookServer webhookServer = WebhookServer.withCertWatcher(
    WebhookServer.DEFAULT_HOST, 8443,
    Path.of("/etc/operator/certs/tls.crt"),
    Path.of("/etc/operator/certs/tls.key"),
    Path.of("/etc/operator/certs/ca.crt"),
    CertWatcher.DEFAULT_POLLING_INTERVAL);
```

Implement a validator and a mutator:

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionResult;
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionValidator;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionRequest;
import io.fabric8.kubernetes.api.model.admission.v1.AdmissionResponse;

public class MyValidator implements AdmissionValidator<MyResource> {
    @Override
    public AdmissionResponse validate(AdmissionRequest request, MyResource resource) {
        if (resource.getSpec().replicas < 0) {
            return AdmissionResult.denied("replicas must not be negative");
        }
        return AdmissionResult.allowed();
    }
}
```

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionMutator;

public class MyMutator implements AdmissionMutator<MyResource> {
    @Override
    public AdmissionResponse mutate(AdmissionRequest request, MyResource resource) {
        return AdmissionResult.jsonPatch(
            "[{\"op\":\"add\",\"path\":\"/metadata/annotations/my.example.com~1defaulted\",\"value\":\"true\"}]");
    }
}
```

Register them on the server:

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.admission.AdmissionHandler;

AdmissionHandler admissionHandler = new AdmissionHandler(client);
admissionHandler.registerValidator("my.example.com", MyResource.class, new MyValidator());
admissionHandler.registerMutator("my.example.com", MyResource.class, new MyMutator());
admissionHandler.register(webhookServer);
webhookServer.start();
```

This exposes `/validate/my.example.com` and `/mutate/my.example.com`. The handler deserializes the `AdmissionReview`, converts `request.object` to the registered resource class, and serializes the response. Mutating responses are base64-encoded automatically.

To self-register webhook configurations at startup:

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookRegistrationConfig;
import com.huawei.dcs.modelengine.operator.framework.webhook.registration.WebhookSelfRegistration;

WebhookRegistrationConfig registrationConfig = WebhookRegistrationConfig.builder(
    "my-operator", "my-namespace", Path.of("/etc/operator/certs/ca.crt"))
    .withServicePort(443)
    .withFailurePolicy("Fail")
    .withTimeoutSeconds(10)
    .withSideEffects("None")
    .build();

WebhookSelfRegistration registration = new WebhookSelfRegistration(client, registrationConfig);
registration.register(admissionHandler);
```

`WebhookSelfRegistration` reads the CA bundle, base64-encodes it, and creates or replaces one `ValidatingWebhookConfiguration` and one `MutatingWebhookConfiguration` per registered webhook name. The operator needs RBAC permissions for `admissionregistration.k8s.io`.


### Certificate generation

By default, `WebhookCertificateGenerator` creates the CA bundle and server certificate for the webhook server. It writes `ca.crt`, `tls.crt`, and `tls.key` to `WEBHOOK_CERT_DIRECTORY` (`/tmp/echo-operator/certs` by default). The certificate SANs cover the service name and its FQDN variants, and the server certificate sets the `serverAuth` extended key usage. Set `WEBHOOK_CERT_AUTO_GENERATE=false` to use the file-based fallback and load the CA bundle and sibling `tls.crt`/`tls.key` files from `WEBHOOK_CA_BUNDLE_PATH`.

## 13. Add conversion webhooks

For CRDs with multiple versions, implement a `ConversionWebhookHandler` and mount it on the same TLS server.

```java
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionHandler;
import com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionResult;
import io.fabric8.kubernetes.api.model.HasMetadata;

ConversionHandler conversionHandler = new ConversionHandler(client);
conversionHandler.register("example.com/v1alpha1", "example.com/v1alpha2",
    (desiredVersion, resource) -> ConversionResult.converted(toV2(resource)));
conversionHandler.register("example.com/v1alpha2", "example.com/v1alpha1",
    (desiredVersion, resource) -> ConversionResult.converted(toV1(resource)));
conversionHandler.register(webhookServer);
```

Mark versions in the resource classes:

```java
@Version(value = "v1alpha1", storage = false, served = true, deprecated = true)
public class MyResourceV1 extends CustomResource<MySpecV1, MyStatusV1> implements Namespaced {
}

@Version(value = "v1alpha2", storage = true, served = true)
public class MyResourceV2 extends CustomResource<MySpecV2, MyStatusV2> implements Namespaced {
}
```

The conversion handler receives `apiextensions.k8s.io/v1 ConversionReview` requests, dispatches by `(source apiVersion, desired apiVersion)`, and returns a review response. Return `ConversionResult.converted(...)` on success or `ConversionResult.failed(...)` on error. Same-version requests pass through unchanged.

## 14. Run webhooks, metrics, and operator together

A typical startup sequence looks like this:

```java
webhookServer.start();
webhookSelfRegistration.register(admissionHandler);
metricsHealthServer.start();
operator.start();
```

The shutdown sequence reverses the order:

```java
webhookServer.stop();
operator.stop();
metricsHealthServer.close();
client.close();
```

The webhook server and the metrics/health server are separate. Metrics stay on port 8080 by default, while admission and conversion webhooks share the TLS server on port 8443.
