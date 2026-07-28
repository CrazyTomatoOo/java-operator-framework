# Echo Operator

A sample Kubernetes operator built with `operator-framework`. It watches `EchoResource` custom resources and manages a Deployment and Service for each one. The example demonstrates finalizers, owner references, status updates, retry, metrics, health probes, and leader election.

## What it does

When you create an `EchoResource`:

- The operator adds a finalizer.
- It creates a Deployment and a Service with owner references pointing to the `EchoResource`.
- It updates `status.phase` to `READY` and copies `spec.message` to `status.message`.

When you delete the CR:

- The finalizer triggers cleanup logic.
- The finalizer is removed, allowing Kubernetes to garbage collect the Deployment and Service.

## Prerequisites

- Java 21
- Maven 3.9+
- Docker
- Helm 3
- kubectl with access to a cluster

## Build the project

```bash
mvn -f example/echo-operator/pom.xml clean package
```

## Run locally

The local run script reads the current kubectl namespace and starts the operator against your current kubeconfig:

```bash
example/echo-operator/scripts/local-run.sh
```

You can override settings with environment variables:

```bash
export OPERATOR_NAMESPACE=default
export METRICS_PORT=8080
export LEADER_ELECTION_ENABLED=false
export LEADER_ELECTION_NAMESPACE=default
export LEADER_ELECTION_LOCK_NAME=echo-operator-lock
example/echo-operator/scripts/local-run.sh
```

The operator exposes:

- `http://localhost:8080/healthz`
- `http://localhost:8080/readyz`
- `http://localhost:8080/metrics`

## Build the Docker image

```bash
example/echo-operator/scripts/build-image.sh
```

This packages the jar and runs:

```bash
docker build -t example/echo-operator:latest example/echo-operator
```

## Deploy with Helm

```bash
example/echo-operator/scripts/deploy.sh
```

The script builds the image, loads it into kind if the current context is `kind-*`, and installs the Helm chart.

To remove the deployment:

```bash
example/echo-operator/scripts/undeploy.sh
```

You can also render the chart locally:

```bash
helm template echo-operator example/echo-operator/helm/echo-operator
helm lint example/echo-operator/helm/echo-operator
```

## Example CR

See `example/echo-operator/examples/echo-cr.yaml`:

```yaml
apiVersion: example.com/v1alpha1
kind: EchoResource
metadata:
  name: my-echo
spec:
  message: "Hello from Echo Operator"
  replicas: 1
```

Apply it with:

```bash
kubectl apply -f example/echo-operator/examples/echo-cr.yaml
```

Then check the results:

```bash
kubectl get echoresources
kubectl get deployment my-echo
kubectl get service my-echo
```

## Webhooks

Echo Operator registers a validating webhook, a mutating webhook, and a conversion webhook on a shared TLS server. The webhook server listens on port 8443 by default and is exposed through the Helm Service on port 443.

### Validating webhook

`EchoValidatingWebhook` denies CRs that are invalid before they are persisted:

- missing `spec`
- blank `spec.message`
- `spec.message` longer than 140 characters
- negative `spec.replicas`

### Mutating webhook

`EchoMutatingWebhook` defaults CRs before they are persisted:

- adds the annotation `echo.example.com/defaulted: "true"`
- sets `spec.replicas` to 1 when it is missing or not positive

### Conversion webhook

Echo Operator supports two API versions of `EchoResource`:

- `example.com/v1alpha1` is deprecated and no longer the storage version.
- `example.com/v1alpha2` is the storage version and adds `spec.logLevel`.

`EchoConverter` converts between versions. When converting from `v1alpha1` to `v1alpha2`, `logLevel` defaults to `INFO`. When converting from `v1alpha2` to `v1alpha1`, `logLevel` is dropped.

The resource classes are annotated with fabric8 `@Version`:

```java
@Version(value = "v1alpha1", storage = false, served = true, deprecated = true)
public class EchoResourceV1 extends CustomResource<EchoSpecV1, EchoStatusV1> implements Namespaced {
}

@Version(value = "v1alpha2", storage = true, served = true)
public class EchoResourceV2 extends CustomResource<EchoSpecV2, EchoStatusV2> implements Namespaced {
}
```

## Environment variables

In addition to the variables listed in the local run section, Echo Operator recognizes:

- `WEBHOOK_PORT` - port for the TLS webhook server, default `8443`
- `CONTROLLER_ENABLED` - enable the reconciliation controller, default `true`. At least one of `CONTROLLER_ENABLED` or `WEBHOOK_ENABLED` must be `true`.
- `WEBHOOK_ENABLED` - enable webhook serving and registration, default `true`. When `false`, the operator stops serving admission and conversion webhooks, deletes any stale `ValidatingWebhookConfiguration` and `MutatingWebhookConfiguration` it owns, renders the CRD conversion strategy as `None`, and keeps the metrics and health endpoints running. **Only valid in combined mode.** Split mode requires `webhook.enabled=true` and will fail to render if set to `false`.
- `WEBHOOK_REGISTRATION_CLEANUP_ENABLED` - enable cleanup of stale runtime-owned admission webhook configurations, default `true`. When the Helm chart owns admission configurations (`createWebhookConfigurations=true`), the operator cleans up any leftover runtime-owned configurations on startup.
- `WEBHOOK_SELF_REGISTRATION_ENABLED` - enable runtime self-registration of admission webhook configurations, default `true`. When `false`, the operator does not create or update `ValidatingWebhookConfiguration` or `MutatingWebhookConfiguration` resources.
- `WEBHOOK_PREDECESSOR_VALIDATING_NAME` - name of a predecessor `ValidatingWebhookConfiguration` the operator must wait to be removed before registering its own, default unset. Used during Helm-to-runtime ownership transitions.
- `WEBHOOK_PREDECESSOR_MUTATING_NAME` - name of a predecessor `MutatingWebhookConfiguration` the operator must wait to be removed before registering its own, default unset. Used during Helm-to-runtime ownership transitions.
- `WEBHOOK_VALIDATING_ENABLED` - enable the validating admission webhook, default `true`. When `false`, the operator does not register the `ValidatingWebhookConfiguration` and deletes any stale one it owns. The HTTP endpoint stays registered as a safety net and returns a deny response.
- `WEBHOOK_MUTATING_ENABLED` - enable the mutating admission webhook, default `true`. When `false`, the operator does not register the `MutatingWebhookConfiguration` and deletes any stale one it owns. The HTTP endpoint stays registered as a safety net and returns a deny response.
- `WEBHOOK_CONVERSION_ENABLED` - enable the CRD conversion webhook, default `true`. When `false`, the operator does not patch the CRD conversion webhook client config and the conversion endpoint returns a failure response.
- `WEBHOOK_CERT_AUTO_GENERATE` - enable automatic webhook certificate generation, default `true`. When `true`, the operator generates a CA and server certificates on startup and persists the CA (private key and certificate) in the Kubernetes Secret named by `WEBHOOK_CERT_SECRET_NAME`. The CA survives pod restarts and Helm upgrades because it lives in the Secret, not on local disk. The private key is never written to the filesystem.
- `WEBHOOK_CERT_SECRET_NAME` - name of the Secret that holds the webhook CA when `WEBHOOK_CERT_AUTO_GENERATE=true`, default `echo-operator-webhook-ca`. The operator creates the Secret if it does not exist and reads the CA back from it on subsequent startups.
- `WEBHOOK_CERT_DIRECTORY` - directory to store generated certificates, default `/tmp/echo-operator/certs`
- `WEBHOOK_SERVICE_NAME` - name of the Kubernetes Service used by webhook self-registration, default `echo-operator`
- `WEBHOOK_SERVICE_NAMESPACE` - namespace of the webhook Service, defaults to the operator pod namespace (see `OPERATOR_POD_NAMESPACE`)
- `OPERATOR_POD_NAMESPACE` - namespace the operator pod runs in, defaults to the watched `OPERATOR_NAMESPACE`. Used as the default for `WEBHOOK_SERVICE_NAMESPACE` and for namespaced resource lookups.
- `WEBHOOK_CA_BUNDLE_PATH` - fallback path to the CA bundle used when `WEBHOOK_CERT_AUTO_GENERATE=false`; the sibling `tls.crt` and `tls.key` files are used for the server certificate and private key, default `/etc/echo-operator/certs/ca.crt`

For local development you can set them before running `local-run.sh`:

```bash
export WEBHOOK_PORT=8443
export WEBHOOK_ENABLED=true
export WEBHOOK_VALIDATING_ENABLED=true
export WEBHOOK_MUTATING_ENABLED=true
export WEBHOOK_CONVERSION_ENABLED=true
export WEBHOOK_CERT_AUTO_GENERATE=true
export WEBHOOK_CERT_SECRET_NAME=echo-operator-webhook-ca
export WEBHOOK_CERT_DIRECTORY=/tmp/echo-operator/certs
export WEBHOOK_SERVICE_NAME=echo-operator
# only used when WEBHOOK_CERT_AUTO_GENERATE=false
export WEBHOOK_CA_BUNDLE_PATH=/tmp/echo-operator/certs/ca.crt
```

## Helm webhook values

The chart supports these webhook-related values in `values.yaml`:

```yaml
webhook:
  port: 8443
  caBundle: ""                    # base64 CA bundle for CRD conversion / admission client configs
  path: /convert
  certAutoGenerate: true          # generate CA + server certs at startup; persists CA in certSecretName
  certSecretName: echo-operator-webhook-ca  # Secret holding the CA when certAutoGenerate is true
  createWebhookConfigurations: false   # set true to pre-create webhook configs via Helm (requires certAutoGenerate=false)
  failurePolicy: Fail
  timeoutSeconds: 10
  admissionName: echo.example.com
  tls:
    secretName: echo-operator-webhook-tls
  service:
    namespace: ""
    name: ""
    port: 443
```

When `certAutoGenerate` is `true` (the default), the operator generates a CA and server certificates on startup and persists the CA in the Secret named by `certSecretName`. The CA survives pod restarts and Helm upgrades because it lives in the Secret, not on local disk.

When `createWebhookConfigurations` is `true`, the Helm chart pre-creates the `ValidatingWebhookConfiguration` and `MutatingWebhookConfiguration` resources. This mode only works with `certAutoGenerate=false` because Helm needs the CA bundle at install time, and auto-generated CAs are not available until the operator starts.

When `createWebhookConfigurations` is `false` (the default), the operator self-registers its admission webhook configurations on startup.

## Deployment modes

The Helm chart supports two deployment modes controlled by `deploymentMode` in `values.yaml`.

### Combined mode (default)

When `deploymentMode: combined` (the default), the chart renders a single Deployment, a single Service, and a single ServiceAccount. The combined workload runs both the reconciliation controller and the webhook server in one process. This is the simplest deployment path and the default for new installations.

```yaml
deploymentMode: combined  # default
webhook:
  enabled: true           # default
```

Setting `webhook.enabled=false` is only valid in combined mode. It produces a controller-only Deployment that exposes only the metrics port, renders the CRD conversion strategy as `None`, and does not create admission configurations.

### Split mode (opt-in)

When `deploymentMode: split`, the chart renders two Deployments, two Services, and two ServiceAccounts:

| Resource | Controller | Webhook |
|----------|-----------|---------|
| Deployment | `<fullname>-controller` | `<fullname>-webhook` |
| Service | `<fullname>-controller` | `<fullname>` (webhook Service) |
| ServiceAccount | `<fullname>-controller` | `<fullname>-webhook` |

The controller Deployment runs with `CONTROLLER_ENABLED=true` and `WEBHOOK_ENABLED=false`. The webhook Deployment runs with `CONTROLLER_ENABLED=false` and `WEBHOOK_ENABLED=true`. Both use the same container image.

**Split mode requires `webhook.enabled=true`.** Setting `deploymentMode: split` with `webhook.enabled=false` will fail Helm rendering.

#### Deployment examples

Deploy in combined mode (default):

```bash
helm install echo-operator ./helm/echo-operator \
  --set deploymentMode=combined
```

Deploy in split mode:

```bash
helm install echo-operator ./helm/echo-operator \
  --set deploymentMode=split
```

Or use the deploy script:

```bash
# Combined mode
DEPLOYMENT_MODE=combined ./scripts/deploy.sh

# Split mode
DEPLOYMENT_MODE=split ./scripts/deploy.sh
```

### Nested workload values

Split-mode workloads do not inherit the top-level combined workload values. Each split workload has its own nested block:

```yaml
controller:
  workload:
    replicas: 1
    resources:
      limits: { cpu: 500m, memory: 512Mi }
      requests: { cpu: 100m, memory: 128Mi }
    podAnnotations: {}
    podLabels: {}
    nodeSelector: {}
    tolerations: []
    affinity: {}
    serviceAccount:
      create: true
      name: ""

webhook:
  workload:
    replicas: 1
    resources:
      limits: { cpu: 500m, memory: 512Mi }
      requests: { cpu: 100m, memory: 128Mi }
    podAnnotations: {}
    podLabels: {}
    nodeSelector: {}
    tolerations: []
    affinity: {}
    serviceAccount:
      create: true
      name: ""
```

Top-level values (`replicas`, `resources`, `podAnnotations`, `podLabels`, `nodeSelector`, `tolerations`, `affinity`, `serviceAccount.name`) apply only to the combined Deployment. Split workloads read exclusively from their nested `controller.workload.*` and `webhook.workload.*` blocks.

### Independent RBAC and watched namespace

In split mode, the controller and webhook ServiceAccounts have separate RBAC bindings:

- **Controller ServiceAccount**: gets the controller Role (EchoResource CRUD, pods/services/deployments/events) and, when `leaderElection.enabled=true`, the lease Role in the release namespace. It does not get admission registration or CRD patch permissions.
- **Webhook ServiceAccount**: gets the webhook ClusterRole (admission registration, CRD patch, barrier reads) and, when `webhook.certAutoGenerate=true`, the CA Secret read Role. It does not get EchoResource or workload management permissions.

The controller Role is scoped to `operator.namespace` (the watched namespace). When `operator.namespace` differs from the release namespace and `leaderElection.enabled=true`, the lease Role is created in the release namespace while the controller Role targets the watched namespace.

## Certificate modes

### Auto-generated (default)

When `webhook.certAutoGenerate=true` (the default), the operator generates a CA and server certificates on startup. The CA private key and certificate are persisted in the Kubernetes Secret named by `webhook.certSecretName` (default `echo-operator-webhook-ca`). The CA survives pod restarts and Helm upgrades because it lives in the Secret, not on local disk. The private key is never written to the filesystem.

### External TLS

When `webhook.certAutoGenerate=false`, you must provide:

1. A TLS Secret (named by `webhook.tls.secretName`) containing `ca.crt`, `tls.crt`, and `tls.key`, created externally by a cluster admin or external tooling.
2. A literal PEM CA bundle in `webhook.caBundle`. Helm base64-encodes it once and embeds it in the CRD conversion client config and any Helm-owned admission configurations.

The operator reads the CA bundle and sibling `tls.crt`/`tls.key` from the mounted Secret at `WEBHOOK_CA_BUNDLE_PATH` (default `/etc/echo-operator/certs/ca.crt`).

### caBundle literal PEM

The `webhook.caBundle` value must be a literal PEM certificate (with `-----BEGIN CERTIFICATE-----` / `-----END CERTIFICATE-----` envelope and valid base64 DER body). Helm validates the PEM body at render time and rejects envelope-only or non-certificate data.

## Admission ownership

Admission configurations (`ValidatingWebhookConfiguration` and `MutatingWebhookConfiguration`) are owned by exactly one authority at any time.

### Runtime-owned (default)

When `webhook.createWebhookConfigurations=false` (the default), the operator self-registers its admission configurations at startup. The runtime-owned configurations are named `echo-operator.<watched-namespace>.echo.example.com`. The operator creates, updates, and deletes them.

The Helm chart injects predecessor barrier env vars so the runtime can wait for any leftover Helm-owned configurations to be removed before registering its own:

```
WEBHOOK_PREDECESSOR_VALIDATING_NAME=<fullname>-validating
WEBHOOK_PREDECESSOR_MUTATING_NAME=<fullname>-mutating
```

### Helm-owned

When `webhook.createWebhookConfigurations=true` (requires `webhook.certAutoGenerate=false`), the Helm chart creates the admission configurations directly. The Helm-owned configurations are named `<fullname>-validating` and `<fullname>-mutating`. The operator does not self-register but cleans up any leftover runtime-owned configurations on startup (`WEBHOOK_REGISTRATION_CLEANUP_ENABLED=true`, `WEBHOOK_SELF_REGISTRATION_ENABLED=false`).

### Ownership transitions

Transitions between runtime-owned and Helm-owned are bidirectional:

- **Helm-to-runtime**: Set `createWebhookConfigurations=false`. The new runtime Deployment waits for the held Helm configurations to be deleted (predecessor barrier), then registers its own. During the transition, the new webhook pod reports `503` on `/readyz` until the predecessor is gone.
- **Runtime-to-Helm**: Set `createWebhookConfigurations=true` with `certAutoGenerate=false` and a valid `caBundle`. The Helm chart creates its configurations and the runtime cleans up its own on next startup.

## Verification commands

```bash
# Lint the chart
helm lint example/echo-operator/helm/echo-operator

# Run the Helm contract test (static, no cluster required)
example/echo-operator/scripts/helm-contract-test.sh

# Run the docs contract test (static, no cluster required)
example/echo-operator/scripts/docs-contract-test.sh

# Combined-mode smoke test (requires a Kubernetes cluster)
DEPLOYMENT_MODE=combined example/echo-operator/scripts/smoke-test.sh

# Split-mode smoke test (requires a Kubernetes cluster)
DEPLOYMENT_MODE=split example/echo-operator/scripts/smoke-test.sh

# Maven regression
mvn -f example/echo-operator/pom.xml clean verify
```

## Exclusions

The chart and operator do not provide:

- **cert-manager integration**: certificate management is handled by the operator's built-in auto-generation or by externally provisioned TLS Secrets.
- **CA rotation**: the auto-generated CA is persistent (stored in a Secret) but is not automatically rotated. Rotate by deleting the Secret and restarting the operator.
- **HA/PDB/HPA**: high-availability, PodDisruptionBudget, and HorizontalPodAutoscaler configurations are out of scope for this chart.
- **Framework-level deployment mode API**: `deploymentMode` is a Helm chart value, not a framework-level API. The operator framework itself has no concept of combined vs. split mode.
- **Second image or JAR**: both combined and split modes use the same container image. Split mode differentiates behavior through runtime env vars (`CONTROLLER_ENABLED`, `WEBHOOK_ENABLED`), not through separate artifacts.

## Example CR for v1alpha2

You can also create a `v1alpha2` CR:

```yaml
apiVersion: example.com/v1alpha2
kind: EchoResource
metadata:
  name: my-echo-v2
spec:
  message: "Hello from v1alpha2"
  replicas: 2
  logLevel: DEBUG
```

Save it as `echo-v2.yaml` and apply it:

```bash
kubectl apply -f echo-v2.yaml
```

Because `v1alpha2` is the storage version, reading the resource back will show `apiVersion: example.com/v1alpha2` and `spec.logLevel`.
