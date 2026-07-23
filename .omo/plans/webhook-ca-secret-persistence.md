# webhook-ca-secret-persistence - Work Plan

## TL;DR (For humans)

- **What you'll get**: `WEBHOOK_CERT_AUTO_GENERATE=true` will persist the generated webhook CA into a Kubernetes Secret (`echo-operator-webhook-ca`) in the operator pod namespace instead of discarding it. On restart the operator reads the Secret, reuses the CA, regenerates a fresh local server certificate, and writes `ca.crt`/`tls.crt`/`tls.key` to the local cert directory. Both admission and conversion webhooks keep working after restarts because the CA (and therefore the CRD `caBundle`) stays stable. The CA private key is kept only in the Secret and in memory; it is **not** written to the pod's local cert directory.
- **Why this approach**: It keeps the existing `WebhookServer`/`CertWatcher` file-based design untouched, requires no cert-manager, and fixes the restart-caBundle mismatch with minimal framework changes.
- **What it will NOT do**: It does not add mTLS/client authentication, automatic CA rotation, or support for using an external CA together with auto-generate. The `WEBHOOK_CERT_AUTO_GENERATE=false` path stays exactly as-is.
- **Effort**: 7 waves, ~17 todos; touches the framework cert package, WebhookSelfRegistration, EchoOperatorMain/OperatorConfig, Helm templates/values, and tests.
- **Risk**: The CA private key lives in an in-cluster Secret, so RBAC must be tight. The webhook Service name/namespace used in certificate SANs and webhook client configs must match the Helm-rendered Service. Helm upgrades must not wipe the runtime-patched CRD `caBundle`, and disabled-webhook upgrades must remove stale self-registered admission webhooks.
- **Decisions made**: Secret name `echo-operator-webhook-ca`; Secret lives in the operator pod namespace from `OPERATOR_POD_NAMESPACE`; Secret holds only `ca.crt`/`ca.key`; server cert is local per pod; CA private key is not written to local disk; cert directory `/tmp/echo-operator/certs` mounted as `emptyDir` with `fsGroup: 1001`; Secret permissions added to the namespace-scoped `Role` only when webhooks and auto-generate are enabled; CRD conversion `caBundle` and service fields are patched at runtime and `caBundle` is preserved across Helm upgrades via `lookup`; webhook Service name/namespace are configurable via `WEBHOOK_SERVICE_NAME`/`WEBHOOK_SERVICE_NAMESPACE` and used consistently for certs, admission, and conversion; self-registered admission webhook resource names keep a stable base name; webhook setup can be disabled via `WEBHOOK_ENABLED`, in which case CRD conversion strategy is `None` and stale self-registered admission webhooks are deleted; metrics/health server is independent of webhook enablement.

## Scope

### IN scope
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/GeneratedCertificate.java` — add CA private key fields.
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateGenerator.java` — populate CA private key and support generating a server cert from an existing CA.
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateSecretManager.java` — new class: read Secret, generate if missing, create Secret, write local PEMs (without CA private key).
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/PemCertificateUtils.java` — new public PEM read helpers for both `Path` and in-memory `byte[]`/`String`.
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/ReloadableSslContext.java` — delegate PEM reading to `PemCertificateUtils`.
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/registration/WebhookSelfRegistration.java` — add CRD conversion webhook clientConfig patching with conflict retry and admission webhook cleanup helpers.
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java` — add `WEBHOOK_ENABLED`, `WEBHOOK_CERT_SECRET_NAME`, `WEBHOOK_SERVICE_NAME`, `WEBHOOK_SERVICE_NAMESPACE`, and `OPERATOR_POD_NAMESPACE` configs; wire secret manager; use configured service name/namespace for webhook client configs while keeping a stable registration base name; call CRD clientConfig patch; guard `start()`/`stop()` webhook-only components when webhooks disabled; clean up stale admission webhooks when disabled; keep metrics/health independent.
- `example/echo-operator/src/main/resources/application.properties` — document new keys.
- `example/echo-operator/helm/echo-operator/values.yaml` — add `webhook.certAutoGenerate` and `webhook.certSecretName`; remove or deprecate `webhook.service.namespace` override.
- `example/echo-operator/helm/echo-operator/templates/deployment.yaml` — mount `emptyDir` for cert directory, add `fsGroup`, and pass new env vars when webhooks and auto-generate are enabled.
- `example/echo-operator/helm/echo-operator/templates/role.yaml` / `clusterrole.yaml` / `clusterrolebinding.yaml` — add `secrets` verbs only when webhooks and auto-generate are enabled, and add disabled-mode admission webhook cleanup RBAC.
- `example/echo-operator/helm/echo-operator/templates/crd.yaml` — render `conversion.strategy: None` when webhooks disabled; preserve existing conversion `caBundle` via Helm `lookup` when webhooks and auto-generate are enabled; otherwise use supplied value.
- `example/echo-operator/helm/echo-operator/templates/validatingwebhookconfiguration.yaml` and `mutatingwebhookconfiguration.yaml` — prevent Helm-managed admission webhook configs from rendering with auto-generated certs and an empty/mismatched static `caBundle`.
- `example/echo-operator/helm/echo-operator/templates/service.yaml` — respect `.Values.webhook.service.name` override; namespace is always `.Release.Namespace`.
- Unit and integration tests for the new behavior, including a testable overload for config loading.
- README updates for the new env vars and the persistent-CA behavior.

### OUT of scope
- Any change to `WEBHOOK_CERT_AUTO_GENERATE=false` or the pre-created TLS Secret path.
- CA rotation/renewal beyond the existing 365-day validity.
- Webhook client authentication or network policy.
- ClusterRole changes for Secrets (Secrets are namespaced).
- Support for encrypted private keys or non-RSA keys in the auto-generate Secret.
- Writing the CA private key to the local cert directory.
- Cross-namespace webhook Service deployment (namespace override is constrained to release namespace).

## Verification strategy

- **Unit tests** for `GeneratedCertificate`, `PemCertificateUtils`, `WebhookCertificateSecretManager`, and `WebhookSelfRegistration` using fabric8's mock server.
- **Integration test** for `EchoOperatorMain` startup path verifying webhook enablement guard, secret manager wiring, configured service name/namespace usage, stable admission registration naming, disabled-mode cleanup, and CRD clientConfig patching.
- **Helm verification**: `helm lint` and `helm template` for `webhook.enabled=true/false`, `webhook.certAutoGenerate=true/false`, and non-default `fullnameOverride`.
- **Maven verification**: `mvn -f operator/framework/pom.xml install` then `mvn -f example/echo-operator/pom.xml test` must pass.
- **Manual QA** (mandatory for upgrade preservation): deploy with auto-generate, run `helm upgrade`, restart the pod, apply a CR on each API version, and confirm conversion still succeeds.

## Execution strategy

- **Wave 1**: Extend the certificate data model so the CA private key can be carried and persisted.
- **Wave 2**: Add generator API to sign a server certificate from an existing CA.
- **Wave 3**: Extract reusable PEM reading utilities (Path + in-memory) so the new Secret manager can decode existing PEMs without writing CA keys to disk.
- **Wave 4**: Build the Secret manager that persists only the CA and generates local server certs.
- **Wave 5**: Add CRD conversion clientConfig patching and disabled-mode admission cleanup helpers to `WebhookSelfRegistration`.
- **Wire Wave**: Update EchoOperatorMain/OperatorConfig (webhook enablement, pod namespace, service name/namespace, secret name) and call the new CRD patch/cleanup helpers.
- **Wave 6**: Update Helm RBAC, deployment, CRD, and Service templates.
- **Wave 7**: Tests, docs, and final verification.

Each wave ends with a green test/verification step before the next wave starts.

## Todos

### T1: Extend `GeneratedCertificate` with CA private key
- [x] 1. Task
**References**
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/GeneratedCertificate.java:23-25`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateGenerator.java:117-141`

**Acceptance**
- `GeneratedCertificate` record includes `caPrivateKey` (`java.security.PrivateKey`) and `caPrivateKeyPem` (`byte[]`).
- The compact canonical constructor validates the new fields are non-null.
- `caPrivateKeyPem()` returns a defensive copy.
- `WebhookCertificateGenerator.generateCertificate` populates the new fields.
- Existing `caBundleBase64()` continues to use `caCertificatePem`.
- The CA private key is **not** written to the local cert directory.

**Happy-path QA**
- `WebhookCertificateGenerator.generate()` returns a `GeneratedCertificate` whose `caPrivateKeyPem` is a non-empty PEM block and whose `caPrivateKey` matches the public key in `caCertificate`.
- Evidence: new or updated unit test in `WebhookCertificateGeneratorTest` passes.

**Failure-path QA**
- If `GeneratedCertificate` is constructed with a null `caPrivateKey`, `NullPointerException` is thrown with a clear message.
- Evidence: unit test asserting the NPE passes.

**Blocked by**: none
**Commit**: `feat(webhook): carry CA private key in GeneratedCertificate`

---

### T2: Add server certificate generation from an existing CA
- [x] 2. Task
**References**
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateGenerator.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/GeneratedCertificate.java`

**Acceptance**
- Add `WebhookCertificateGenerator.generateServerCertificate(X509Certificate caCertificate, PrivateKey caPrivateKey)` that creates a new RSA key pair and signs a server certificate with the provided CA.
- The generated server certificate has the same SANs, EKU `serverAuth`, and validity as a freshly generated one.
- Returns a `GeneratedCertificate` whose `caCertificate`/`caPrivateKey` are the supplied CA and whose `serverCertificate`/`serverPrivateKey` are new.

**Happy-path QA**
- A server cert generated from an existing CA verifies against that CA and has `serverAuth` EKU.
- Evidence: new unit test passes.

**Failure-path QA**
- Passing a CA cert whose public key does not match the supplied CA private key throws `GeneralSecurityException`.
- Evidence: unit test passes.

**Blocked by**: T1
**Commit**: `feat(webhook): generate server cert from persisted CA`

---

### T3: Extract PEM reading utilities (Path and in-memory)
- [x] 3. Task
**References**
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/ReloadableSslContext.java:126-145` (existing private read methods)
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/PemCertificateUtils.java` (new file)

**Acceptance**
- Create `PemCertificateUtils` in `...webhook.cert` with public static methods:
  - `List<X509Certificate> readCertificates(Path)` and `List<X509Certificate> readCertificates(byte[])`
  - `PrivateKey readPrivateKey(Path)` and `PrivateKey readPrivateKey(byte[])` supporting both PKCS#8 (`-----BEGIN PRIVATE KEY-----`) and PKCS#1 RSA (`-----BEGIN RSA PRIVATE KEY-----`) PEM.
- Explicitly reject encrypted private keys (PEM with `ENCRYPTED` or `Proc-Type`/DEK-Info headers) by throwing `IOException`.
- `ReloadableSslContext` delegates file-based reading to `PemCertificateUtils` and its behavior is unchanged.
- Existing tests for `ReloadableSslContext`/`TlsCertReloadTest` still pass.

**Happy-path QA**
- `PemCertificateUtils.readCertificates(byte[])` and `readPrivateKey(byte[])` decode PEM bytes produced by `WebhookCertificateGenerator` without touching disk.
- Evidence: new unit test passes.

**Failure-path QA**
- Passing an encrypted private key byte array throws `IOException` with a clear message.
- Passing invalid base64 throws `IOException`.
- Evidence: unit tests pass.

**Blocked by**: T1
**Commit**: `refactor(webhook): extract reusable PEM certificate utilities`

---

### T4: Implement `WebhookCertificateSecretManager`
- [x] 4. Task
**References**
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateSecretManager.java` (new file)
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateGenerator.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/GeneratedCertificate.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/PemCertificateUtils.java`
- `io.fabric8.kubernetes.client.KubernetesClient` and `io.fabric8.kubernetes.client.KubernetesClientException`

**Acceptance**
- Public class `WebhookCertificateSecretManager` with constructor taking `KubernetesClient`, `secretName`, `secretNamespace`, `serviceName`, `serviceNamespace`, and `certDirectory`.
- Method `GeneratedCertificate resolve()`:
  1. Reads Secret `secretName` in `secretNamespace` via `client.secrets().inNamespace(secretNamespace).withName(secretName).get()`.
  2. **If found**: decodes `ca.crt` and `ca.key` in memory using `PemCertificateUtils` byte-array methods, validates that the CA private key matches the CA certificate, generates a fresh server cert from that CA (T2 API), and writes `ca.crt`, `tls.crt`, `tls.key` atomically to `certDirectory`. The Secret is **not** updated. The CA private key is **not** written locally.
  3. **If not found**: generates fresh CA + server cert, writes temp files (`ca.crt`, `tls.crt`, `tls.key`) to `certDirectory` to verify writability, creates the Secret via `client.secrets().inNamespace(secretNamespace).resource(secret).create()` with data keys `ca.crt` and `ca.key` (base64-encoded PEM), and atomically moves the temp files into place. The CA private key PEM is taken from `GeneratedCertificate.caPrivateKeyPem` for the Secret; it is **not** written to `certDirectory`.
  4. **Concurrency**: if `create()` throws `KubernetesClientException` with `getCode() == 409`, re-read the Secret and use the CA from it. No other Secret updates occur, so update conflicts are impossible.
- Secret metadata labels include `app.kubernetes.io/managed-by: operator-framework`.
- Secret type is `Opaque`.

**Happy-path QA**
- First call creates the Secret with `ca.crt`/`ca.key` and writes local PEMs; second call reads the same Secret, reuses the CA, and generates a new local server cert. Local cert directory never contains `ca.key`.
- Evidence: unit test with fabric8 mock server passes; asserts Secret data keys are `ca.crt` and `ca.key`, CA cert unchanged between calls, local `tls.crt` differs, and `ca.key` is absent from cert directory.

**Failure-path QA**
- If the cert directory is not writable, `resolve()` throws `IOException` and does not create a Secret.
- If the Secret contains malformed base64 or mismatched CA cert/key, `resolve()` throws a clear exception.
- If `create()` returns `KubernetesClientException` with code other than 409, the exception propagates.
- Evidence: unit tests pass.

**Blocked by**: T2, T3
**Commit**: `feat(webhook): add WebhookCertificateSecretManager for CA persistence`

---

### T5: Unit-test the Secret manager end-to-end
- [x] 5. Task
**References**
- `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateSecretManagerTest.java` (new file)
- Existing test patterns in `operator/framework/src/test/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/`

**Acceptance**
- Test coverage:
  - Missing Secret → generation + Secret creation with only `ca.crt`/`ca.key`.
  - Existing Secret → CA reused, local server cert regenerated, Secret untouched.
  - Concurrent `resolve()` calls → one stable CA, no split-brain (simulate via mock server returning 409 on one create).
  - Secret with invalid base64 → clear exception.
  - Secret with mismatched CA cert/key → clear exception.
  - Local file permissions are `600` for `tls.key` and `644` for `ca.crt`/`tls.crt`.
  - `ca.key` is never present in the local cert directory.
- All new tests pass.

**Happy-path QA**
- `mvn -f operator/framework/pom.xml test` passes including the new test class.
- Evidence: Maven surefire output shows `WebhookCertificateSecretManagerTest` green.

**Failure-path QA**
- If fabric8 mock server is unavailable and tests are stub-only, the stub tests still exercise encode/decode and file writing.
- Evidence: test command passes.

**Blocked by**: T4
**Commit**: `test(webhook): cover WebhookCertificateSecretManager`

---

### T6: Add CRD conversion clientConfig patching and disabled-mode webhook cleanup to `WebhookSelfRegistration`
- [x] 6. Task
**References**
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/registration/WebhookSelfRegistration.java`
- `example/echo-operator/helm/echo-operator/templates/crd.yaml:14-24`
- `example/echo-operator/helm/echo-operator/templates/clusterrole.yaml:13-16` (existing CRD permissions)

**Acceptance**
- Add `WebhookSelfRegistration.patchConversionWebhookClientConfig(String crdName, Path caBundlePath, String serviceName, String serviceNamespace, int servicePort)` that:
  1. Reads `ca.crt` from `caBundlePath` and base64-encodes it.
  2. Fetches the CRD via `client.apiextensions().v1().customResourceDefinitions().withName(crdName).get()`.
  3. If found and `spec.conversion.strategy == "Webhook"`, sets:
     - `spec.conversion.webhook.clientConfig.caBundle` to the base64 string.
     - `spec.conversion.webhook.clientConfig.service.name` to `serviceName`.
     - `spec.conversion.webhook.clientConfig.service.namespace` to `serviceNamespace`.
     - `spec.conversion.webhook.clientConfig.service.port` to `servicePort`.
  4. Updates via `client.apiextensions().v1().customResourceDefinitions().resource(crd).update()`.
  5. If update throws `KubernetesClientException` with `getCode() == 409`, re-read the CRD and retry the update at most 3 times. If a re-read shows the desired `caBundle` and service fields are already set, return successfully.
  6. Throws `IllegalStateException` if the CRD is missing or not configured for webhook conversion.
- Add `WebhookSelfRegistration.unregisterAdmissionWebhooks(KubernetesClient client, String baseName, Collection<String> validatorNames, Collection<String> mutatorNames)` or an equivalent instance method that deletes `ValidatingWebhookConfiguration`/`MutatingWebhookConfiguration` resources named `baseName + "." + normalize(name)`, ignores 404, and propagates non-404 Kubernetes API errors.

**Happy-path QA**
- A mock-server test creates a CRD with webhook conversion, calls `patchConversionWebhookClientConfig`, and asserts `caBundle`, `service.name`, `service.namespace`, and `service.port` are set correctly.
- A mock-server test creates stale validating/mutating webhook configurations, calls the unregister helper with `WEBHOOK_NAME`, and asserts both resources are deleted.
- Evidence: new unit test passes.

**Failure-path QA**
- A mock-server test returns 409 on the first update, then succeeds on retry; asserts the CRD ends with the correct `caBundle` and service fields.
- If the CRD does not exist, `patchConversionWebhookClientConfig` throws `IllegalStateException`.
- If unregister receives a non-404 Kubernetes API error, it propagates the exception.
- Evidence: unit tests pass.

**Blocked by**: T1
**Commit**: `feat(webhook): patch CRD conversion webhook clientConfig at runtime`

---

### T7: Add webhook config, pod namespace config, strict validation, and a testable overload
- [x] 7. Task
**References**
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java:354-357` (`OperatorConfig` record)
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java:257-300` (`loadConfig`)
- `example/echo-operator/src/main/resources/application.properties:25-34`

**Acceptance**
- `OperatorConfig` record gains `boolean webhookEnabled`, `boolean webhookCertAutoGenerate`, `String webhookCertSecretName`, `String webhookServiceName`, `String webhookServiceNamespace`, `String operatorNamespace` (watched namespace), and `String operatorPodNamespace` (namespace where the operator pod and namespaced Role/Secret live).
- Replace the hardcoded `WEBHOOK_SERVICE_NAME` constant with `DEFAULT_WEBHOOK_SERVICE_NAME = "echo-operator"`.
- Add a private `resolveRequiredConfig(String envVar, Map<String,String> env, Properties defaults, String propertyKey, String fallback)` helper that:
  - Returns the env var value if it is **set and non-blank**.
  - Throws `IllegalArgumentException` if the env var is **set and blank**.
  - Otherwise falls back to the property file value if it is **non-blank**.
  - Throws `IllegalArgumentException` if the property file value is **present-but-blank**.
  - Otherwise returns the hard-coded `fallback`.
- Add a private `resolveBooleanConfig(String envVar, Map<String,String> env, Properties defaults, String propertyKey, boolean fallback)` helper that accepts only `"true"` or `"false"` (case-insensitive, trimmed) from env or property file, and throws `IllegalArgumentException` for any other non-blank value.
- Add a package-private overload `static OperatorConfig loadConfig(Map<String,String> env, Properties defaults)` that delegates to `resolveRequiredConfig` and `resolveBooleanConfig`, with the existing `loadConfig()` delegating to `loadConfig(System.getenv(), loadApplicationProperties())`.
- `loadConfig` uses `resolveBooleanConfig` for `WEBHOOK_ENABLED` / `webhook.enabled` with default `true`.
- `loadConfig` uses `resolveBooleanConfig` for `WEBHOOK_CERT_AUTO_GENERATE` / `webhook.cert.auto-generate` with default `true`.
- `loadConfig` uses `resolveRequiredConfig` for:
  - `OPERATOR_POD_NAMESPACE` / `operator.pod-namespace` with default equal to the resolved `operatorNamespace` for local runs.
  - `WEBHOOK_CERT_SECRET_NAME` / `webhook.cert.secret-name` with default `echo-operator-webhook-ca`.
  - `WEBHOOK_SERVICE_NAME` / `webhook.service.name` with default `echo-operator`.
  - `WEBHOOK_SERVICE_NAMESPACE` / `webhook.service.namespace` with default equal to the resolved `operatorPodNamespace`.
- Helm must set `OPERATOR_POD_NAMESPACE` from `metadata.namespace` via the downward API so the CA Secret namespace follows the release/pod namespace even when `.Values.operator.namespace` watches a different namespace.
- `application.properties` documents the new keys.

**Happy-path QA**
- `EchoOperatorMain.loadConfig(emptyEnv, emptyProperties)` returns defaults including `webhookEnabled=true`, `webhookCertAutoGenerate=true`, `operatorNamespace=default`, `operatorPodNamespace=default`, and `webhookServiceNamespace=default`.
- Evidence: new unit test passes.

**Failure-path QA**
- `loadConfig(mapWithBlankCertSecretName, emptyProperties)` throws `IllegalArgumentException`.
- `loadConfig(mapWithBlankWebhookEnabled, emptyProperties)` throws `IllegalArgumentException`.
- `loadConfig(mapWithInvalidBooleanWebhookEnabled, emptyProperties)` throws `IllegalArgumentException`.
- `loadConfig(emptyEnv, propertiesWithBlankCertSecretName)` throws `IllegalArgumentException`.
- `loadConfig(envWithOperatorNamespaceFooAndPodNamespaceBar, emptyProperties)` returns `operatorNamespace=foo`, `operatorPodNamespace=bar`, and default `webhookServiceNamespace=bar`.
- Evidence: unit tests pass.

**Blocked by**: none
**Commit**: `feat(echo-operator): add webhook config with testable overload`

---

### T8: Wire Secret manager and CRD patch into EchoOperatorMain using configured service name/namespace and enablement guards
- [x] 8. Task
**References**
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java:117-125` (`WebhookRegistrationConfig` builder)
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java:153-173` (`resolveWebhookCertificatePaths`, `generateWebhookCertificate`)
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java:192-255` (`start`, `stop`)
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateSecretManager.java`
- `operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/registration/WebhookSelfRegistration.java`

**Acceptance**
- In `create()`, if `!config.webhookEnabled()`, skip creation of `admissionHandler`, `conversionHandler`, `webhookServer`, `webhookSelfRegistration`, and the `resolveWebhookCertificatePaths` call. Register only the operator/reconciler. The `webhookServer`, `admissionHandler`, `conversionHandler`, and `webhookSelfRegistration` fields may be `null` in this mode.
- Add a stable admission registration base name helper, e.g. `registrationBaseName(config) = DEFAULT_WEBHOOK_SERVICE_NAME + "." + config.operatorNamespace()`, matching the pre-change default naming scheme and not depending on overridden Service name/namespace.
- In `start()`, if `!config.webhookEnabled()`, skip starting the webhook server, admission registration, and CRD patch; **always** start the metrics/health server and the operator; before starting the operator, delete stale self-registered validating/mutating webhook configurations for `WEBHOOK_NAME` using the stable registration base name, ignoring 404 and propagating non-404 Kubernetes API failures.
- In `stop()`, guard each nullable field (`webhookServer`, `metricsHealthServer`, `operator`, `client`) with null checks or try-catch to avoid NPE when webhooks are disabled.
- If `config.webhookEnabled()`:
  - Replace the hardcoded service name/namespace passed to `WebhookRegistrationConfig.builder(...)` with `config.webhookServiceName()` and `config.webhookServiceNamespace()`:
    ```java
    WebhookRegistrationConfig registrationConfig = WebhookRegistrationConfig.builder(config.webhookServiceName(),
            config.webhookServiceNamespace(), certificatePaths.caPath()).withServicePort(config.webhookServicePort())
            .withBaseName(registrationBaseName(config)).build();
    ```
  - When `config.webhookCertAutoGenerate()` is `true`, `resolveWebhookCertificatePaths` calls:
    ```java
    new WebhookCertificateSecretManager(client, config.webhookCertSecretName(), config.operatorPodNamespace(),
            config.webhookServiceName(), config.webhookServiceNamespace(), config.webhookCertDirectory()).resolve()
    ```
    and uses the returned paths. The cert Secret lives in the operator pod namespace (`operatorPodNamespace`), not necessarily the watched `operatorNamespace`.
  - When `autoGenerate` is `false`, existing file-based path resolution remains unchanged.
  - In `start()`, after `webhookServer.start()` and before `webhookSelfRegistration.register(admissionHandler)`, call:
    ```java
    webhookSelfRegistration.patchConversionWebhookClientConfig("echoresources.example.com", certificatePaths.caPath(),
            config.webhookServiceName(), config.webhookServiceNamespace(), config.webhookServicePort());
    ```
    Only when `config.webhookCertAutoGenerate()` is true.
- Remove or repurpose the now-redundant `generateWebhookCertificate` method.

**Happy-path QA**
- With `WEBHOOK_ENABLED=true` and `WEBHOOK_CERT_AUTO_GENERATE=true`, `EchoOperatorMain.create()` starts successfully, creates the Secret, registers admission webhooks with `clientConfig.service.name`/`namespace` equal to config, and patches the CRD conversion `caBundle` and service fields.
- With `WEBHOOK_ENABLED=false`, `EchoOperatorMain.create().start().stop()` starts metrics/health and operator, deletes stale self-registered admission webhook configurations, does not create a Secret or start a webhook server, and does not throw NPE.
- Evidence: integration tests with fabric8 mock server pass.

**Failure-path QA**
- If `WEBHOOK_CERT_AUTO_GENERATE=true` but the ServiceAccount lacks Secret permissions, startup fails with a Kubernetes API 403.
- If `WEBHOOK_ENABLED=false` and deleting stale admission webhook configurations returns a non-404 Kubernetes API error, startup fails loudly instead of leaving stale fail-closed webhooks active.
- Evidence: mock-server tests return 403 on Secret get/create and on disabled-mode admission cleanup, and assert exception propagation.

**Blocked by**: T4, T6, T7
**Commit**: `feat(echo-operator): wire Secret manager and CRD clientConfig patch`

---

### T9: Update Helm RBAC for minimal Secret permissions and disabled-mode admission cleanup
- [x] 9. Task
**References**
- `example/echo-operator/helm/echo-operator/templates/role.yaml:1-32`
- `example/echo-operator/helm/echo-operator/templates/clusterrole.yaml`
- `example/echo-operator/helm/echo-operator/templates/clusterrolebinding.yaml`

**Acceptance**
- Render Secret rules only when `.Values.rbac.create`, `.Values.webhook.enabled`, and `.Values.webhook.certAutoGenerate` are all true.
- Rules:
  ```yaml
  - apiGroups: [""]
    resources: ["secrets"]
    resourceNames: [{{ .Values.webhook.certSecretName | quote }}]
    verbs: ["get"]
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["create"]
  ```
- `create` is allowed without `resourceNames` because some Kubernetes auth implementations do not enforce resourceNames on create.
- Update `clusterrole.yaml` and `clusterrolebinding.yaml` so a disabled-webhook Helm upgrade can clean up stale self-registered admission webhooks:
  - When `.Values.rbac.create=true` and `.Values.webhook.enabled=true`, keep the existing admissionregistration and CRD permissions needed for self-registration and CRD clientConfig patching.
  - When `.Values.rbac.create=true` and `.Values.webhook.enabled=false`, render only the minimum admissionregistration permissions required for cleanup: `get`/`delete` on `validatingwebhookconfigurations` and `mutatingwebhookconfigurations` for the exact Java self-registration resource name `DEFAULT_WEBHOOK_SERVICE_NAME + "." + effectiveOperatorNamespace + "." + WEBHOOK_NAME` (currently `echo-operator.<namespace>.echo.example.com`). Do **not** derive this from `.Values.webhook.admissionName`, because that value is for Helm-managed webhook templates and can diverge from Java self-registration. Do not render CRD update/patch permissions and do not render Secret permissions.
  - When `.Values.rbac.create=false`, render no Role, ClusterRole, RoleBinding, or ClusterRoleBinding.

**Happy-path QA**
- `helm template echo-operator example/echo-operator/helm/echo-operator --set rbac.create=true --set webhook.enabled=true --set webhook.certAutoGenerate=true` renders the Role with the secrets rules.
- Evidence: grep output shows `secrets` under Role rules and admissionregistration/CRD permissions under ClusterRole rules.

**Failure-path QA**
- `helm template ... --set rbac.create=true --set webhook.enabled=true --set webhook.certAutoGenerate=false` does not render the secrets rules.
- `helm template ... --set rbac.create=true --set webhook.enabled=false` does not render the secrets rules, does render admissionregistration `get`/`delete`, and does not render CRD update/patch permissions.
- `helm template ... --set rbac.create=false` does not render any Role, RoleBinding, ClusterRole, or ClusterRoleBinding.
- Evidence: grep output confirms no `secrets` rules in any disabled case and confirms the disabled cleanup ClusterRole shape.

**Blocked by**: none
**Commit**: `feat(helm): allow operator to manage webhook CA Secret`

---

### T10: Update Helm Deployment for webhook enablement, auto-generate emptyDir, fsGroup, and env vars
- [x] 10. Task
**References**
- `example/echo-operator/helm/echo-operator/templates/deployment.yaml:40-102`
- `example/echo-operator/helm/echo-operator/values.yaml:34-57`
- `example/echo-operator/helm/echo-operator/templates/_helpers.tpl`

**Acceptance**
- Add `.Values.webhook.certAutoGenerate` (default `true`) and `.Values.webhook.certSecretName` (default `echo-operator-webhook-ca`).
- Always render metrics port and liveness/readiness probes (independent of webhooks).
- When `.Values.webhook.enabled` is true:
  - Always set env `OPERATOR_POD_NAMESPACE` from `metadata.namespace` via the Kubernetes downward API.
  - Set env `WEBHOOK_ENABLED=true`.
  - Set env `WEBHOOK_SERVICE_NAME={{ default (include "echo-operator.fullname" .) .Values.webhook.service.name }}`.
  - Set env `WEBHOOK_SERVICE_NAMESPACE={{ .Release.Namespace }}`.
  - Set env `WEBHOOK_PORT={{ .Values.webhook.port }}`.
  - Set env `WEBHOOK_SERVICE_PORT={{ .Values.webhook.service.port }}`.
  - When `.Values.webhook.certAutoGenerate` is true:
    - Set env `WEBHOOK_CERT_AUTO_GENERATE=true`.
    - Set env `WEBHOOK_CERT_DIRECTORY=/tmp/echo-operator/certs`.
    - Set env `WEBHOOK_CERT_SECRET_NAME={{ .Values.webhook.certSecretName }}`.
    - Set env `WEBHOOK_CA_BUNDLE_PATH=/tmp/echo-operator/certs/ca.crt`.
    - Mount an `emptyDir` volume at `/tmp/echo-operator/certs`.
    - Add `fsGroup: 1001` and `fsGroupChangePolicy: OnRootMismatch` to the pod `securityContext`.
  - When `certAutoGenerate=false`, keep existing behavior: mount the pre-created TLS secret at `/etc/echo-operator/certs`, set `WEBHOOK_CERT_AUTO_GENERATE=false`, and set `WEBHOOK_CA_BUNDLE_PATH=/etc/echo-operator/certs/ca.crt`.
- When `webhook.enabled=false`, set env `WEBHOOK_ENABLED=false` and `OPERATOR_POD_NAMESPACE` via downward API, and do not render webhook ports, webhook env vars beyond `WEBHOOK_ENABLED`, or cert volumes/mounts.
- `readOnlyRootFilesystem: true` remains.

**Happy-path QA**
- `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=true` renders an `emptyDir` volume, a volume mount at `/tmp/echo-operator/certs`, `fsGroup: 1001`, `OPERATOR_POD_NAMESPACE` from `metadata.namespace`, and the auto-generate env vars.
- Evidence: `helm template` output contains `emptyDir: {}`, `mountPath: /tmp/echo-operator/certs`, `fsGroup: 1001`, `OPERATOR_POD_NAMESPACE`, and `WEBHOOK_CERT_SECRET_NAME`.

**Failure-path QA**
- `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=false` renders `WEBHOOK_CERT_AUTO_GENERATE=false`, the TLS secret mount at `/etc/echo-operator/certs`, and does NOT render the emptyDir cert mount or `WEBHOOK_CERT_SECRET_NAME`.
- `helm template ... --set webhook.enabled=false` still renders metrics probes, does not render webhook env vars except `WEBHOOK_ENABLED=false`, and does not render cert mounts.
- Evidence: `helm template` output confirms.

**Blocked by**: T7
**Commit**: `feat(helm): mount cert directory and pass webhook env vars`

---

### T11: Update Helm Service template to respect service name override
- [x] 11. Task
**References**
- `example/echo-operator/helm/echo-operator/templates/service.yaml`
- `example/echo-operator/helm/echo-operator/values.yaml`

**Acceptance**
- Change Service metadata name to `{{ default (include "echo-operator.fullname" .) .Values.webhook.service.name }}`.
- Service metadata namespace remains `{{ .Release.Namespace }}`.
- All Service selectors remain based on `echo-operator.selectorLabels`.

**Happy-path QA**
- `helm template ... --set webhook.service.name=my-webhook-svc --show-only templates/service.yaml` renders `name: my-webhook-svc`.
- Evidence: command output.

**Failure-path QA**
- Without override, Service name matches the Deployment's `WEBHOOK_SERVICE_NAME`:
  ```bash
  SERVICE_NAME=$(helm template echo-op example/echo-operator/helm/echo-operator \
    --set webhook.enabled=true --show-only templates/service.yaml | \
    awk '/^  name: /{print $2; exit}')
  ENV_NAME=$(helm template echo-op example/echo-operator/helm/echo-operator \
    --set webhook.enabled=true --show-only templates/deployment.yaml | \
    awk '/name: WEBHOOK_SERVICE_NAME/{getline; gsub(/"/, "", $2); print $2; exit}')
  test "$SERVICE_NAME" = "$ENV_NAME"
  printf '%s\n' "$SERVICE_NAME"
  ```
  The command exits 0 and prints `echo-op-echo-operator`.
- Evidence: command output.

**Blocked by**: none
**Commit**: `fix(helm): respect webhook.service.name override in Service`

---

### T12: Update Helm CRD and admission webhook templates for auto-generate, disablement, and caBundle safety
- [x] 12. Task
**References**
- `example/echo-operator/helm/echo-operator/templates/crd.yaml:14-24`
- `example/echo-operator/helm/echo-operator/templates/validatingwebhookconfiguration.yaml`
- `example/echo-operator/helm/echo-operator/templates/mutatingwebhookconfiguration.yaml`
- `example/echo-operator/helm/echo-operator/values.yaml`

**Acceptance**
- When `.Values.webhook.enabled` is false, render `spec.conversion.strategy: None` and no `webhook` block. Document that version conversion is disabled in this mode.
- When `.Values.webhook.enabled` is true and `.Values.webhook.certAutoGenerate` is true, use Helm `lookup` to read the existing CRD `spec.conversion.webhook.clientConfig.caBundle`:
  - If the live CRD exists and has a non-empty `caBundle`, render that value.
  - Otherwise render an empty string (`caBundle: ""`) because the operator will patch it at runtime.
- When `webhook.enabled=true` and `certAutoGenerate=false`, keep the existing behavior: render `caBundle: {{ .Values.webhook.caBundle | b64enc | quote }}`.
- Remove or deprecate `.Values.webhook.service.namespace` from `values.yaml`; the conversion client config service namespace always uses `.Release.Namespace`.
- Prevent Helm-managed admission webhook configurations from rendering with auto-generated certs and a static/empty CA bundle:
  - If `.Values.webhook.createWebhookConfigurations=true` and `.Values.webhook.certAutoGenerate=true`, Helm rendering must fail with a clear message such as `webhook.createWebhookConfigurations requires webhook.certAutoGenerate=false and a supplied webhook.caBundle`.
  - `validatingwebhookconfiguration.yaml` and `mutatingwebhookconfiguration.yaml` render only when `.Values.webhook.enabled=true`, `.Values.webhook.createWebhookConfigurations=true`, and `.Values.webhook.certAutoGenerate=false`.
  - When they render, they use the external/static `.Values.webhook.caBundle` path and do not conflict with the operator auto-generated CA path.

**Happy-path QA**
- `helm template ... --set webhook.enabled=false` renders `conversion.strategy: None`.
- `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=true` renders `caBundle: ""` when the CRD does not yet exist.
- `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=false --set webhook.createWebhookConfigurations=true --set webhook.caBundle=dummy-ca` renders the Helm-managed validating/mutating webhook configurations with a non-empty `caBundle`.
- Evidence: `helm template` output confirms.

**Failure-path QA**
- `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=false` renders a non-empty `caBundle` from `.Values.webhook.caBundle`.
- `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=true --set webhook.createWebhookConfigurations=true` exits non-zero with the explicit `webhook.createWebhookConfigurations requires webhook.certAutoGenerate=false` message.
- Evidence: `helm template` output contains `caBundle: <base64-value>`.

**Blocked by**: T6
**Commit**: `feat(helm): handle webhook disablement and preserve caBundle on upgrade`

---

### T13: Update application.properties and README documentation
- [x] 13. Task
**References**
- `example/echo-operator/src/main/resources/application.properties`
- `example/echo-operator/README.md` (webhook environment variables section)
- `operator/framework/README.md` if it documents certificate behavior

**Acceptance**
- Document `WEBHOOK_ENABLED` / `webhook.enabled`, `WEBHOOK_CERT_SECRET_NAME` / `webhook.cert.secret-name`, `WEBHOOK_SERVICE_NAME` / `webhook.service.name`, `WEBHOOK_SERVICE_NAMESPACE` / `webhook.service.namespace`, and `OPERATOR_POD_NAMESPACE` / `operator.pod-namespace` with defaults and behavior.
- Explain that with `WEBHOOK_CERT_AUTO_GENERATE=true` the CA is persisted in the named Secret across restarts, the CRD conversion `caBundle` is patched automatically and preserved on upgrade, and the CA private key is not written to local disk.
- Explain that `WEBHOOK_ENABLED=false` disables webhook serving/registration, deletes stale self-registered admission webhook configurations, and renders CRD conversion strategy `None`; metrics/health remain active.
- Explain that Helm-managed admission webhook configurations (`webhook.createWebhookConfigurations=true`) are supported only with `webhook.certAutoGenerate=false` and a supplied static `webhook.caBundle`; auto-generated certs rely on operator self-registration instead.
- Keep `WEBHOOK_CERT_AUTO_GENERATE=false` documentation unchanged.

**Happy-path QA**
- `grep -n 'WEBHOOK_CERT_SECRET_NAME' example/echo-operator/src/main/resources/application.properties` returns at least one match with an accurate description.
- `grep -n 'WEBHOOK_SERVICE_NAME' example/echo-operator/src/main/resources/application.properties` returns at least one match.
- `grep -n 'WEBHOOK_ENABLED' example/echo-operator/src/main/resources/application.properties` returns at least one match.
- `grep -n 'OPERATOR_POD_NAMESPACE' example/echo-operator/src/main/resources/application.properties` returns at least one match.
- `grep -n 'WEBHOOK_CERT_SECRET_NAME' example/echo-operator/README.md` returns at least one match.
- Evidence: grep output shown.

**Blocked by**: T7
**Commit**: `docs(echo-operator): document new webhook env vars`

---

### T14a: Example config tests for new env vars
- [x] 14. Task
**References**
- `example/echo-operator/src/test/java/com/example/echooperator/` (existing tests)
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java`

**Acceptance**
- Add unit tests for `EchoOperatorMain.loadConfig(Map, Properties)`:
  - Defaults for `WEBHOOK_ENABLED`, `WEBHOOK_CERT_AUTO_GENERATE`, `WEBHOOK_CERT_SECRET_NAME`, `WEBHOOK_SERVICE_NAME`, `OPERATOR_POD_NAMESPACE`, and `WEBHOOK_SERVICE_NAMESPACE`.
  - Custom env/property values are picked up.
  - `OPERATOR_NAMESPACE=watched` plus `OPERATOR_POD_NAMESPACE=release-ns` keeps `operatorNamespace=watched`, `operatorPodNamespace=release-ns`, and default `webhookServiceNamespace=release-ns`.
  - Set-but-blank env values throw `IllegalArgumentException`.
  - Present-but-blank property values throw `IllegalArgumentException`.
  - Invalid boolean strings for `WEBHOOK_ENABLED` or `WEBHOOK_CERT_AUTO_GENERATE` throw `IllegalArgumentException`.
**Happy-path QA**
- `mvn -f example/echo-operator/pom.xml test` passes.
- Evidence: Maven output shows test success.

**Failure-path QA**
- If `WEBHOOK_CERT_SECRET_NAME` is set to blank, config loading throws.
- Evidence: test passes.

**Blocked by**: T7
**Commit**: `test(echo-operator): cover new webhook config env vars`

---

### T14b: Example wiring/integration tests for Secret manager, service name/namespace, enablement guard, and CRD patch
- [x] 15. Task
**References**
- `example/echo-operator/src/test/java/com/example/echooperator/` (existing tests)
- `example/echo-operator/src/main/java/com/example/echooperator/EchoOperatorMain.java`
- `example/echo-operator/pom.xml` (may need `kubernetes-server-mock` test dependency)

**Acceptance**
- Add tests verifying:
  - When `webhookEnabled=false`, `create()` does not instantiate `webhookServer`/`admissionHandler`/`conversionHandler`/`webhookSelfRegistration`, `start()` deletes stale validating/mutating webhook configurations for the stable registration base name, starts metrics/health and operator without NPE, and `stop()` runs without NPE.
  - When `webhookEnabled=true` and `webhookCertAutoGenerate=true`, `resolveWebhookCertificatePaths` uses the secret manager and produces paths under the cert directory.
  - `start()` calls `patchConversionWebhookClientConfig`.
  - Admission webhook registration uses `config.webhookServiceName()` and `config.webhookServiceNamespace()` as `clientConfig.service.name`/`namespace`.
  - Admission webhook registration names use the stable base name and do not change when only `WEBHOOK_SERVICE_NAME` / `WEBHOOK_SERVICE_NAMESPACE` changes.
  - The patched CRD conversion webhook `clientConfig.service.name`/`namespace`/`port` equal config values.
  - The cert Secret namespace equals `config.operatorPodNamespace()` when `operatorNamespace` and `operatorPodNamespace` differ.

**Happy-path QA**
- `mvn -f example/echo-operator/pom.xml test` passes.
- Evidence: Maven output shows the new integration test green.

**Failure-path QA**
- If the ServiceAccount cannot read Secrets, `resolveWebhookCertificatePaths` propagates the Kubernetes 403 exception and startup fails.
- If disabled-mode admission webhook cleanup returns a non-404 error, startup fails and the test asserts the exception is visible.
- Evidence: mock-server test returns 403 on Secret get/create and asserts exception propagation.

**Blocked by**: T8, T14a
**Commit**: `test(echo-operator): cover auto-generate Secret manager and CRD patch wiring`

---

### T15: Helm lint and template verification
- [x] 16. Task
**References**
- `example/echo-operator/helm/echo-operator/`
- `example/echo-operator/helm/echo-operator/templates/_helpers.tpl`
- `example/echo-operator/helm/echo-operator/templates/service.yaml`

**Acceptance**
- `helm lint example/echo-operator/helm/echo-operator` passes with no errors or warnings.
- `helm template` renders successfully for all combinations:
  - `webhook.enabled=true`, `webhook.certAutoGenerate=true`
  - `webhook.enabled=true`, `webhook.certAutoGenerate=false`
  - `webhook.enabled=false`
- With a non-default `fullnameOverride`, `WEBHOOK_SERVICE_NAME` in the Deployment equals the rendered Service name for both `certAutoGenerate=true` and `false`:
  ```bash
  for mode in true false; do
    SERVICE_NAME=$(helm template custom-echo example/echo-operator/helm/echo-operator \
      --set fullnameOverride=custom-echo \
      --set webhook.enabled=true \
      --set webhook.certAutoGenerate=$mode \
      --show-only templates/service.yaml | awk '/^  name: /{print $2; exit}')
    ENV_NAME=$(helm template custom-echo example/echo-operator/helm/echo-operator \
      --set fullnameOverride=custom-echo \
      --set webhook.enabled=true \
      --set webhook.certAutoGenerate=$mode \
      --show-only templates/deployment.yaml | \
      awk '/name: WEBHOOK_SERVICE_NAME/{getline; gsub(/"/, "", $2); print $2; exit}')
    test "$SERVICE_NAME" = "$ENV_NAME"
    test "$SERVICE_NAME" = custom-echo
  done
  ```
  The loop exits 0 for both modes.
- Verify `fsGroup: 1001` renders when `webhook.certAutoGenerate=true`.
- Verify `WEBHOOK_CERT_AUTO_GENERATE=false` renders when `webhook.enabled=true --set webhook.certAutoGenerate=false`.
- Verify `OPERATOR_POD_NAMESPACE` renders from `metadata.namespace`, and with `--set operator.namespace=watched --namespace release-ns` the Deployment carries watched namespace separately from pod namespace.
- Verify `conversion.strategy: None` renders when `webhook.enabled=false`.
- Verify metrics liveness/readiness probes render when `webhook.enabled=false`.
- Verify `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=true --set webhook.createWebhookConfigurations=true` fails with the expected message, and `helm template ... --set webhook.enabled=true --set webhook.certAutoGenerate=false --set webhook.createWebhookConfigurations=true --set webhook.caBundle=dummy-ca` renders Helm-managed admission webhook configs.

**Happy-path QA**
- `helm lint` exits 0.
- The above extraction loop shows consistent `custom-echo` values, `fsGroup: 1001`, `WEBHOOK_CERT_AUTO_GENERATE=false` in the external TLS mode, downward-API `OPERATOR_POD_NAMESPACE`, Helm-managed admission config failure in auto-generate mode, and no unexpected errors.
- Evidence: command output.

**Failure-path QA**
- `helm template ... --set webhook.enabled=false` does not reference `WEBHOOK_SERVICE_NAME`, `WEBHOOK_CERT_SECRET_NAME`, or webhook ports, but does render metrics probes and the disabled-mode admission cleanup ClusterRole/ClusterRoleBinding.
- Evidence: `helm template` output confirms.

**Blocked by**: T9, T10, T11, T12
**Commit**: `chore(helm): verify chart renders for all webhook/cert modes`

---

### T16: Final Maven verification
- [x] 17. Task
**References**
- `operator/framework/pom.xml`
- `example/echo-operator/pom.xml`

**Acceptance**
- `mvn -f operator/framework/pom.xml install` succeeds (installs SNAPSHOT for the example).
- `mvn -f operator/framework/pom.xml test` passes.
- `mvn -f example/echo-operator/pom.xml test` passes.

**Happy-path QA**
- Both modules build with `BUILD SUCCESS`.
- Evidence: Maven logs.

**Failure-path QA**
- If any test fails, fix before marking complete.
- Evidence: zero failing tests in logs.

**Blocked by**: T5, T14b, T15
**Commit**: `chore: verify full build after CA persistence changes`

---

### T17: Manual end-to-end verification (mandatory for upgrade preservation)
- [x] 18. Task
**References**
- `example/echo-operator/scripts/deploy.sh`
- `example/echo-operator/scripts/undeploy.sh`

**Acceptance**
- Deploy with `webhook.certAutoGenerate=true`.
- Apply a v1alpha1 and v1alpha2 CR and confirm conversion works.
- Record the current CRD `caBundle`:
  ```bash
  BEFORE=$(kubectl get crd echoresources.example.com -o jsonpath='{.spec.conversion.webhook.clientConfig.caBundle}')
  ```
- Run `helm upgrade` with the same values.
- After upgrade, assert:
  ```bash
  AFTER=$(kubectl get crd echoresources.example.com -o jsonpath='{.spec.conversion.webhook.clientConfig.caBundle}')
  [ "$BEFORE" == "$AFTER" ]
  ```
- Delete the operator pod to force restart.
- Re-apply CRs; conversion still works.
- Check that Secret `echo-operator-webhook-ca` exists and contains only `ca.crt` and `ca.key`:
  ```bash
  RELEASE_NS=${RELEASE_NS:-default}
  kubectl -n "$RELEASE_NS" get secret echo-operator-webhook-ca -o json | \
    python3 -c 'import json,sys; keys=sorted(json.load(sys.stdin)["data"].keys()); assert keys == ["ca.crt", "ca.key"], keys; print(",".join(keys))'
  ```
  The command exits 0 and prints `ca.crt,ca.key`.
- Check that the local cert directory does not contain `ca.key`:
  ```bash
  RELEASE_NS=${RELEASE_NS:-default}
  POD=$(kubectl -n "$RELEASE_NS" get pod -l app.kubernetes.io/name=echo-operator -o jsonpath='{.items[0].metadata.name}')
  test -n "$POD"
  kubectl -n "$RELEASE_NS" exec "$POD" -- sh -c 'test ! -e /tmp/echo-operator/certs/ca.key'
  ```
  Both `test` commands exit 0.
- Run an explicit CA equality and conversion check after the restart:
  ```bash
  RELEASE_NS=${RELEASE_NS:-default}
  SECRET_CA=$(kubectl -n "$RELEASE_NS" get secret echo-operator-webhook-ca -o jsonpath='{.data.ca\.crt}')
  CRD_CA=$(kubectl get crd echoresources.example.com -o jsonpath='{.spec.conversion.webhook.clientConfig.caBundle}')
  [ "$SECRET_CA" = "$CRD_CA" ]
  printf '%s\n' \
    'apiVersion: example.com/v1alpha2' \
    'kind: EchoResource' \
    'metadata:' \
    '  name: my-echo-v2' \
    'spec:' \
    '  message: Hello from v1alpha2' \
    '  replicas: 1' \
    '  logLevel: INFO' >/tmp/echo-v1alpha2.yaml
  kubectl apply -f example/echo-operator/examples/echo-cr.yaml
  kubectl apply -f /tmp/echo-v1alpha2.yaml
  kubectl get echoresource my-echo -o jsonpath='{.apiVersion}' | grep 'example.com/v1alpha2'
  kubectl get echoresource my-echo-v2 -o jsonpath='{.apiVersion}' | grep 'example.com/v1alpha2'
  ```

**Happy-path QA**
- Conversion succeeds after restart and upgrade; CRD `caBundle` is preserved.
- Evidence: `kubectl apply` succeeds; `kubectl get echoresources` returns correct stored version; the upgrade assertion passes.

**Failure-path QA**
- If conversion fails after restart or upgrade, first run the CA equality check above; if `[ "$SECRET_CA" = "$CRD_CA" ]` fails, the failure is a CA bundle mismatch and must be fixed before completion.
- Evidence: record the `SECRET_CA == CRD_CA` command exit code, both `kubectl apply` exits, and both `kubectl get ... apiVersion` outputs.

**Blocked by**: T16
**Commit**: N/A (verification only)

## Final verification wave

After all todos are complete, run these in parallel and require ALL to approve before declaring done:

- [x] F1. Plan compliance audit**: Re-read `.omo/plans/webhook-ca-secret-persistence.md` and run `git diff --check` to ensure no whitespace issues. Confirm each todo has a corresponding commit or documented skip reason. Command: `git diff --check; echo $?` must be 0.
- [x] F2. Code quality review**: Run the following commands and confirm the expected results:
  - `grep -R "System.out" operator/framework/src/main/java example/echo-operator/src/main/java` → no hits.
  - `grep -R "secrets" example/echo-operator/helm/echo-operator/templates/clusterrole.yaml` → no hits.
  - `grep -R "PemCertificateUtils" operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/ReloadableSslContext.java operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateSecretManager.java` → both hit.
  - `grep -R "certDirectory.*ca.key\|ca.key.*certDirectory\|Path.*ca.key\|ca.key.*Path" operator/framework/src/main/java/com/huawei/dcs/modelengine/operator/framework/webhook/cert/WebhookCertificateSecretManager.java` → no hits (CA key not written locally).
- [x] F3. Scope fidelity**: Run `helm template` for each mode and confirm:
  - `webhook.enabled=true --set webhook.certAutoGenerate=false` renders `WEBHOOK_CERT_AUTO_GENERATE=false` and does not render `emptyDir` cert mount or `WEBHOOK_CERT_SECRET_NAME` env.
  - `webhook.enabled=false` renders `WEBHOOK_ENABLED=false` and `conversion.strategy: None`.
  - Inspect `EchoOperatorMain.create()` and confirm `webhookEnabled=false` skips `admissionHandler`/`conversionHandler`/`webhookServer`/`webhookSelfRegistration` instantiation.
- [x] F4. Upgrade preservation verification**: Execute T17 and record the `BEFORE == AFTER` assertion output. A live cluster is required; if unavailable, document the block and do not mark the plan complete until T17 is performed.

## Commit strategy

- One commit per todo (atomic, reviewable).
- Commit message prefix follows repo convention: `feat(...)`, `refactor(...)`, `test(...)`, `docs(...)`, `chore(...)`.
- Final verification commits can be squashed into the relevant feature commits; keep `chore: verify full build` separate if it touches only CI/config.

## Success criteria

- `WEBHOOK_CERT_AUTO_GENERATE=true` persists the CA in Secret `echo-operator-webhook-ca`.
- The CA Secret is created in `OPERATOR_POD_NAMESPACE`, not the watched `OPERATOR_NAMESPACE`, when those differ.
- Operator restart does not change the CA; CRD conversion webhook continues to work.
- Helm upgrade does not wipe the runtime-patched CRD `caBundle`.
- `WEBHOOK_CERT_AUTO_GENERATE=false` behavior is unchanged.
- `WEBHOOK_CERT_AUTO_GENERATE=false` is explicitly rendered in Helm's external TLS mode so Java cannot fall back to its auto-generate default.
- `WEBHOOK_ENABLED=false` skips all webhook setup, deletes stale self-registered admission webhooks, renders CRD conversion strategy `None`, and keeps metrics/health active.
- All framework and example unit tests pass.
- `helm lint` passes for all webhook/cert mode combinations and for a non-default `fullnameOverride`.
- RBAC grants only `get` on the named Secret and `create` on Secrets in the operator namespace, only when webhooks and auto-generate are enabled.
- Disabled-mode RBAC grants only the minimum admissionregistration cleanup permissions and no Secret or CRD update permissions.
- The CA private key is never written to the local cert directory.
