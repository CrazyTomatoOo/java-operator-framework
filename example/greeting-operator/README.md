# Greeting Operator (advanced example)

The advanced sample for `operator-framework-spring-boot-starter`: a typed
**custom-resource operator** exercising the framework's deep reconcile and
registration APIs on a `Greeting` CRD with two API versions, a `status`
subresource, and a conversion webhook callback.

## What it demonstrates

- **Typed custom-resource model** — `Greeting` implements `HasMetadata` with the
  `@Group`/`@Version`/`@Kind` annotations and the `Namespaced` marker, plus a
  `GreetingList` for typed list/watch operations.
- **Managed dependent + server-side apply** — `ControllerBuilder.manages` +
  `Dependents.apply` compute the owned `<name>-child` ConfigMap from
  `GreetingConfigMap.desired`, stamp the controller owner reference, and submit it
  with `Applies` under the `greeting-operator` field manager.
- **Finalizer pattern** — `Finalizers.add` on first sighting, and
  `Finalizers.isDeleting`/`remove` on deletion to clean up the **non-owned**
  external state ConfigMap `<name>-external` (no owner reference, so garbage
  collection cannot remove it).
- **Status subresource** — `StatusUpdates.update` writes
  `status.observedGeneration` (the informer generation), `phase`, and the rendered
  message through `/status` without touching the cached instance.
- **Requeue now** — `ReconcileResult.requeueNow` right after the finalizer add
  (its patch must land in the store) and while the applied child is not yet
  visible in the owned ConfigMap cache.
- **Generation filter and resync** — `generationFilter(true)` ignores status-only
  updates; `resyncPeriod(Duration.ofMinutes(2))` forces periodic drift correction.
- **Secondary watch** — `watches("styles", ConfigMap, Mappers.byLabel)` re-renders
  every greeting referencing a styles ConfigMap when the styles ConfigMap changes.
- **Retry policy** — explicit `operator.framework.retry.*` in `application.yaml`;
  reconciler exceptions propagate and the framework retries them.
- **Conversion webhook callback** — `GreetingConverter` (`ResourceConverter`)
  moves `spec.message` (v1) to `spec.text` (v2) and back, preserving identity.
- **Kubernetes Events** — `normal` events for render ("Rendered") and cleanup
  ("Cleaned").
- **Actuator health + Prometheus metrics** — same wiring as `echo-operator`.

The reconciliation flow is three phases:

1. **Deletion** — delete the external state ConfigMap `<name>-external` (it carries
   no owner reference, so garbage collection cannot remove it) and remove the
   finalizer.
2. **First sighting** — add the finalizer and `requeueNow` until the patch lands in
   the store.
3. **Steady state** — server-side-apply the owned `<name>-child` ConfigMap under
   the `greeting-operator` field manager, wait for it in the owned cache, sync the
   external state, persist the status subresource, and publish an event.

## Build

```bash
mvn -f operator/framework/pom.xml install
mvn -f example/greeting-operator/pom.xml clean verify
```

## Run

Uses your current kubeconfig; the controller watches the configured namespace:

```bash
mvn -f example/greeting-operator/pom.xml spring-boot:run
```

In another shell:

```bash
# install the CRD (replace the __NAMESPACE__ / __CA__ placeholders, or register
# conversion manually and point the CRD at the operator service)
kubectl apply -f example/greeting-operator/k8s/crd.yaml

kubectl create configmap fancy --namespace default --from-literal=prefix='» '
kubectl label configmap fancy greetings.example.com/primary=hello --namespace default

cat <<'EOF' | kubectl apply -f -
apiVersion: greetings.example.com/v1
kind: Greeting
metadata:
  name: hello
  namespace: default
spec:
  message: world
  style: fancy
EOF

kubectl get configmap hello-child -o jsonpath='{.data.message}'   # » world
kubectl get greeting hello -o jsonpath='{.status.phase}'          # Rendered

# the styles watch: editing the styles ConfigMap re-renders the child
kubectl patch configmap fancy --type merge -p '{"data":{"prefix":"★ "}}'

# conversion (v2 variables): read the object through v2
kubectl get greeting.v2.greetings.example.com hello -o jsonpath='{.spec.text}'

# cleanup: the finalizer deletes the external ConfigMap, GC removes the child
kubectl delete greeting hello
```

`k8s/rbac.yaml` grants the custom-resource, ConfigMap, and Event permissions; the
framework starter manages controller startup/shutdown and provides the fixed
webhook routes.

## Tests

- `GreetingReconcilerTest` — drives the reconciler through
  deletion/finalizer-add/steady-state with a mocked client, asserting the exact
  requests: the finalizer JSON patch, the server-side apply (field manager + owner
  reference), the status merge patch, the external ConfigMap lifecycle, requeue-now
  branches, and exception propagation to the framework retry policy.
- `GreetingSsaWireTest` — real mock server verifying the server-side-apply wire
  format (`application/apply-patch+yaml`, explicit field manager,
  `controller=true` owner reference), mirroring how the framework verifies its own
  helpers.
- `GreetingConverterTest` — the conversion callback in isolation: v1↔v2 field
  move, apiVersion rewrite, identity/status preservation, same-version passthrough.
- `GreetingOperatorApplicationTest` — Spring discovers all beans and applies the
  registration options (generation filter, 2-minute resync, owned ConfigMap,
  `styles` watch).

## Known limitation: fabric8 7.x custom-kind binding

Binding custom resources through the fabric8 Jackson deserializer is broken in
the pinned fabric8 7.3.0: `KubernetesResource` carries
`@JsonDeserialize(using = KubernetesDeserializer.class)`, and once the custom kind
resolves, `treeToValue(node, CustomClass)` re-enters the dispatcher and
**overflows the stack**. The framework's webhook transport uses this binding for
callback arguments, so **custom-kind admission and conversion webhooks currently
fail** (built-in kinds such as ConfigMap work — the framework's own webhook tests
cover them). The controller-side `Finalizers`/`StatusUpdates` helpers hit the same
path, so the greeting controller is verified at the request/unit level and has
**no automated in-memory runtime or cluster e2e** yet. A small repro:

```java
var json = "{\"apiVersion\":\"greetings.example.com/v1\",\"kind\":\"Greeting\",..."
Serialization.unmarshal(json, Greeting.class); // StackOverflowError
```

The module ships the `META-INF/services` registration that fabric8 7.x otherwise
requires for custom kinds, so a framework fabric8 upgrade or a non-dispatch binding
fix should make the example fully runnable end-to-end without further changes.
