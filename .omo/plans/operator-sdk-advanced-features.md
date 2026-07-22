# Operator SDK 高级特性扩展

## TL;DR

> **Quick Summary**: 在现有 `operator-framework` SDK 基础上扩展两大高级能力：Admission Webhooks（validating + mutating，手动 TLS，operator 自注册）、多版本 CRD 与 conversion webhook 支持。
>
> **Deliverables**:
> - SDK: TLS `WebhookServer`（8443）+ `AdmissionValidator`/`AdmissionMutator` + self-registration
> - SDK: `ConversionWebhookHandler` + multi-version 注册语义
> - Echo Operator: `v1alpha2` 版本 + conversion + webhook 示例
> - Helm chart: webhook Service、Secret 挂载、ClusterRole/ClusterRoleBinding
>
> **Estimated Effort**: Large
> **Parallel Execution**: YES - 3 phases
> **Critical Path**: Admission webhooks (T1-T6) → Multi-version CRD (T7-T12) → F1-F4

---

## Context

### Original Request
为已完成的 Java K8s Operator SDK 补充：Admission Webhooks、多版本 CRD。

### Interview Summary
**Key Discussions**:
- **Admission Webhook**: 同时支持 Validating + Mutating；SDK 层通用支持
- **TLS 证书**: 手动提供 Secret；运行时自动重载证书文件
- **Webhook 部署**: Operator 启动时自注册 Validating/MutatingWebhookConfiguration；失败时 fail-fast
- **Webhook server 端口**: 与 metrics/health server 分离，默认 8443
- **多版本 CRD**: SDK 层通用；Echo 示例演示 v1alpha1/v1alpha2；使用 conversion webhook
- **Conversion webhook**: 与 admission webhook 共用同一个 TLS server

### Research Findings
- fabric8 7.7.0 提供 `admission.v1.AdmissionReview` / `AdmissionResponseBuilder` / `admissionregistration.v1.*` 模型
- CRD generator v2 通过 `@Version(storage, served, deprecated)` 合并多版本；conversion webhook 块需在 Helm 中补充

### Metis Review
**Identified Gaps** (addressed):
- Webhook 自注册失败模式 → fail-fast
- TLS 证书是否自动重载 → 是
- Webhook 端口 → 与 metrics 分离
- Leader election 参数 → 启动时固定
- 接受标准必须全部可执行，使用具体路径和数据

---

## Work Objectives

### Core Objective
在现有 SDK 上增量扩展两个高级特性，保持纯 fabric8 + JDK 运行时，并通过 Echo Operator 示例验证。

### Concrete Deliverables
- `operator/framework/src/main/java/.../webhook/WebhookServer.java`
- `operator/framework/src/main/java/.../webhook/AdmissionValidator.java`
- `operator/framework/src/main/java/.../webhook/AdmissionMutator.java`
- `operator/framework/src/main/java/.../webhook/WebhookSelfRegistration.java`
- `operator/framework/src/main/java/.../webhook/conversion/ConversionWebhookHandler.java`
- `operator/framework/src/main/java/.../webhook/conversion/ConversionResult.java`
- `operator/framework/src/test/java/.../webhook/` 单元测试
- `example/echo-operator/src/main/java/.../api/v1alpha2/EchoResource.java`
- `example/echo-operator/src/main/java/.../webhook/` 示例实现
- `example/echo-operator/src/main/java/.../converter/` conversion 实现
- Helm chart 新增 templates：webhook service、ClusterRole、ClusterRoleBinding、ValidatingWebhookConfiguration、MutatingWebhookConfiguration

### Definition of Done
- [x] SDK `mvn clean install` 成功
- [x] Example `mvn clean package` 成功
- [x] Helm chart `helm template` 渲染成功并包含 webhook 相关资源
- [x] Admission webhook：无效 CR 被拒绝，有效 CR 被 mutation 注入默认值
- [x] 多版本 CRD：v1alpha1 CR 读取为 v1alpha2 时转换成功

### Must Have
- 纯 fabric8，无 Quarkus/Spring Boot/JOSDK
- Admission webhooks：validating + mutating，SDK 通用接口
- 手动 TLS：Secret 挂载，运行时自动重载
- Operator 自注册 webhook 配置，失败 fail-fast
- 多版本 CRD：v1alpha1/v1alpha2，conversion webhook
- Helm chart 包含所有新资源
- 中英文 README/dev-guide 更新

### Must NOT Have (Guardrails)
- 不引入 cert-manager
- 不自动生成自签名证书
- 不实现 storage version 自动迁移
- 不支持运行时 YAML 配置热重载
- 不支持 leader election / metrics port / watched namespace 热重载
- 不添加 webhook metrics/tracing/通用测试框架
- 不将 webhook server 与 metrics/health 共用端口

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** - ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES（已存在的 JUnit 5 + Mockito）
- **Automated tests**: YES (Tests-after)
- **Framework**: JUnit 5 + Mockito
- **Integration tests**: NO

### QA Policy
Every task MUST include agent-executed QA scenarios. Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Maven/Java**: Bash (`mvn`, `java`)
- **Helm**: Bash (`helm template`, `helm lint`)
- **Kubernetes local**: Bash (`kind` / `kubectl` if cluster available)
- **Webhook**: Bash (`curl` with TLS skip / custom CA)

---

## Execution Strategy

### Parallel Execution Waves

```
Phase 1: Admission Webhooks
├── T1: TLS WebhookServer (JDK HttpsServer, default port 8443)
├── T2: AdmissionValidator / AdmissionMutator interfaces + admission handler
├── T3: WebhookSelfRegistration (Validating/MutatingWebhookConfiguration)
├── T4: TLS cert reload on file change
├── T5: SDK unit tests for webhook components
└── T6: Echo Operator webhook example (validating + mutating)

Phase 2: Multi-version CRD
├── T7: EchoResource v1alpha2 + hub/spoke converter
├── T8: CRD generator multi-version config + Helm CRD patch
├── T9: ConversionWebhookHandler + /convert endpoint
├── T10: Wire conversion into WebhookServer
├── T11: SDK unit tests for conversion
└── T12: Helm chart: Service, RBAC

Phase 3: Documentation + integration
├── T13: Update SDK README + dev-guide (CN/EN)
├── T14: Update Echo README (CN/EN)
├── T15: Helm lint/template/render verification
└── T16: Smoke test: webhook + conversion

Wave FINAL (4 parallel reviews, then user okay):
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
├── F3: Real manual QA (unspecified-high)
└── F4: Scope fidelity check (deep)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| T1 | - | T2, T3, T4, T6, T10 |
| T2 | T1 | T3, T5, T6 |
| T3 | T1, T2 | T6, T16 |
| T4 | T1 | T6, T16 |
| T5 | T1, T2 | F1-F4 |
| T6 | T1, T2, T3, T4 | T16, F1-F4 |
| T7 | - | T8, T9, T11, T12 |
| T8 | T7 | T12, T16 |
| T9 | T7 | T10, T11 |
| T10 | T1, T9 | T12, T16 |
| T11 | T7, T9, T10 | F1-F4 |
| T12 | T7, T8, T10 | T16 |
| T13 | T1-T12 | F1-F4 |
| T14 | T6, T12 | F1-F4 |
| T15 | T12 | F1-F4 |
| T16 | T6, T12, T15 | F1-F4 |

### Agent Dispatch Summary

- **Phase 1**: 6 tasks → `deep` / `unspecified-high`
- **Phase 2**: 6 tasks → `deep` / `unspecified-high`
- **Phase 3**: 4 tasks → `writing` / `unspecified-high`
- **FINAL**: 4 tasks → `oracle`, `unspecified-high`, `unspecified-high`, `deep`

---

## TODOs

- [x] 1. TLS WebhookServer (JDK HttpsServer)

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.webhook.WebhookServer`
  - Use `com.sun.net.httpserver.HttpsServer` on default port 8443
  - Accept TLS cert/key file paths or `KeyStore` supplier
  - Register contexts: `/validate/{name}`, `/mutate/{name}`, `/convert`
  - Support graceful start/stop
  - Provide cert reload: rebuild SSL context when files change

  **Must NOT do**:
  - Do NOT use external web frameworks
  - Do NOT bind to port 8080 (metrics port)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 1
  - **Blocks**: T2, T3, T4, T6, T10
  - **Blocked By**: None

  **References**:
  - `MetricsHealthServer.java` - existing HttpServer pattern
  - JDK `HttpsServer` docs

  **Acceptance Criteria**:
  - [ ] `WebhookServer` starts on 8443 with TLS
  - [ ] Unit test: HTTPS request to /healthz-like path returns 200
  - [ ] Cert reload test

  **QA Scenarios**:

  ```
  Scenario: Webhook server serves HTTPS
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=WebhookServerTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-1-webhook-server.log
  ```

  **Commit**: YES

- [x] 2. AdmissionValidator / AdmissionMutator interfaces + handler

  **What to do**:
  - Create `AdmissionValidator<T extends HasMetadata>` interface: `AdmissionResponse validate(AdmissionRequest request, T resource)`
  - Create `AdmissionMutator<T extends HasMetadata>` interface: `AdmissionResponse mutate(AdmissionRequest request, T resource)` returning JSON patch
  - Create `AdmissionHandler` that deserializes `AdmissionReview`, dispatches to validator/mutator, serializes response
  - Use fabric8 `AdmissionReviewBuilder` / `AdmissionResponseBuilder`
  - Register handlers per resource type and path

  **Must NOT do**:
  - Do NOT support admissionregistration v1beta1
  - Do NOT add generic middleware framework

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Phase 1
  - **Blocks**: T3, T5, T6
  - **Blocked By**: T1

  **References**:
  - fabric8 `admission.v1.*` model classes
  - RFC 6902 JSON Patch

  **Acceptance Criteria**:
  - [ ] Interfaces exist
  - [ ] Handler returns correct `AdmissionReview` response
  - [ ] Mutating handler produces base64 JSON patch

  **QA Scenarios**:

  ```
  Scenario: Admission validator rejects invalid resource
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=AdmissionHandlerTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-2-admission-handler.log
  ```

  **Commit**: YES

- [x] 3. WebhookSelfRegistration

  **What to do**:
  - Create `WebhookSelfRegistration` that uses fabric8 client to create/update `ValidatingWebhookConfiguration` and `MutatingWebhookConfiguration`
  - Read CA bundle from file (ca.crt)
  - Build `WebhookClientConfig` pointing to Service
  - Support configurable failurePolicy (default `Fail`), timeoutSeconds, rules, namespaceSelector, objectSelector
  - Fail-fast on permission error or missing CA bundle
  - Add ownerReference or label for cleanup if desired

  **Must NOT do**:
  - Do NOT retry indefinitely
  - Do NOT fallback to Ignore without explicit config

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Phase 1
  - **Blocks**: T6, T16
  - **Blocked By**: T1, T2

  **References**:
  - fabric8 `admissionregistration.v1.*` model/DSL
  - `LeaderElectionManager.java` - config builder pattern

  **Acceptance Criteria**:
  - [ ] Self-registration creates/updates webhook configs with correct fields
  - [ ] Unit test with mock client verifies DSL calls
  - [ ] Missing CA bundle throws IllegalStateException

  **QA Scenarios**:

  ```
  Scenario: Self-registration builds webhook configuration
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=WebhookSelfRegistrationTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-3-self-registration.log
  ```

  **Commit**: YES

- [x] 4. TLS cert reload on file change

  **What to do**:
  - Extend `WebhookServer` with cert file watcher
  - Watch `tls.crt`, `tls.key`, `ca.crt` for changes
  - Rebuild `SSLContext` / `KeyManager` / `TrustManager` atomically
  - Do not drop in-flight requests
  - Use `ReloadableSslContext` abstraction

  **Must NOT do**:
  - Do NOT require pod restart for cert rotation
  - Do NOT leave server in broken state if new cert is invalid

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 1
  - **Blocks**: T6, T16
  - **Blocked By**: T1

  **References**:
  - JDK `SSLContext`, `KeyManagerFactory`, `TrustManagerFactory`

  **Acceptance Criteria**:
  - [ ] Cert reload succeeds without restart
  - [ ] Unit test: new cert is used after file change

  **QA Scenarios**:

  ```
  Scenario: TLS cert reload
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=TlsCertReloadTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-4-cert-reload.log
  ```

  **Commit**: YES

- [x] 5. SDK webhook unit tests

  **What to do**:
  - Test `WebhookServer`, `AdmissionHandler`, `WebhookSelfRegistration`, cert reload with mocks and local HttpsServer
  - Aim >70% coverage on new SDK classes

  **Must NOT do**:
  - Do NOT add integration tests requiring real cluster

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 1
  - **Blocks**: F1-F4
  - **Blocked By**: T1, T2, T3, T4

  **References**:
  - Existing test patterns in `operator/framework/src/test/java/`

  **Acceptance Criteria**:
  - [ ] All new SDK webhook tests pass
  - [ ] `mvn -f operator/framework/pom.xml test` passes

  **QA Scenarios**:

  ```
  Scenario: Full SDK test suite passes
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test`
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-5-sdk-webhook-tests.log
  ```

  **Commit**: YES

- [x] 6. Echo Operator webhook example

  **What to do**:
  - Create `EchoValidatingWebhook`: reject if `spec.message` is blank or `spec.replicas < 0`
  - Create `EchoMutatingWebhook`: add default `message` if blank, inject annotation `echo.example.com/mutated: "true"`
  - Register webhooks in `EchoOperatorMain`
  - Add self-registration call
  - Update `application.properties` / env defaults

  **Must NOT do**:
  - Do NOT add business logic beyond validation/mutation examples
  - Do NOT modify Reconciler behavior

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Phase 1
  - **Blocks**: T16, F1-F4
  - **Blocked By**: T1, T2, T3, T4

  **References**:
  - `EchoReconciler.java`
  - `EchoOperatorMain.java`
  - `EchoResource.java`

  **Acceptance Criteria**:
  - [ ] Validating webhook rejects invalid Echo CR
  - [ ] Mutating webhook injects default/annotation
  - [ ] Unit tests pass

  **QA Scenarios**:

  ```
  Scenario: Echo validating webhook rejects blank message
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoValidatingWebhookTest`
    Expected Result: Test passes
    Evidence: .sisyphus/evidence/task-6-echo-validating.log
  ```

  **Commit**: YES

- [x] 7. EchoResource v1alpha2 + hub/spoke converter

  **What to do**:
  - Create `example/echo-operator/src/main/java/.../api/v1alpha2/EchoResource.java` with `@Version(value="v1alpha2", storage=true, served=true)`
  - Create `EchoSpec` / `EchoStatus` v1alpha2 with evolved schema (e.g., add `image` field, rename `message` to `greeting` with conversion)
  - Keep `api/v1alpha1/` classes as deprecated (`@Version(value="v1alpha1", storage=false, served=true, deprecated=true)`)
  - Create `EchoConverter` implementing hub-and-spoke conversion between v1alpha1 and v1alpha2
  - Storage version is v1alpha2

  **Must NOT do**:
  - Do NOT change existing v1alpha1 behavior beyond deprecation
  - Do NOT implement n-way conversion

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 2
  - **Blocks**: T8, T9, T11, T12
  - **Blocked By**: None

  **References**:
  - `example/echo-operator/src/main/java/.../api/v1alpha1/EchoResource.java`
  - Fabric8 `@Version` annotation docs

  **Acceptance Criteria**:
  - [ ] v1alpha2 classes exist
  - [ ] v1alpha1 marked deprecated
  - [ ] Converter round-trips correctly

  **QA Scenarios**:

  ```
  Scenario: Hub-and-spoke converter round-trips
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoConverterTest`
    Expected Result: Test passes
    Evidence: .sisyphus/evidence/task-7-converter.log
  ```

  **Commit**: YES

- [x] 8. CRD generator multi-version + Helm CRD patch

  **What to do**:
  - Configure `crd-generator-maven-plugin` to include both v1alpha1 and v1alpha2 classes
  - Verify generated CRD contains both versions
  - Patch `example/echo-operator/helm/echo-operator/templates/crd.yaml` (or use `crds/`) to add:
    - `spec.conversion.strategy: Webhook`
    - `spec.conversion.webhook.conversionReviewVersions: ["v1"]`
    - `spec.conversion.webhook.clientConfig.service` pointing to webhook service
    - `spec.conversion.webhook.clientConfig.caBundle` from values
  - Ensure `preserveUnknownFields: false`

  **Must NOT do**:
  - Do NOT expect CRD generator to emit conversion block automatically
  - Do NOT use v1beta1 admission API

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Phase 2
  - **Blocks**: T12, T16
  - **Blocked By**: T7

  **References**:
  - `example/echo-operator/pom.xml`
  - `example/echo-operator/helm/echo-operator/templates/crd.yaml`
  - Kubernetes CRD conversion docs

  **Acceptance Criteria**:
  - [ ] Generated CRD contains both versions
  - [ ] Helm CRD includes conversion webhook block
  - [ ] `helm lint` passes

  **QA Scenarios**:

  ```
  Scenario: Multi-version CRD renders with conversion
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml clean compile`
      2. Run `helm template echo-operator example/echo-operator/helm/echo-operator | grep -A 10 conversion`
    Expected Result: Output contains conversion.webhook block
    Evidence: .sisyphus/evidence/task-8-crd-conversion.log
  ```

  **Commit**: YES

- [x] 9. ConversionWebhookHandler + /convert endpoint

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.webhook.conversion.ConversionWebhookHandler`
  - Interface: `ConversionReview handle(ConversionReview review)`
  - Create `ConversionResult` to wrap converted object + error
  - Use fabric8 `apiextensions.v1.ConversionReview` model
  - Register `/convert` context on `WebhookServer`
  - Dispatch by `request.desiredVersion` and `request.objects[].apiVersion`

  **Must NOT do**:
  - Do NOT implement generic conversion framework beyond hub-and-spoke
  - Do NOT return HTTP 500 on conversion failure

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 2
  - **Blocks**: T10, T11
  - **Blocked By**: T7

  **References**:
  - fabric8 `apiextensions.v1.ConversionReview` model
  - Kubernetes conversion webhook docs

  **Acceptance Criteria**:
  - [ ] Handler interface exists
  - [ ] `/convert` endpoint registered
  - [ ] Unit test: v1alpha1 → v1alpha2 conversion

  **QA Scenarios**:

  ```
  Scenario: Conversion handler converts between versions
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ConversionWebhookHandlerTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-9-conversion-handler.log
  ```

  **Commit**: YES

- [x] 10. Wire conversion into WebhookServer

  **What to do**:
  - Register `EchoConverter` as conversion handler in `EchoOperatorMain`
  - Ensure `/convert` path is served by the same TLS `WebhookServer` as admission webhooks
  - Update `WebhookServer` to support multiple handler registrations per path

  **Must NOT do**:
  - Do NOT create separate server for conversion
  - Do NOT break existing admission paths

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Phase 2
  - **Blocks**: T12, T16
  - **Blocked By**: T1, T9

  **References**:
  - `WebhookServer.java`
  - `EchoOperatorMain.java`

  **Acceptance Criteria**:
  - [ ] Echo Operator registers conversion handler
  - [ ] Same TLS server serves `/validate`, `/mutate`, `/convert`

  **QA Scenarios**:

  ```
  Scenario: Echo conversion endpoint registered
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoConversionEndpointTest`
    Expected Result: Test passes
    Evidence: .sisyphus/evidence/task-10-echo-conversion.log
  ```

  **Commit**: YES

- [x] 11. SDK conversion unit tests

  **What to do**:
  - Test `ConversionWebhookHandler` dispatch and error handling
  - Test Echo converter round-trip
  - Test invalid desiredVersion returns failed ConversionResponse

  **Must NOT do**:
  - Do NOT add integration tests requiring real cluster

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 2
  - **Blocks**: F1-F4
  - **Blocked By**: T7, T9, T10

  **References**:
  - Existing SDK test patterns

  **Acceptance Criteria**:
  - [ ] All conversion tests pass
  - [ ] Error cases return proper ConversionReview failed status

  **QA Scenarios**:

  ```
  Scenario: Conversion error handled gracefully
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ConversionWebhookHandlerTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-11-conversion-tests.log
  ```

  **Commit**: YES

- [x] 12. Helm chart: Service, RBAC

  **What to do**:
  - Add `templates/webhook-service.yaml` (port 8443)
  - Add `templates/webhook-clusterrole.yaml` + `templates/webhook-clusterrolebinding.yaml` for admissionregistration and CRD permissions
  - Add `templates/validatingwebhookconfiguration.yaml` and `templates/mutatingwebhookconfiguration.yaml` with caBundle from values
  - Update `templates/deployment.yaml`: mount TLS Secret, expose webhook port
  - Update `values.yaml`: webhook enabled, service, cert paths

  **Must NOT do**:
  - Do NOT use cert-manager
  - Do NOT create cluster-scoped resources unless webhook enabled

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Phase 2
  - **Blocks**: T15, T16
  - **Blocked By**: T7, T8, T10

  **References**:
  - Existing Helm chart files
  - Kubernetes admission webhook docs

  **Acceptance Criteria**:
  - [ ] `helm lint` passes
  - [ ] `helm template` contains all webhook resources
  - [ ] Deployment mounts TLS Secret

  **QA Scenarios**:

  ```
  Scenario: Helm chart renders webhook resources
    Tool: Bash
    Steps:
      1. Run `helm lint example/echo-operator/helm/echo-operator`
      2. Run `helm template echo-operator example/echo-operator/helm/echo-operator > /tmp/rendered.yaml`
      3. grep for ValidatingWebhookConfiguration, MutatingWebhookConfiguration, Service port 8443, Secret volume mount
    Expected Result: All present
    Evidence: .sisyphus/evidence/task-12-helm-webhook.log
  ```

  **Commit**: YES

- [x] 13. Update SDK README + dev-guide (CN/EN)

  **What to do**:
  - Document webhook registration and self-registration
  - Document multi-version CRD and conversion webhook
  - Update build/test instructions

  **Must NOT do**:
  - Do NOT write documentation-only deliverables as substitutes for tests

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 3
  - **Blocks**: F1-F4
  - **Blocked By**: T1-T12

  **References**:
  - All new SDK classes

  **Acceptance Criteria**:
  - [x] `operator/framework/README.md` and `README.zh-CN.md` updated
  - [x] `docs/dev-guide.md` and `docs/dev-guide.zh-CN.md` updated

  **QA Scenarios**:

  ```
  Scenario: Documentation files exist and mention new features
    Tool: Bash
    Steps:
      1. grep -n "Webhook" operator/framework/README.md docs/dev-guide.md
    Expected Result: Sections exist
    Evidence: .sisyphus/evidence/task-13-docs.log
  ```

  **Commit**: YES

- [x] 14. Update Echo README (CN/EN)

  **What to do**:
  - Document how to build/deploy with webhooks enabled
  - Document how to create TLS Secret
  - Document multi-version CR example

  **Must NOT do**:
  - Do NOT duplicate SDK dev-guide content

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 3
  - **Blocks**: F1-F4
  - **Blocked By**: T6, T12

  **References**:
  - Echo example classes

  **Acceptance Criteria**:
  - [x] `example/echo-operator/README.md` and `README.zh-CN.md` updated

  **QA Scenarios**:

  ```
  Scenario: Echo README mentions webhook and conversion examples
    Tool: Bash
    Steps:
      1. grep -n "webhook" example/echo-operator/README.md
      2. grep -n "v1alpha2" example/echo-operator/README.md
    Expected Result: Sections exist
    Evidence: .sisyphus/evidence/task-14-echo-readme.log
  ```

  **Commit**: YES

- [x] 15. Helm lint/template/render verification

  **What to do**:
  - Run `helm lint`
  - Run `helm template` and verify all resources
  - Validate webhook configs reference correct service path

  **Must NOT do**:
  - Do NOT skip RBAC verification

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Phase 3
  - **Blocks**: T16, F1-F4
  - **Blocked By**: T12

  **References**:
  - Helm chart templates

  **Acceptance Criteria**:
  - [x] `helm lint` passes
  - [x] Rendered YAML contains all expected resources

  **QA Scenarios**:

  ```
  Scenario: Helm chart passes lint and renders webhook resources
    Tool: Bash
    Steps:
      1. Run `helm lint example/echo-operator/helm/echo-operator`
      2. Run `helm template echo-operator example/echo-operator/helm/echo-operator > /tmp/echo-rendered.yaml`
    Expected Result: lint exits 0, rendered YAML contains webhook resources
    Evidence: .sisyphus/evidence/task-15-helm-verify.log
  ```

  **Commit**: YES

- [x] 16. Smoke test: webhook + conversion

  **What to do**:
  - Update `smoke-test.sh` to optionally enable webhooks
  - If cluster available: deploy with webhooks, apply invalid CR and assert rejection, apply v1alpha1 CR and read as v1alpha2
  - If no cluster: validate script syntax and skip gracefully

  **Must NOT do**:
  - Do NOT fail build if no cluster

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Phase 3
  - **Blocks**: F1-F4
  - **Blocked By**: T6, T12, T15

  **References**:
  - Existing `smoke-test.sh`

  **Acceptance Criteria**:
  - [x] `smoke-test.sh` syntax valid
  - [x] Optional cluster tests run if available

  **QA Scenarios**:

  ```
  Scenario: Smoke test script updated
    Tool: Bash
    Steps:
      1. Run `bash -n example/echo-operator/scripts/smoke-test.sh`
    Expected Result: Syntax check passes
    Evidence: .sisyphus/evidence/task-16-smoke-test.log
  ```

  **Commit**: YES

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists. For each "Must NOT Have": search codebase for forbidden patterns. Check evidence files exist.
  Output: `Must Have [7/7] | Must NOT Have [7/7] | Tasks [16/16] | Evidence [16/16] | VERDICT: APPROVE`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `mvn clean install` for SDK and example. Review for anti-patterns.
  Output: `Build [PASS] | Files [5 clean/0 issues] | VERDICT: PASS`

- [x] F3. **Real Manual QA** — `unspecified-high`
  Execute every QA scenario, test integration, edge cases.
  Output: `Scenarios [16/16 pass] | Integration [4/4] | Edge Cases [6 tested] | VERDICT: APPROVE`

- [x] F4. **Scope Fidelity Check** — `deep`
  Verify 1:1 implementation against spec, no scope creep.
  Output: `Tasks [16/16 compliant] | Contamination [CLEAN] | Unaccounted [CLEAN] | VERDICT: APPROVE`

---

## Commit Strategy

- **Phase 1**: `feat(webhook): add TLS webhook server and admission self-registration`
- **Phase 2**: `feat(crd): add multi-version CRD and conversion webhook support`
- **Phase 3**: `docs: update README, dev-guide, and Helm chart for advanced features`
- **FINAL**: `chore(release): final review and smoke test`

---

## Success Criteria

### Verification Commands
```bash
# SDK builds and installs
mvn -f operator/framework/pom.xml clean install
# Expected: BUILD SUCCESS

# Example builds
mvn -f example/echo-operator/pom.xml clean package
# Expected: BUILD SUCCESS

# Helm chart renders with webhooks
helm template echo-operator example/echo-operator/helm/echo-operator
# Expected: contains ValidatingWebhookConfiguration, MutatingWebhookConfiguration, webhook Service

# Helm lint passes
helm lint example/echo-operator/helm/echo-operator
# Expected: 0 chart(s) failed
```

### Final Checklist
- [x] All "Must Have" present
- [x] All "Must NOT Have" absent
- [x] All tests pass
- [x] Helm chart renders and lints
- [x] Documentation updated (Chinese + English)
- [x] Evidence files saved to `.sisyphus/evidence/`
