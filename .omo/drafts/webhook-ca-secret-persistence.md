---
slug: webhook-ca-secret-persistence
intent: clear
review_required: true
status: approved
created: 2026-07-23
---

# webhook-ca-secret-persistence

- Approach: Persist auto-generated webhook CA to a Kubernetes Secret; server cert is generated locally per pod; on startup read Secret or generate and create it; write PEMs to local cert directory for WebhookServer file-based loading; patch CRD conversion caBundle at runtime.
- Secret name: `echo-operator-webhook-ca` (separate from the external TLS secret `echo-operator-webhook-tls`).
- Server cert on restart: regenerate fresh 365-day server cert locally; CA stays stable across restarts. Secret stores only `ca.crt`/`ca.key`.
- Container cert directory: `/tmp/echo-operator/certs` mounted as emptyDir when auto-generate=true (Deployment has `readOnlyRootFilesystem: true`).
- RBAC: Role grants `get` on named Secret and `create` on Secrets in the operator namespace.
- Framework API: extend `GeneratedCertificate` with CA private key; add public `WebhookCertificateSecretManager` helper; add CRD conversion caBundle patch to `WebhookSelfRegistration`.
- Secret name: `echo-operator-webhook-ca` (separate from the external TLS secret `echo-operator-webhook-tls`).
- Server cert on restart: regenerate fresh 365-day server cert and update Secret; CA stays stable across restarts.
- Container cert directory: `/tmp/echo-operator/certs` mounted as emptyDir when auto-generate=true (Deployment has `readOnlyRootFilesystem: true`).
- RBAC: add `secrets` get/create/update to the namespace-scoped Role (`templates/role.yaml`).
- Framework API: extend `GeneratedCertificate` with CA private key; add public `WebhookCertificateSecretManager` helper.

## Approval brief

### What we found
- `EchoOperatorMain` currently hard-codes webhook creation; `WEBHOOK_CERT_AUTO_GENERATE=true` is the only path that avoids pre-creating a TLS Secret, but it writes certs to `/tmp/echo-operator/certs` and the CA is lost on restart.
- Helm deployment mounts `webhook.tls.secretName` read-only at `/etc/echo-operator/certs`; `readOnlyRootFilesystem: true`; Role has no Secret permissions.
- `GeneratedCertificate` does not currently carry the CA private key, so persisting it requires a record change.
- `WebhookSelfRegistration` reads the CA bundle from a file path, so as long as the CA cert is written to the cert directory, admission registration continues to work.
- CRD conversion caBundle is Helm-static, so keeping the CA stable is essential for conversion webhook availability across restarts.

### Proposed approach
1. Extend `GeneratedCertificate` with `caPrivateKey` and `caPrivateKeyPem`.
2. Add `WebhookCertificateSecretManager` in the framework to:
   - Read `echo-operator-webhook-ca` Secret data (`ca.crt`, `ca.key`, `tls.crt`, `tls.key`).
   - If missing, call `WebhookCertificateGenerator`, create/update the Secret, and return the certificates.
   - Write all PEMs atomically to the configured cert directory so `WebhookServer`/`CertWatcher` keep working unchanged.
3. Update `EchoOperatorMain.resolveWebhookCertificatePaths` to use the secret manager when `webhookCertAutoGenerate=true`.
4. Add `OperatorConfig.webhookCertSecretName` and env var `WEBHOOK_CERT_SECRET_NAME`.
5. Helm: add `webhook.certAutoGenerate` and `webhook.certSecretName` values; when `certAutoGenerate=true` mount an emptyDir at `/tmp/echo-operator/certs`, set `WEBHOOK_CERT_DIRECTORY` and `WEBHOOK_CA_BUNDLE_PATH` to that directory, and add `WEBHOOK_CERT_SECRET_NAME`.
6. RBAC: Role gains `secrets` resource verbs `[get, list, create, update]` (and `patch` if using createOrReplace).
7. Tests: unit tests for Secret read/create/write; integration test for restart stability.

### Surviving owner-decisions (with defaults)
1. **Secret name**: default `echo-operator-webhook-ca` (separate from external TLS secret). OK?
2. **Server cert on restart**: default regenerate fresh server cert and update Secret. Alternative is reuse if still valid. Default OK?
3. **Cert directory mount**: default `/tmp/echo-operator/certs` emptyDir. OK?
4. **RBAC scope**: use existing namespace-scoped Role, not ClusterRole. OK?

If you approve, I will scaffold the plan file and produce the full work plan.

## Metis gap-analysis findings (integrated into plan)

- T3 originally contradicted itself on whether server cert is reused or regenerated; clarified to "persist CA only, regenerate server cert on restart."
- Added T2 for server cert generation from existing CA API.
- Specified operation order in Secret manager: generate/resolve cert material → verify cert directory writable → create/update Secret → atomically move local files.
- Added concurrency handling: re-read Secret on AlreadyExists, retry update on conflict.
- Tightened RBAC to `[get, update]` on named Secret + `[create]` on secrets; dropped `list`/`patch`.
- Specified exact Fabric8 API chains (`client.secrets().inNamespace(...).withName(...).get()`, `.resource(secret).create()`, `.resource(secret).update()`).
- Fixed config blank handling: validate after fallback, throw `IllegalArgumentException` if blank.

## High-accuracy review fixes (integrated into plan)

- **Momus #1 / design fix**: Added `serviceName` and `serviceNamespace` to `WebhookCertificateSecretManager` constructor so server certs retain correct Kubernetes service SANs.
- **Momus #2 / Oracle #4**: Replaced post-fallback blank validation with a new `resolveRequiredConfig` helper that treats a present-but-blank env/property value as invalid and throws `IllegalArgumentException`.
- **Momus #3**: Made T12 documentation QA executable with explicit `grep` commands and file paths.
- **Oracle #1**: Added T6 to patch CRD conversion `caBundle` at runtime via `WebhookSelfRegistration.patchConversionWebhookCaBundle`; added T11 to render empty `caBundle` in Helm CRD when auto-generate is enabled.
- **Oracle #2**: Replaced `AlreadyExistsException` with `KubernetesClientException` and `getCode() == 409` checks, matching Fabric8 7.3.0 API.
- **Oracle #3**: Simplified Secret to store only `ca.crt`/`ca.key`; server cert is generated locally and the Secret is never updated after creation, eliminating update conflicts under concurrent starts.

- Noted that `example/echo-operator/pom.xml` lacks `kubernetes-server-mock`; plan adds it if needed for wiring tests.
- Made manual end-to-end verification optional; automated Maven/Helm tests remain mandatory.
- Added PEM edge cases: invalid base64, mismatched CA cert/key, encrypted keys, non-RSA keys.
- Fixed dependency order: framework `install` before example tests; Helm Deployment no longer blocked by Role templating.

## Second high-accuracy review fixes (integrated into plan)

- **Momus #1**: Made webhook Service name configurable via `WEBHOOK_SERVICE_NAME` / `webhook.service.name` and wired it from Helm into `EchoOperatorMain`, `WebhookCertificateSecretManager`, and `WebhookSelfRegistration`. Added Helm render verification for non-default release names.
- **Momus #2**: Tightened RBAC condition: Secret rules render only when `rbac.create`, `webhook.enabled`, and `webhook.certAutoGenerate` are all true.
- **Momus #3**: Made Final Verification Wave F1–F4 executable with concrete commands, expected outputs, and pass/fail criteria; added explicit assertion that decoded Secret `ca.crt` equals CRD `caBundle`.



## Third high-accuracy review fixes (integrated into plan)

- **Momus #1 / Oracle #1**: T8 now explicitly requires `WebhookRegistrationConfig.builder(config.webhookServiceName(), ...)` and T13b asserts registered admission webhook `clientConfig.service.name` equals the configured service name.
- **Momus #2 / Oracle #2**: T14 now uses `--set fullnameOverride=custom-echo` and asserts the rendered Service metadata name equals `WEBHOOK_SERVICE_NAME` in the Deployment.
- **Oracle #3**: Removed all local `ca.key` writes. The CA private key is kept only in the Secret and in memory. Local cert directory contains `ca.crt`, `tls.crt`, `tls.key`. Updated T1, T4, T5, T16, F2, and success criteria accordingly.


## Fourth high-accuracy review fixes (integrated into plan)

- **Momus #1 / Oracle concern**: T3 now provides in-memory `byte[]` PEM readers in addition to `Path` readers; T4 uses them to decode `ca.key` from the Secret without writing the CA private key to disk.
- **Momus #2**: Added `WEBHOOK_ENABLED` / `webhook.enabled` config and T8 guard so `EchoOperatorMain` skips all webhook setup when webhooks are disabled. T9 Secret RBAC and T10 cert mount/envs are now conditional on both `webhook.enabled` and `webhook.certAutoGenerate`.
- **Momus #3 / Oracle concern**: T11 updates `templates/service.yaml` to respect `.Values.webhook.service.name`; T15 verifies Service metadata name, Deployment env, and CRD client service name agree for explicit overrides.
- **Oracle #1**: T10 adds `fsGroup: 1001` and `fsGroupChangePolicy: OnRootMismatch` to the pod securityContext when `webhook.certAutoGenerate=true` so UID 1001 can write to the emptyDir.
- **Oracle #2**: T6 adds bounded retry on CRD update 409; if a re-read shows the desired `caBundle` already set, it returns success.
- **Oracle #3**: T12 uses Helm `lookup` to preserve an existing non-empty CRD conversion `caBundle` when `webhook.certAutoGenerate=true`, preventing Helm upgrades from wiping it.
- **Oracle #4**: T10 renders `WEBHOOK_SERVICE_NAME` whenever `webhook.enabled=true`, not only for auto-generate mode, so non-default release names work with external TLS secrets too.


## Fifth high-accuracy review fixes (integrated into plan)

- **Momus #1 / Oracle #2**: T12 now renders `conversion.strategy: None` when `webhook.enabled=false`, so disabling webhooks does not leave a non-functional webhook client config in the CRD.
- **Momus #2 / Oracle #3**: Added `WEBHOOK_SERVICE_NAMESPACE` / `webhook.service.namespace` config and used it consistently in `WebhookRegistrationConfig`, `WebhookCertificateSecretManager`, and Helm CRD/Deployment templates.
- **Momus #3 / Oracle #4**: T14a now uses a testable `loadConfig(Map, Properties)` overload instead of mutating `System.getenv()`. T17 is mandatory for upgrade preservation and F4 no longer permits skipping it.
- **Oracle #1**: T8 now guards `start()` and `stop()` against null webhook fields when `WEBHOOK_ENABLED=false`, and T14b tests that `create().start().stop()` with webhooks disabled does not throw NPE.

## Sixth high-accuracy review fixes (integrated into plan)

- **Momus #1**: T8 now passes `config.operatorNamespace()` as the Secret manager's `secretNamespace`, keeping the CA Secret in the operator pod namespace separate from a potentially overridden webhook Service namespace.
- **Momus #2 / Oracle #2**: T7 now rejects present-but-blank property-file values in addition to blank env values, and adds `resolveBooleanConfig` for strict `"true"`/`"false"` parsing of `WEBHOOK_ENABLED` and `WEBHOOK_CERT_AUTO_GENERATE`. T14a tests these cases.
- **Momus #3 / Oracle #3**: F2 no longer requires zero occurrences of the string `ca.key` in `WebhookCertificateSecretManager` (the Secret data key is legitimately named `ca.key`); instead it checks that no local cert-directory path references `ca.key`, and relies on T5/T17 to assert the CA key is absent from the local directory.
- **Oracle #1**: T6 renamed from `patchConversionWebhookCaBundle` to `patchConversionWebhookClientConfig` and now patches CRD `spec.conversion.webhook.clientConfig.service.name`, `.namespace`, and `.port` from config in addition to `caBundle`. T8 call site and T14b assertion updated accordingly.
- **Oracle #4**: T14a now tests invalid boolean strings for `WEBHOOK_ENABLED` and `WEBHOOK_CERT_AUTO_GENERATE`, and T14b asserts the patched CRD conversion service fields match config values.

## Seventh high-accuracy review fixes (integrated into plan)

- **Momus #1**: T10 now explicitly renders `WEBHOOK_CERT_AUTO_GENERATE=false` for the external TLS Secret path, preventing Java's default `true` from accidentally taking the auto-generate branch.
- **Momus #2 / Oracle #3**: T7 now adds a distinct `OPERATOR_POD_NAMESPACE` / `operator.pod-namespace` config populated by Helm from `metadata.namespace`; T8 uses `config.operatorPodNamespace()` for the CA Secret while keeping `operatorNamespace` as the watched namespace. T14/T15 test `.Values.operator.namespace != .Release.Namespace`.
- **Momus #3**: T17 now includes executable CA equality and conversion commands after upgrade/restart: compare Secret `data.ca.crt` directly with CRD `caBundle`, apply v1alpha1 and v1alpha2 CRs, and assert both read back as the storage version.
- **Oracle #1**: T8 now requires `.withBaseName(registrationBaseName(config))` so self-registered admission webhook object names remain stable when only Service name/namespace changes.
- **Oracle #2**: T6/T8/T9 now add disabled-mode admission webhook cleanup: delete stale self-registered validating/mutating webhook configurations when `WEBHOOK_ENABLED=false`, with minimal cleanup RBAC and tests for successful deletion and non-404 failure propagation.

## Eighth high-accuracy review fixes (integrated into plan)

- **Momus #1**: T11 and T15 now use context-aware `helm template` + `awk` extraction to compare the rendered Service metadata name to the actual `WEBHOOK_SERVICE_NAME` env value, instead of grepping only the env key.
- **Momus #2**: T17 now has exact executable commands for Secret key verification (`python3` assertion that keys are exactly `ca.crt,ca.key`) and pod-local `ca.key` absence (`kubectl exec` against the selected operator pod).
- **Oracle #1**: T9 disabled-cleanup RBAC now derives `resourceNames` from the actual Java self-registration name (`DEFAULT_WEBHOOK_SERVICE_NAME + "." + effectiveOperatorNamespace + "." + WEBHOOK_NAME`, currently `echo-operator.<namespace>.echo.example.com`) and explicitly does not use `.Values.webhook.admissionName`.

## Ninth high-accuracy review fixes (integrated into plan)

- **Oracle #1**: T12 now prevents Helm-managed admission webhook configurations from rendering with `webhook.certAutoGenerate=true`: `webhook.createWebhookConfigurations=true && webhook.certAutoGenerate=true` must fail with a clear Helm error. Helm-managed validating/mutating webhook configs now render only for `webhook.enabled=true`, `webhook.createWebhookConfigurations=true`, and `webhook.certAutoGenerate=false` with a supplied static `webhook.caBundle`. T15 verifies both the failure and external-TLS rendering paths.

## Final high-accuracy review receipts

- **Momus tenth review**: OKAY — updated Helm constraint is consistent with rendering paths and has concrete positive/failure verification in T12 and T15.
- **Oracle tenth review**: OKAY — plan blocks unsafe Helm-managed auto-generate admission webhook path and verifies the supported external-TLS rendering path.
- Status: ready for implementation by a separate worker session.
