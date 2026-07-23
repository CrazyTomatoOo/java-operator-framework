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
- `WEBHOOK_ENABLED` - enable webhook serving and registration, default `true`. When `false`, the operator stops serving admission and conversion webhooks, deletes any stale `ValidatingWebhookConfiguration` and `MutatingWebhookConfiguration` it owns, renders the CRD conversion strategy as `None`, and keeps the metrics and health endpoints running.
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
