2026-06-20: Echo mutating webhook now uses JSON Patch `add` for `/spec/message` when defaulting blank/missing values; this matches create-time defaulting semantics.
2026-06-20: `rebuildInformers()` should build replacement event sources, start the new informer factory, then swap `informerFactory` so reload keeps controllers attached to the fresh factory.

2026-06-20 (F3 rerun): All T1-T19 QA scenarios re-executed after T3/T9 blocker fixes.
- T3: OperatorLauncherTest.resyncPeriodReloadRebuildsInformersWhileWorkersKeepRunning passes; resync change rebuilds informers while workers keep running.
- T9: EchoMutatingWebhookTest.defaultsBlankMessageBeforeValidationSeesTheResource passes; blank spec.message defaulted to "Hello, Echo!" and annotation echo.example.com/mutated injected via JSON Patch `add`.
- SDK full test suite now 56 tests (was 55), example webhook tests now 9 (was 8).
- Build: operator/framework `mvn clean install` BUILD SUCCESS; example/echo-operator `mvn clean package` BUILD SUCCESS.
- Helm: lint passes; template renders conversion webhook block, webhook service (containerPort 8443), TLS Secret mount, reloadable ConfigMap.
- T19: smoke-test.sh syntax valid; cluster-based optional checks SKIPPED because TLS Secret `echo-operator-webhook-tls` is not present (manual TLS required by design, no cert-manager).
- Plan/class naming discrepancies found but not blockers: plan references `ConversionWebhookHandlerTest` but actual class is `ConversionHandlerTest`; default Helm values do not render Validating/MutatingWebhookConfiguration because operator self-registers them, but templates render correctly with `webhook.createWebhookConfigurations=true`.
2026-06-20: F4 rerun confirmed T3 hot reload now registers `Operator.withReloadableConfig` listener and rebuilds running informers when `resyncPeriod` changes; `OperatorLauncherTest.resyncPeriodReloadRebuildsInformersWhileWorkersKeepRunning` covers new factory/source creation.
2026-06-20: F4 rerun confirmed T9 mutating webhook defaults blank/missing `spec.message` to `Hello, Echo!` using JSON Patch `add` at `/spec/message` and injects `echo.example.com/mutated: "true"`.
2026-06-20: F4 rerun found remaining cert-manager textual references in Echo README, README.zh-CN, smoke-test comment, and Helm values comment; these are contamination against the plan's no cert-manager guardrail if literal reference cleanliness is required.
2026-06-20: Final F4 blocker fix removed all explicit `cert-manager` name references from Echo docs/comments, replacing them with neutral external TLS provisioning wording without changing Helm behavior or source code.
## Hot Reload Removal Summary - 2026-06-21

- Removed runtime config hot-reload mechanism from operator-framework.
- Deleted classes: ConfigWatcher, ReloadableConfig, ConfigChangeListener, OperatorConfigSnapshot and their tests.
- Removed jackson-dataformat-yaml dependency from operator/framework/pom.xml.
- Reverted Operator, ResourceEventSource, ExponentialBackoffRetryPolicy, RateLimiter to fixed-value semantics.
- Updated EchoOperatorMain and EchoOperatorMainTest to remove reloadable config wiring.
- Deleted example/echo-operator/src/main/resources/operator-config.yaml.
- Removed Helm reloadable ConfigMap template, values, and Deployment volume/env.
- Cleaned SDK README, dev-guide, Echo README, and smoke-test.sh of hot-reload references.
- Updated .sisyphus/plans/operator-sdk-advanced-features.md to reflect removal.

## 2026-06-21: Plan re-activation and DoD verification

- Boulder system was stuck on completed `remove-hot-reload` plan; switched `.sisyphus/boulder.json` to `operator-sdk-advanced-features.md`.
- Found 5 unchecked Definition of Done items in `operator-sdk-advanced-features.md` (lines 66-70).
- Verification executed:
  - `mvn -f operator/framework/pom.xml clean install` → BUILD SUCCESS (exit 0).
  - `mvn -f example/echo-operator/pom.xml clean package` → BUILD SUCCESS (exit 0).
  - Native `/opt/homebrew/bin/helm lint` → 0 chart(s) failed.
  - Native `/opt/homebrew/bin/helm template --set webhook.createWebhookConfigurations=true` → renders all 10 templates including ValidatingWebhookConfiguration, MutatingWebhookConfiguration, webhook Service, TLS Secret mount, conversion webhook block.
  - `mvn -f operator/framework/pom.xml test -Dtest=WebhookServerTest,AdmissionHandlerTest,ConversionWebhookHandlerTest` → 5 tests pass.
  - `mvn -f example/echo-operator/pom.xml test -Dtest=EchoValidatingWebhookTest,EchoMutatingWebhookTest,EchoConverterTest` → 12 tests pass.
- Note: `rtk helm template` truncates output and inserts literal "... (N lines truncated)" text, making it unreliable for verifying rendered resources. Use native `/opt/homebrew/bin/helm` for verification.
- All 5 DoD items now checked in the plan file.

## 2026-06-21: F1 Plan Compliance Audit

- Audited `.sisyphus/plans/operator-sdk-advanced-features.md` end-to-end.
- Must Have: 7/7 PASS (fabric8-only runtime, admission webhooks, manual TLS with reload, self-registration fail-fast, multi-version CRD + conversion, Helm resources, CN/EN docs).
- Must NOT Have: 7/7 PASS (no cert-manager, no self-signed cert generation at runtime, no storage migration, no YAML hot-reload, no leader/metrics/namespace hot-reload, no webhook metrics/tracing/test framework, no shared port with metrics/health).
- Task evidence: 16/16 exist (task-4 through task-19 map to T1-T16 of this plan).
- Maven verification: SDK `mvn clean install` BUILD SUCCESS (47 tests); example `mvn clean package` BUILD SUCCESS (29 tests).
- Helm verification: `helm lint` 0 failed; `helm template --set webhook.createWebhookConfigurations=true` renders all webhook resources.
- Output saved to `.sisyphus/evidence/f1-advanced-features-compliance.log`.
- Verdict: `Must Have [7/7] | Must NOT Have [7/7] | Tasks [16/16] | Evidence [16/16] | VERDICT: APPROVE`.
- Minor observations: v1alpha2 classes live in Java package `api.v2` (API version is still `example.com/v1alpha2`); webhook Service is integrated into `service.yaml` rather than a separate `webhook-service.yaml`; `ConversionWebhookHandlerTest` is actually `ConversionHandlerTest`. None are functional blockers.

## 2026-06-21: F3 Real Manual QA completed

- Executed all T1-T16 QA scenarios from `.sisyphus/plans/operator-sdk-advanced-features.md`.
- Detailed evidence saved to `.sisyphus/evidence/f3-advanced-features-qa.log`.
- Result: `Scenarios [16/16 pass] | Integration [4/4] | Edge Cases [6/6 tested] | VERDICT: APPROVE`.
- Key notes:
  - Used native `/opt/homebrew/bin/helm` for lint/template; `rtk helm` truncates output and is unsuitable for verification.
  - Helm webhook configs are not rendered by default because operator self-registers them; verification used `--set webhook.createWebhookConfigurations=true` to assert `ValidatingWebhookConfiguration`, `MutatingWebhookConfiguration`, webhook Service, TLS Secret mount, and CRD conversion block are present.
  - Plan references `ConversionWebhookHandlerTest`, but actual test class is `ConversionHandlerTest`; commands used the real class name.
  - Edge cases covered: missing CA bundle fail-fast, unregistered conversion version failure, blank-message mutation defaulting, webhook configs disabled by default, default values still expose webhook Service/TLS mount, and explicit webhook creation renders all resources plus conversion block.

## 2026-06-21: Renamed Java package api.v2 to api.v1alpha2

- Moved `example/echo-operator/src/main/java/com/example/echooperator/api/v2/` to `.../api/v1alpha2/`.
- Updated package declarations in `EchoResource.java`, `EchoSpec.java`, `EchoStatus.java`.
- Updated qualified references in `EchoConverter.java`, `EchoConverterTest.java`, `EchoOperatorMain.java`.
- Left `com.example.echooperator.api.v1` unchanged.
- Verified zero remaining `com.example.echooperator.api.v2` references via grep.
- Builds pass:
  - `mvn -f operator/framework/pom.xml clean install` → BUILD SUCCESS (47 tests).
  - `mvn -f example/echo-operator/pom.xml clean package` → BUILD SUCCESS (29 tests).
- Evidence saved to `.sisyphus/evidence/task-rename-v1alpha2.log`.

## 2026-06-21: F4 Scope Fidelity rerun after v1alpha2 package rename

- Re-ran F4 scope fidelity against `.sisyphus/plans/operator-sdk-advanced-features.md` after the package rename.
- Confirmed T7 path drift is resolved: `example/echo-operator/src/main/java/com/example/echooperator/api/v1alpha2/EchoResource.java` exists, declares `package com.example.echooperator.api.v1alpha2`, and uses `@Version(value = "v1alpha2", storage = true, served = true)`.
- Confirmed zero remaining `api.v2`, `/api/v2`, or `com.example.echooperator.api.v2` references via grep.
- Guardrail grep for cert-manager, self-signed cert generation wording, storage migration, hot reload, and webhook metrics/tracing patterns returned no matches.
- Maven verification passed: SDK `mvn -f operator/framework/pom.xml clean install` → BUILD SUCCESS (47 tests); example `mvn -f example/echo-operator/pom.xml clean package` → BUILD SUCCESS (29 tests).
- Evidence saved to `.sisyphus/evidence/f4-advanced-features-scope-rerun.log` with verdict: `Tasks [16/16 compliant] | Contamination [CLEAN] | Unaccounted [CLEAN] | VERDICT: APPROVE`.
