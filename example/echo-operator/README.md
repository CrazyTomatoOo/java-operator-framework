# Echo Operator (Spring Boot example)

Minimal example of the `operator-framework-spring-boot-starter`: a plain Spring Boot app,
no framework bootstrap code.

## What it does

- Watches `ConfigMap`s labeled `echo.example.com/enabled=true` in the configured namespace.
- Reconciles each into an owned `<name>-echo` ConfigMap containing `data.message` uppercased;
  the child is garbage-collected when its owner is deleted.
- Publishes a best-effort Kubernetes Event when the child changes.
- Exposes admission webhooks:
  - `POST /operator-framework/webhooks/mutate/echomutator` — defaults `data.message` to `hello world`.
  - `POST /operator-framework/webhooks/validate/echovalidator` — denies echo ConfigMaps with a blank message.

Everything is auto-configured: the `Reconciler` bean is registered through an explicit
`ControllerRegistration` (adds the owned-ConfigMap secondary watch), webhook beans are
discovered by type, and metrics/health come from Actuator (`/actuator/health`,
`/actuator/prometheus`).

## Build

The example depends on the framework SNAPSHOT, so install it first:

```bash
mvn -f operator/framework/pom.xml install
mvn -f example/echo-operator/pom.xml clean verify
```

## Run

Uses your current kubeconfig (`~/.kube/config`):

```bash
mvn -f example/echo-operator/pom.xml spring-boot:run
```

Try it:

```bash
kubectl create configmap greeting \
  --from-literal=message=hello \
  --dry-run=client -o yaml | kubectl label --local -f - echo.example.com/enabled=true -o yaml | kubectl apply -f -
kubectl get configmap greeting-echo -o jsonpath='{.data.message}'   # HELLO
kubectl delete configmap greeting                                    # child is GC'd
```

## End-to-end test

Prerequisite: Docker plus a current kubectl context whose cluster runs locally-built
images (Docker Desktop works out of the box).

```bash
example/echo-operator/scripts/e2e-test.sh
```

The script builds the image, deploys to a throwaway `echo-e2e` namespace with RBAC and TLS,
registers real `Mutating/ValidatingWebhookConfiguration`s, and verifies: mutation defaulting
and validation denial enforced by the API server, reconcile of the owned child ConfigMap,
Kubernetes Event publication, update propagation, owner-reference garbage collection, and
actuator health/Prometheus metrics. The namespace and webhook configurations are cleaned up
on exit.

## Notes


- Tests: `EchoOperatorTest` covers reconciler/webhook logic with a mock API server, and
  `EchoWebhookEndpointTest` exercises the real admission endpoints over MockMvc. Leader
  election is framework behavior (covered by framework tests) and stays off here since the
  example runs as a single replica; enable it for multi-replica deployments:
  ```yaml
  operator:
    framework:
      leader-election:
        enabled: true   # Lease in the controller namespace, named echo-operator-leader
  ```

- Webhooks run on the app's HTTP(S) port; for real cluster admission registration configure
  `server.ssl.*` and point a `(Mutating|Validating)WebhookConfiguration` at the paths above.
- `operator.framework.*` properties (namespace, resync, retry, rate limit, leader election,
  events) are documented in the framework README and IDE auto-completion metadata.
