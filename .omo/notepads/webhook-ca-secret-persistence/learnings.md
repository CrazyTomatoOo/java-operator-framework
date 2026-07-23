#SX|#PS|#TT|# Learnings
#KM|#NM|#KM|
#JP|#KH|#SK|## Project conventions
#MR|#MV|#NS|- Java 21, Maven 3.9+, fabric8 Kubernetes Client 7.3.0
#SY|#TK|#PP|- Framework package: `com.huawei.dcs.modelengine.operator.framework.webhook`
#NH|#ZX|#ZS|- Example package: `com.example.echooperator`
#RW|#XJ|#RS|- Helm chart at `example/echo-operator/helm/echo-operator/`
#ZT|#ZM|#YQ|- Build: `mvn -f operator/framework/pom.xml install` then `mvn -f example/echo-operator/pom.xml test`
#JP|#BS|#HB|- WebhookSelfRegistration uses `config.baseName()` for resource naming: `baseName + "." + name`
#BT|#XM|#QJ|- WebhookRegistrationConfig has `.withBaseName(...)` builder method
#YX|#BJ|#TN|- Existing ClusterRole grants full admissionregistration CRUD (not resourceNames-scoped)
#JV|#QJ|#XQ|- `WEBHOOK_SERVICE_NAME = "echo-operator"` and `WEBHOOK_NAME = "echo.example.com"` are hardcoded constants
#NT|#MK|#MK|- `OperatorConfig` is a record; `loadConfig()` reads env vars + application.properties
#SK|#RX|#ZZ|- `GeneratedCertificate` now carries CA private key material in memory (`caPrivateKey`, `caPrivateKeyPem`) for later Secret persistence, but `WebhookCertificateGenerator` still only writes `ca.crt`, `tls.crt`, and `tls.key` to disk
#HX|#YX|#VP|- 2026-07-23: `templates/service.yaml` now uses `default (include "echo-operator.fullname" .) .Values.webhook.service.name`; `helm template` renders `my-webhook-svc` with override and `test-echo-operator` without it.
#RJ|#HQ|#NB|- Helm RBAC should only grant namespaced Secret access when webhook cert auto-generation is on; disabled webhook mode needs a cleanup ClusterRole scoped to `echo-operator.<namespace>.echo.example.com`
#SZ|#KK|## T7 config findings
#WB|#ZJ|- `OperatorConfig.loadConfig(Map<String, String>, Properties)` now makes webhook and pod-namespace resolution testable without changing the legacy `resolveConfig` contract.
#ZS|#MY|- Required webhook and pod namespace values reject present-but-blank environment or property values; boolean webhook settings accept only trimmed `true`/`false` values.
#TS|#BH|- `operator.pod-namespace` defaults to the watched `operator.namespace`, and `webhook.service.namespace` defaults to the resolved pod namespace.
#NV|#LW|- 2026-07-23: Helm deployment now always sets `OPERATOR_POD_NAMESPACE` from `metadata.namespace` and `WEBHOOK_ENABLED`; webhook-enabled pods split cert handling by `certAutoGenerate`, using `emptyDir` + `fsGroup: 1001` for generated certs and the pre-created TLS secret for mounted certs.
#SB|#TT|- 2026-07-23: Added `EchoOperatorMainConfigTest` coverage for webhook config defaults, env/property overrides, blank-value rejection, boolean validation, and the `operator.pod-namespace` → `webhook.service.namespace` fallback chain.
#NV|
#HV|## T6 webhook registration findings
#JN|- `patchConversionWebhookClientConfig` reads the explicitly supplied CA path, patches only CRDs using `spec.conversion.strategy: Webhook`, and retries resource-version conflicts three times after the initial update.
#NW|- The conversion patch is idempotent when the CA bundle and conversion service fields already match; admission cleanup normalizes leading-slash names and ignores only 404 deletes.
#BY|- Framework verification passed with the Fabric8 mock-server CRD/admission tests and the existing suite: 150 tests, 0 failures, 0 errors.
#HQ|
#KS|## T4 webhook CA Secret persistence findings
#HJ|- `WebhookCertificateSecretManager` reads `ca.crt` and `ca.key` from a namespaced Secret, validates the key by signing and verifying a challenge, and regenerates only the server certificate locally.
#ZW|- New CA material is staged in temporary files before Secret creation; only a successful create promotes `ca.crt`, `tls.crt`, and `tls.key`. The CA private key is never written to `certDirectory`.
#QH|- Secret creation uses type `Opaque`, label `app.kubernetes.io/managed-by: operator-framework`, and only `ca.crt`/`ca.key` data. A 409 create conflict re-reads the Secret and uses its CA.
#RH|- `resolve()` returns the promoted local paths in `GeneratedCertificate`; local permissions are `644` for certificates and `600` for `tls.key` on POSIX filesystems.
#WB|- Framework verification passed with the new mock-server certificate persistence tests: 155 tests, 0 failures, 0 errors.
#KG|- T5 added concurrent existing-Secret coverage for `resolve()`, proving two simultaneous calls reuse the same CA Secret and write the same local CA/server files.
#MP|- 2026-07-23: Helm chart now gates CRD conversion on `webhook.enabled`, renders `strategy: None` with no webhook block when disabled, preserves live CRD `caBundle` via `lookup` when `certAutoGenerate=true`, keeps the base64-encoded `webhook.caBundle` path when `certAutoGenerate=false`, and fails fast if `createWebhookConfigurations=true` is paired with auto-generated certs.
#XW|- 2026-07-23: Helm verification passed with `helm template` for disabled conversion, empty `caBundle` on auto-generate, base64 `caBundle` on explicit CA, the expected fail-fast error for Helm-managed webhook configs, and `helm lint`.
#XN|- 2026-07-23: `EchoOperatorMain` now guards webhook construction and startup with `webhook.enabled`, uses configured service identity plus a stable `echo-operator.<operator namespace>` admission base name, persists auto-generated CA material through `WebhookCertificateSecretManager`, patches conversion before admission registration, and cleans stale registrations in disabled mode.
#TT|- 2026-07-23: T15 verification: `helm lint example/echo-operator/helm/echo-operator` passed with only the expected `icon` warning; `helm template` passed for webhook enabled/auto-generate true, webhook enabled/auto-generate false, and webhook disabled.
#QN|- 2026-07-23: With `fullnameOverride=custom-echo`, `SERVICE_NAME` and `WEBHOOK_SERVICE_NAME` both rendered as `custom-echo` in both cert modes, `OPERATOR_POD_NAMESPACE` matched `metadata.namespace` (`test-ns`), `fsGroup: 1001` rendered when `certAutoGenerate=true`, and `WEBHOOK_CERT_AUTO_GENERATE=false` rendered when `certAutoGenerate=false`.
#LK|- 2026-07-23: Disabled webhook mode rendered `strategy: None` plus liveness/readiness probes; `webhook.createWebhookConfigurations=true` with auto-generated certs failed fast with `webhook.createWebhookConfigurations requires webhook.certAutoGenerate=false and a supplied webhook.caBundle`; with `webhook.certAutoGenerate=false` and `webhook.caBundle=dummy-ca`, the webhook configs rendered with `caBundle: "ZHVtbXktY2E="`.
- 2026-07-23: Added `EchoOperatorMainWiringTest` coverage for disabled webhook lifecycle/cleanup, auto-generated certificate paths and pod-namespace Secret placement, conversion patch arguments, and stable admission registration base names independent of service identity overrides.
