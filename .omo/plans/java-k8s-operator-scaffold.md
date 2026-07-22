# Java K8s Operator 开发脚手架

## TL;DR

> **Quick Summary**: 构建一个基于纯 fabric8 的 Java Kubernetes Operator SDK 核心库（`operator-framework`），并配套一个独立的 Echo Operator 示例项目，演示 CRD 双向生成、Reconciler、Informer、Leader Election、Metrics/Health、Finalizer、OwnerReference 等生产级 Operator 模式。
>
> **Deliverables**:
> - `operator/framework/` - SDK 核心库（Maven 项目）
> - `example/echo-operator/` - Echo Operator 示例（Maven 项目，独立仓库式结构）
> - `example/echo-operator/helm/echo-operator/` - Helm chart
> - `example/echo-operator/Dockerfile` - 容器镜像
> - Shell scripts for build/deploy/local-run
> - 中英文 README + 开发文档
>
> **Estimated Effort**: Large
> **Parallel Execution**: YES - 4 waves
> **Critical Path**: T1 (scaffold) → T3/T4 (SDK API core) → T9 (Echo example wiring) → T12 (Helm + deploy scripts) → F1-F4 (final review) → user okay

---

## Context

### Original Request
开发一个 Java 的 Kubernetes Operator 开发脚手架，client 使用 fabric8。先分析需求以及脚手架需要具备的功能。

### Interview Summary
**Key Discussions**:
- **框架层**: 使用纯 fabric8 低层实现，不引入 Java Operator SDK (JOSDK)
- **运行时**: 纯 Maven Java 项目，Java 21
- **CRD 生成**: 双向支持 - Java class → CRD YAML，以及 CRD YAML → Java class
- **交付形式**: SDK 核心库 + 独立示例项目（example 不放在 SDK 项目内）
- **部署模板**: Helm chart
- **可观测/高可用**: Metrics (Micrometer + Prometheus) + Health probes + Leader election
- **镜像构建**: Dockerfile
- **测试**: example 项目仅保留单元测试
- **自动化脚本**: Shell 脚本
- **CI**: 不需要

### Research Findings
- fabric8 `crd-generator-maven-plugin`（v2 API）可从 `CustomResource` 子类生成 CRD YAML，输出到 `target/classes/META-INF/fabric8/`
- fabric8 `java-generator-maven-plugin` 可从 CRD YAML 生成 Java POJO
- 旧版 `crd-generator-apt` 已弃用
- 生产级 Operator 需要 informer、leader election、status update、owner reference、finalizer、metrics、health probes

### Metis Review
**Identified Gaps** (addressed):
- SDK 模块结构 → 默认单模块 Maven 项目
- Example 消费 SDK 方式 → 通过 `mvn install` 安装到本地仓库
- CRD 版本名 → 默认 `v1alpha1`
- CRD 生命周期 → Helm chart 包含 CRD，手动 `helm install` 时自动 apply
- HTTP server → 默认 JDK `com.sun.net.httpserver.HttpServer`
- Resync 间隔 → 默认 60s
- Retry 策略 → 默认指数退避，最大 5 次
- 文档范围 → README + dev-guide，中英文
- Scope 边界 → 明确排除 webhooks、OLM、多版本 CRD、通用 CLI 脚手架、observability dashboards、authn/authz、config reload

---

## Work Objectives

### Core Objective
实现一个可复用的、基于 fabric8 的 Java Operator 开发 SDK，并通过 Echo Operator 示例验证其可用性。

### Concrete Deliverables
- `operator/framework/pom.xml` - SDK Maven 项目
- `operator/framework/src/main/java/.../operator/framework/*.java` - SDK API 实现
- `operator/framework/src/test/java/...` - SDK 单元测试
- `example/echo-operator/pom.xml` - Echo Operator Maven 项目
- `example/echo-operator/src/main/java/.../echooperator/` - Echo CRD / Reconciler / Main
- `example/echo-operator/src/test/java/...` - Echo 单元测试
- `example/echo-operator/Dockerfile`
- `example/echo-operator/helm/echo-operator/` - Helm chart
- `example/echo-operator/scripts/` - build.sh / deploy.sh / undeploy.sh / local-run.sh
- `operator/framework/README.md` + `README.zh-CN.md`
- `example/echo-operator/README.md` + `README.zh-CN.md`
- `docs/dev-guide.md` + `docs/dev-guide.zh-CN.md`

### Definition of Done
- [ ] SDK `mvn clean install` 成功
- [ ] Example `mvn clean package` 成功（依赖本地 SDK）
- [ ] Docker image `example/echo-operator:latest` 构建成功
- [ ] Helm chart `helm template` 渲染成功
- [ ] Operator 本地启动后 `/healthz`、`/readyz`、`/metrics` 可访问
- [ ] Echo CR apply 后触发 reconcile，创建 Deployment，更新 status

### Must Have
- 纯 fabric8，无 JOSDK/Quarkus/Spring Boot
- SDK 提供 Operator 启动器、Reconciler 接口、Informer 封装、Leader Election、Metrics/Health Server、Retry/Rate limiter、OwnerReference helper
- 双向 CRD 生成支持
- Echo Operator 示例演示 Finalizer、OwnerReference、Status update、错误重试
- Helm chart + Dockerfile
- 中英文 README + dev-guide
- Shell 脚本自动化 build/deploy/local-run

### Must NOT Have (Guardrails)
- 不实现 Admission Webhooks
- 不实现 OLM bundle
- 不实现通用 CLI 脚手架工具
- 不实现多版本 CRD
- 不实现 Grafana dashboards / alerts
- 不实现 authn/authz 层
- 不实现运行时配置热重载
- 不引入 Quarkus / Spring Boot
- SDK 不加集成测试（仅单元测试 + mocks）

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** - ALL verification is agent-executed. No exceptions.
> Acceptance criteria requiring "user manually tests/confirms" are FORBIDDEN.

### Test Decision
- **Infrastructure exists**: NO（工作目录为空）
- **Automated tests**: YES (Tests-after)
- **Framework**: JUnit 5 + Mockito
- **Integration tests**: NO（仅单元测试）

### QA Policy
Every task MUST include agent-executed QA scenarios (see TODO template below).
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **Maven/Java**: Use Bash (`mvn`, `java`, `docker`, `helm`)
- **API/HTTP**: Use Bash (`curl`)
- **Container**: Use Bash (`docker build`, `docker run`)
- **Helm**: Use Bash (`helm template`, `helm lint`)
- **Kubernetes local**: Use Bash (`kind` or `kubectl` against available cluster)

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation - scaffolding + core dependencies):
├── T1: Create SDK Maven project + fabric8 dependencies
├── T2: Create Echo Operator Maven project + SDK dependency
├── T3: SDK - Reconciler + ResourceEventSource (Informer) wrapper
├── T4: SDK - Operator launcher + controller registration
└── T5: SDK - OwnerReference helper + finalizer utilities

Wave 2 (Core runtime - MAX PARALLEL):
├── T6: SDK - Leader election manager
├── T7: SDK - Retry / rate limiter
├── T8: SDK - Metrics server (Micrometer + Prometheus)
├── T9: SDK - Health server (/healthz /readyz)
└── T10: SDK - Unit tests for SDK APIs

Wave 3 (Example + CRD + Docker + Helm):
├── T11: Echo Operator - CRD Java classes (Spec/Status/Resource)
├── T12: Echo Operator - Reconciler + Deployment creation
├── T13: Echo Operator - Finalizer + OwnerReference + Status update
├── T14: Echo Operator - Main class + local-run script
├── T15: Echo Operator - Dockerfile + image build script
├── T16: Echo Operator - Helm chart
└── T17: Echo Operator - Unit tests

Wave 4 (Documentation + scripts + integration):
├── T18: Shell scripts - build/deploy/undeploy/local-run
├── T19: README + dev-guide (Chinese + English)
└── T20: End-to-end local smoke test

Wave FINAL (4 parallel reviews, then user okay):
├── F1: Plan compliance audit (oracle)
├── F2: Code quality review (unspecified-high)
├── F3: Real manual QA (unspecified-high)
└── F4: Scope fidelity check (deep)
-> Present results -> Get explicit user okay

Critical Path: T1 → T3/T4 → T6/T7/T8/T9 → T11/T12/T13/T14 → T15/T16/T18 → T20 → F1-F4 → user okay
Parallel Speedup: ~60% faster than sequential
Max Concurrent: 5 (Wave 2)
```

### Dependency Matrix

| Task | Depends On | Blocks |
|------|-----------|--------|
| T1 | - | T2, T3, T4, T5, T6, T7, T8, T9, T10 |
| T2 | T1 | T11, T12, T13, T14, T15, T17 |
| T3 | T1 | T4, T10, T20 |
| T4 | T1, T3 | T14, T20 |
| T5 | T1 | T13, T20 |
| T6 | T1 | T14, T20 |
| T7 | T1 | T12, T13, T20 |
| T8 | T1 | T14, T20 |
| T9 | T1, T8 | T14, T20 |
| T10 | T1, T3, T4, T5, T6, T7, T8, T9 | F1-F4 |
| T11 | T2 | T12, T13, T17 |
| T12 | T2, T7, T11 | T13, T17, T20 |
| T13 | T2, T5, T7, T11, T12 | T17, T20 |
| T14 | T2, T4, T6, T8, T9 | T15, T18, T20 |
| T15 | T2, T14 | T18, T20 |
| T16 | T2 | T18, T20 |
| T17 | T2, T11, T12, T13 | F1-F4 |
| T18 | T14, T15, T16 | T20 |
| T19 | T18 | F1-F4 |
| T20 | T4, T6, T7, T8, T9, T13, T14, T15, T16, T18 | F1-F4 |

### Agent Dispatch Summary

- **Wave 1**: 5 tasks → `quick` / `unspecified-high`
- **Wave 2**: 5 tasks → `deep` / `unspecified-high`
- **Wave 3**: 7 tasks → `unspecified-high` / `quick`
- **Wave 4**: 3 tasks → `writing` / `unspecified-high`
- **FINAL**: 4 tasks → `oracle`, `unspecified-high`, `unspecified-high`, `deep`

---

## TODOs

- [x] 1. Create SDK Maven project `operator/framework`

  **What to do**:
  - Create directory `operator/framework/`
  - Create `pom.xml` with:
    - `groupId=com.huawei.dcs.modelengine`, `artifactId=operator-framework`, `version=0.1.0-SNAPSHOT`
    - Java 21 source/target
    - Fabric8 `kubernetes-client` dependency (pin latest stable, e.g., 6.13.4 or 7.x — verify latest compatible with Java 21)
    - Fabric8 `crd-generator-maven-plugin` v2 for Java → CRD generation
    - Fabric8 `java-generator-maven-plugin` for CRD → Java generation
    - `micrometer-registry-prometheus` for metrics
    - JUnit 5 + Mockito for tests
    - Maven compiler plugin configured for annotation processors
  - Create standard `src/main/java` and `src/test/java` directories
  - Add `.gitignore` for Java/Maven

  **Must NOT do**:
  - Do NOT add JOSDK, Quarkus, Spring Boot, or any other operator framework
  - Do NOT add example code into the SDK project

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T2, T3, T4, T5, T6, T7, T8, T9, T10
  - **Blocked By**: None

  **References**:
  - `https://github.com/fabric8io/kubernetes-client/blob/main/doc/CRD-generator.md` - CRD generator v2 Maven plugin setup
  - `https://github.com/fabric8io/kubernetes-client/blob/main/doc/java-generation-from-CRD.md` - Java generator Maven plugin setup

  **Acceptance Criteria**:
  - [ ] `operator/framework/pom.xml` exists and declares all dependencies/plugins
  - [ ] `mvn -f operator/framework/pom.xml clean install` completes with BUILD SUCCESS
  - [ ] `~/.m2/repository/com/huawei/dcs/modelengine/operator-framework/0.1.0-SNAPSHOT/operator-framework-0.1.0-SNAPSHOT.jar` exists

  **QA Scenarios**:

  ```
  Scenario: SDK project compiles and installs
    Tool: Bash
    Preconditions: Empty workspace, Maven available
    Steps:
      1. Run `mvn -f operator/framework/pom.xml clean install -DskipTests`
    Expected Result: Command exits 0, console contains "BUILD SUCCESS"
    Failure Indicators: Non-zero exit, missing dependency, compilation error
    Evidence: .sisyphus/evidence/task-1-mvn-install.log
  ```

  **Evidence to Capture**:
  - [ ] task-1-mvn-install.log

  **Commit**: YES (Wave 1)

- [x] 2. Create Echo Operator Maven project `example/echo-operator`

  **What to do**:
  - Create directory `example/echo-operator/`
  - Create `pom.xml` with:
    - `groupId=com.example`, `artifactId=echo-operator`, `version=0.1.0-SNAPSHOT`
    - Java 21
    - Dependency on local SDK `com.huawei.dcs.modelengine:operator-framework:0.1.0-SNAPSHOT`
    - Fabric8 `kubernetes-client` dependency (same version as SDK)
    - `crd-generator-maven-plugin` v2 to generate CRD from Echo CR Java classes
    - JUnit 5 + Mockito
  - Create `src/main/java/com/example/echooperator/` package structure
  - Create `src/test/java/com/example/echooperator/` package structure
  - Add `.gitignore`

  **Must NOT do**:
  - Do NOT put example inside SDK project
  - Do NOT add Quarkus/Spring Boot

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T11, T12, T13, T14, T15, T17
  - **Blocked By**: T1 (SDK pom must exist and install first)

  **References**:
  - `operator/framework/pom.xml` - copy dependency versions from SDK

  **Acceptance Criteria**:
  - [ ] `example/echo-operator/pom.xml` exists
  - [ ] After SDK installed, `mvn -f example/echo-operator/pom.xml clean compile` completes with BUILD SUCCESS

  **QA Scenarios**:

  ```
  Scenario: Example project compiles against local SDK
    Tool: Bash
    Preconditions: T1 completed and SDK installed to local repo
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml clean compile -DskipTests`
    Expected Result: Command exits 0, console contains "BUILD SUCCESS"
    Evidence: .sisyphus/evidence/task-2-example-compile.log
  ```

  **Evidence to Capture**:
  - [ ] task-2-example-compile.log

  **Commit**: YES (Wave 1)

- [x] 3. SDK - Reconciler interface + ResourceEventSource (Informer) wrapper

  **What to do**:
  - Define `com.huawei.dcs.modelengine.operator.framework.reconciler.Reconciler<T extends HasMetadata>` interface with:
    - `Result reconcile(Request request, T resource)`
    - `Result` contains `requeue` (boolean), `requeueAfter` (Duration optional), `error` (Throwable optional)
    - `Request` contains namespace + name
  - Define `com.huawei.dcs.modelengine.operator.framework.source.ResourceEventSource<T>` that wraps fabric8 `SharedIndexInformer<T>`
    - Registers add/update/delete handlers
    - Enqueues `Request` to a work queue on events
    - Supports optional resync interval (default 60s)
    - Uses `BlockingQueue<Request>` as internal work queue
  - Use `KubernetesClient.informers().sharedIndexInformerFor(Class<T>, long resyncPeriod)`

  **Must NOT do**:
  - Do NOT implement actual reconcile logic here (that's user's job)
  - Do NOT depend on JOSDK classes

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T4, T10, T20
  - **Blocked By**: T1

  **References**:
  - `https://github.com/fabric8io/kubernetes-client/blob/main/kubernetes-examples/src/main/java/io/fabric8/kubernetes/examples/CustomResourceInformerExample.java` - informer usage pattern
  - `https://github.com/fabric8io/kubernetes-client/blob/main/doc/KubernetesOperatorsInJavaWrittenUsingFabric8.md` - operator patterns

  **Acceptance Criteria**:
  - [ ] `Reconciler.java`, `Result.java`, `Request.java`, `ResourceEventSource.java` exist
  - [ ] `ResourceEventSource` can be instantiated with a mock `KubernetesClient` and starts informer
  - [ ] Unit test: enqueued request count matches number of resource events

  **QA Scenarios**:

  ```
  Scenario: ResourceEventSource enqueues requests on resource events
    Tool: Bash
    Preconditions: T1 completed
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ResourceEventSourceTest`
    Expected Result: Tests pass, console shows "BUILD SUCCESS"
    Evidence: .sisyphus/evidence/task-3-resource-event-source-test.log

  Scenario: Reconciler interface can be implemented by user code
    Tool: Bash
    Preconditions: T1 completed
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ReconcilerInterfaceTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-3-reconciler-interface-test.log
  ```

  **Evidence to Capture**:
  - [ ] task-3-resource-event-source-test.log
  - [ ] task-3-reconciler-interface-test.log

  **Commit**: YES (Wave 1)

- [x] 4. SDK - Operator launcher + controller registration

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.Operator` class:
    - `register(Class<T> resourceClass, Reconciler<T> reconciler)`
    - `start()` - builds `KubernetesClient`, starts all registered `ResourceEventSource`s, starts worker threads that poll queue and call reconciler
    - `stop()` / `close()` - stops informers and worker threads gracefully
  - Worker thread pool (configurable, default 1 thread per controller)
  - Handle SIGTERM gracefully (Runtime.getRuntime().addShutdownHook)
  - Support namespace-scoped or cluster-scoped informers; default to **namespace-scoped** (watch the namespace where the operator is deployed) for safer minimal permissions

  **Must NOT do**:
  - Do NOT implement leader election here (T6)
  - Do NOT implement metrics/health here (T8/T9)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T14, T20
  - **Blocked By**: T1, T3

  **References**:
  - `https://github.com/fabric8io/kubernetes-client/blob/main/doc/KubernetesOperatorsInJavaWrittenUsingFabric8.md`

  **Acceptance Criteria**:
  - [ ] `Operator.java` exists
  - [ ] Unit test: `Operator` starts/stops without exception with mock client
  - [ ] Unit test: registered reconciler receives expected request

  **QA Scenarios**:

  ```
  Scenario: Operator launcher starts and stops cleanly
    Tool: Bash
    Preconditions: T3 completed
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=OperatorLauncherTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-4-launcher-test.log
  ```

  **Evidence to Capture**:
  - [ ] task-4-launcher-test.log

  **Commit**: YES (Wave 1)

- [x] 5. SDK - OwnerReference helper + finalizer utilities

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.util.OwnerReferenceHelper`:
    - `createControllerOwnerReference(HasMetadata owner)` returns `OwnerReference` with `controller=true`, `blockOwnerDeletion=true`
  - Create `com.huawei.dcs.modelengine.operator.framework.util.FinalizerHelper`:
    - `addFinalizer(HasMetadata resource, String finalizer)` if not present
    - `removeFinalizer(HasMetadata resource, String finalizer)`
    - `hasFinalizer(HasMetadata resource, String finalizer)`
  - Utility methods should be pure functions (no K8s API calls)

  **Must NOT do**:
  - Do NOT add K8s API mutation logic here (just helpers)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T13, T20
  - **Blocked By**: T1

  **References**:
  - `https://github.com/fabric8io/kubernetes-client/discussions/3717` - owner reference patterns
  - Kubernetes docs on garbage collection and finalizers

  **Acceptance Criteria**:
  - [ ] `OwnerReferenceHelper.java` and `FinalizerHelper.java` exist
  - [ ] Unit tests verify owner reference fields and finalizer add/remove logic

  **QA Scenarios**:

  ```
  Scenario: OwnerReference and finalizer helpers work correctly
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=OwnerReferenceHelperTest,FinalizerHelperTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-5-helper-tests.log
  ```

  **Evidence to Capture**:
  - [ ] task-5-helper-tests.log

  **Commit**: YES (Wave 1)

- [x] 6. SDK - Leader election manager

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.leader.LeaderElectionManager`:
    - Wraps fabric8 `LeaderElector`
    - Config: lock name, namespace, lease duration (default 15s), renew deadline (default 10s), retry period (default 2s)
    - Supports `LeaseLock` (default) and `ConfigMapLock` modes
    - Provides callbacks: `onStartLeading`, `onStopLeading`
    - `run(Runnable leaderRunnable)` - acquires leadership then runs the provided runnable; exits runnable when leadership lost
  - Use `client.leaderElector().withConfig(...).build()`

  **Must NOT do**:
  - Do NOT hardcode lock identity; use UUID + pod name if available, fallback UUID

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: T14, T20
  - **Blocked By**: T1

  **References**:
  - `https://github.com/fabric8io/kubernetes-client/blob/main/kubernetes-examples/src/main/java/io/fabric8/kubernetes/examples/LeaderElectionExamples.java`
  - `https://github.com/fabric8io/kubernetes-client/discussions/4789`

  **Acceptance Criteria**:
  - [ ] `LeaderElectionManager.java` exists
  - [ ] Unit test with mock `KubernetesClient` verifies leader config builder is called with expected durations
  - [ ] No `System.exit` inside leader callbacks

  **QA Scenarios**:

  ```
  Scenario: Leader election manager builds valid config
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=LeaderElectionManagerTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-6-leader-test.log
  ```

  **Evidence to Capture**:
  - [ ] task-6-leader-test.log

  **Commit**: YES (Wave 2)

- [x] 7. SDK - Retry / rate limiter

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.retry.RetryPolicy` interface
  - Create `com.huawei.dcs.modelengine.operator.framework.retry.ExponentialBackoffRetryPolicy`:
    - Config: initial interval (default 500ms), max interval (default 30s), max attempts (default 5)
    - Method `Duration nextDelay(int attempt)`
  - Create `com.huawei.dcs.modelengine.operator.framework.retry.RateLimiter`:
    - Per-resource key rate limiting using token bucket or simple last-execution timestamp
    - Method `boolean canProcess(String key)` and `void record(String key)`
  - Integrate retry into worker loop: if reconciler returns `Result.error != null`, requeue with computed delay up to max attempts

  **Must NOT do**:
  - Do NOT silently swallow errors; errors must be observable in metrics/status
  - Do NOT retry forever without max attempts

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: T12, T13, T20
  - **Blocked By**: T1

  **References**:
  - Java Operator SDK retry concepts (for design reference only; do not import)

  **Acceptance Criteria**:
  - [ ] `RetryPolicy`, `ExponentialBackoffRetryPolicy`, `RateLimiter` exist
  - [ ] Unit tests verify backoff values and max attempts
  - [ ] Unit test verifies rate limiter per-key behavior

  **QA Scenarios**:

  ```
  Scenario: Exponential backoff computes correct delays
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=ExponentialBackoffRetryPolicyTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-7-retry-test.log

  Scenario: Rate limiter throttles per-resource key
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=RateLimiterTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-7-rate-limiter-test.log
  ```

  **Evidence to Capture**:
  - [ ] task-7-retry-test.log
  - [ ] task-7-rate-limiter-test.log

  **Commit**: YES (Wave 2)

- [x] 8. SDK - Metrics server (Micrometer + Prometheus)

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.metrics.MetricsServer`:
    - Shares a single JDK `HttpServer` with `HealthServer` on configurable port (default 8080)
    - Exposes `/metrics` endpoint returning Prometheus format
    - Uses `PrometheusMeterRegistry`
  - Register default metrics:
    - `operator_reconcile_total` (counter, tags: controller, result)
    - `operator_reconcile_errors_total` (counter, tags: controller)
    - `operator_reconcile_duration_seconds` (timer, tags: controller)
  - Provide API for custom reconciler metrics via `MeterRegistry`

  **Must NOT do**:
  - Do NOT use Micrometer bindings that require Spring/Quarkus
  - Do NOT expose metrics on same path as health if it causes conflict

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: T14, T20
  - **Blocked By**: T1

  **References**:
  - Micrometer PrometheusMeterRegistry docs

  **Acceptance Criteria**:
  - [ ] `MetricsServer.java` exists
  - [ ] Unit test starts server on random port, calls `/metrics`, asserts body contains `operator_reconcile_total`
  - [ ] Server stops cleanly

  **QA Scenarios**:

  ```
  Scenario: Metrics endpoint returns Prometheus format
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=MetricsServerTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-8-metrics-test.log
  ```

  **Evidence to Capture**:
  - [ ] task-8-metrics-test.log

  **Commit**: YES (Wave 2)

- [x] 9. SDK - Health server (/healthz /readyz)

  **What to do**:
  - Create `com.huawei.dcs.modelengine.operator.framework.health.HealthServer`:
    - Shares a single JDK `HttpServer` with `MetricsServer` on configurable port (default 8080)
    - Endpoints: `/healthz` (liveness, HTTP 200), `/readyz` (readiness, HTTP 200 when informers synced, else 503)
    - Support registering custom readiness checks
  - Single shared server exposes `/metrics` (Prometheus), `/healthz`, `/readyz` on port 8080 by default; both `MetricsServer` and `HealthServer` register handlers on the same `HttpServer` instance

  **Must NOT do**:
  - Do NOT use a heavy servlet container
  - Do NOT require external web framework

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: T14, T20
  - **Blocked By**: T1, T8 (share server if combined)

  **References**:
  - K8s probe semantics: liveness vs readiness

  **Acceptance Criteria**:
  - [ ] `HealthServer.java` exists
  - [ ] Unit test: `/healthz` returns 200, `/readyz` returns 200 when ready, 503 when not ready
  - [ ] Server stops cleanly

  **QA Scenarios**:

  ```
  Scenario: Health endpoints return correct status codes
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test -Dtest=HealthServerTest`
    Expected Result: Tests pass
    Evidence: .sisyphus/evidence/task-9-health-test.log
  ```

  **Evidence to Capture**:
  - [ ] task-9-health-test.log

  **Commit**: YES (Wave 2)

- [x] 10. SDK - Unit tests for SDK APIs

  **What to do**:
  - Write comprehensive unit tests for all SDK APIs created in Wave 1-2
  - Use Mockito to mock `KubernetesClient`, `SharedIndexInformer`, etc.
  - Ensure `mvn -f operator/framework/pom.xml test` passes with good coverage
  - Aim for >70% line coverage on SDK classes

  **Must NOT do**:
  - Do NOT add integration tests requiring real cluster

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: F1-F4
  - **Blocked By**: T1, T3, T4, T5, T6, T7, T8, T9

  **References**:
  - Existing SDK test patterns

  **Acceptance Criteria**:
  - [ ] All SDK tests pass
  - [ ] Coverage report generated (if jacoco plugin added)

  **QA Scenarios**:

  ```
  Scenario: Full SDK test suite passes
    Tool: Bash
    Steps:
      1. Run `mvn -f operator/framework/pom.xml test`
    Expected Result: BUILD SUCCESS, all tests pass
    Evidence: .sisyphus/evidence/task-10-sdk-tests.log
  ```

  **Evidence to Capture**:
  - [ ] task-10-sdk-tests.log

  **Commit**: YES (Wave 2)

- [x] 11. Echo Operator - CRD Java classes (Spec/Status/Resource)

  **What to do**:
  - In `example/echo-operator/src/main/java/com/example/echooperator/api/v1/` create:
    - `EchoResource extends CustomResource<EchoSpec, EchoStatus>`
    - `EchoSpec` with fields:
      - `String message` (required)
      - `int replicas` (default 1)
    - `EchoStatus` with fields:
      - `String phase` (PENDING / READY / FAILED)
      - `String message`
  - Add CRD metadata annotations:
    - `@Group("example.com")`
    - `@Version("v1alpha1")`
    - `@Kind("EchoResource")`
    - `@Plural("echoresources")`
    - `@ShortNames({"echo"})`
  - Configure `crd-generator-maven-plugin` to generate CRD YAML into `target/classes/META-INF/fabric8/`
  - Also create `example/echo-operator/src/main/resources/crd/echo-crd.yaml` as the source-of-truth for CRD→Java generation demo
  - Configure `java-generator-maven-plugin` to generate Java classes from `src/main/resources/crd/` (into `target/generated-sources/java`) to demonstrate bidirectional generation

  **Must NOT do**:
  - Do NOT use JOSDK annotations
  - Do NOT commit generated sources to git (add to .gitignore)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T12, T13, T17
  - **Blocked By**: T2

  **References**:
  - `operator/framework/src/main/java/.../reconciler/Reconciler.java` - resource type parameter
  - Fabric8 CRD generator annotation docs

  **Acceptance Criteria**:
  - [ ] `EchoResource.java`, `EchoSpec.java`, `EchoStatus.java` exist
  - [ ] `mvn -f example/echo-operator/pom.xml compile` generates CRD YAML at `target/classes/META-INF/fabric8/echoresources.example.com-v1.yml`
  - [ ] `mvn -f example/echo-operator/pom.xml compile` generates Java POJOs from `src/main/resources/crd/echo-crd.yaml` (if plugin configured)

  **QA Scenarios**:

  ```
  Scenario: CRD YAML generated from Java classes
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml clean compile`
      2. Check `ls example/echo-operator/target/classes/META-INF/fabric8/`
    Expected Result: File `echoresources.example.com-v1.yml` exists and contains kind EchoResource
    Evidence: .sisyphus/evidence/task-11-crd-generated.yml

  Scenario: Java classes generated from CRD YAML
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml clean compile`
      2. Check `ls example/echo-operator/target/generated-sources/java/`
    Expected Result: Generated Java package/classes exist (e.g., com.example.echooperator.api.v1.EchoResource)
    Evidence: .sisyphus/evidence/task-11-java-generated.log
  ```

  **Evidence to Capture**:
  - [ ] task-11-crd-generated.yml
  - [ ] task-11-java-generated.log

  **Commit**: YES (Wave 3)

- [x] 12. Echo Operator - Reconciler + Deployment creation

  **What to do**:
  - Create `com.example.echooperator.controller.EchoReconciler implements Reconciler<EchoResource>`
  - On reconcile:
    - Validate `spec.replicas >= 0`; if invalid set `status.phase=FAILED`, `status.message="replicas must be >= 0"`
    - Build desired `Deployment` using fabric8 `DeploymentBuilder`:
      - name = echo resource name
      - namespace = echo resource namespace
      - replicas = spec.replicas (default 1 if 0 treated as 1? Validate: if 0, create 0 replicas)
      - labels: `app=echo`, `managed-by=echo-operator`
      - pod template with nginx container or a simple echo server container (use `nginx:alpine` for simplicity)
    - Build desired `Service` exposing port 80
    - Use `client.apps().deployments().resource(desired).createOrReplace()`
    - Use `client.services().resource(desiredService).createOrReplace()`
    - Set `OwnerReference` on created resources (via T5 helper)
    - Update `status.phase=READY` and `status.message=spec.message`
  - Integrate with SDK retry policy (T7)

  **Must NOT do**:
  - Do NOT implement finalizer here (T13)
  - Do NOT handle status update errors silently

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T13, T17, T20
  - **Blocked By**: T2, T7, T11

  **References**:
  - `operator/framework/src/main/java/.../util/OwnerReferenceHelper.java`
  - Fabric8 DeploymentBuilder examples

  **Acceptance Criteria**:
  - [ ] `EchoReconciler.java` exists
  - [ ] Unit test with mock client verifies Deployment and Service are created with correct fields
  - [ ] Unit test verifies invalid replicas results in FAILED status

  **QA Scenarios**:

  ```
  Scenario: Reconciler creates Deployment and Service
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoReconcilerTest#testCreateDeploymentAndService`
    Expected Result: Test passes
    Evidence: .sisyphus/evidence/task-12-reconciler-create.log

  Scenario: Invalid replicas sets FAILED status
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoReconcilerTest#testInvalidReplicas`
    Expected Result: Test passes, status.phase == FAILED
    Evidence: .sisyphus/evidence/task-12-invalid-replicas.log
  ```

  **Evidence to Capture**:
  - [ ] task-12-reconciler-create.log
  - [ ] task-12-invalid-replicas.log

  **Commit**: YES (Wave 3)

- [x] 13. Echo Operator - Finalizer + OwnerReference + Status update

  **What to do**:
  - Add finalizer constant `echo.example.com/finalizer`
  - In `EchoReconciler`:
    - On add/update: if finalizer not present, add it via `client.resource(echo).edit(...)` and requeue
    - On delete (when deletionTimestamp non-null): perform cleanup (e.g., log cleanup), remove finalizer
    - Use T5 helper for finalizer add/remove
  - Ensure OwnerReference is set on Deployment/Service so K8s garbage collection works
  - Status update:
    - Use `client.resources(EchoResource.class).resource(echo).updateStatus()`
    - Set `status.phase` and `status.message` after reconcile
  - Error retry: if status update fails, return Result with error so retry policy handles it

  **Must NOT do**:
  - Do NOT create infinite requeue loops
  - Do NOT forget to remove finalizer after cleanup

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T17, T20
  - **Blocked By**: T2, T5, T7, T11, T12

  **References**:
  - `operator/framework/src/main/java/.../util/FinalizerHelper.java`
  - Fabric8 `updateStatus()` docs

  **Acceptance Criteria**:
  - [ ] Finalizer added/removed correctly in unit tests
  - [ ] OwnerReference present on created resources
  - [ ] Status updated with correct phase/message
  - [ ] Retry behavior tested with mock client simulating transient failures

  **QA Scenarios**:

  ```
  Scenario: Finalizer added and removed on deletion
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoReconcilerTest#testFinalizer`
    Expected Result: Test passes
    Evidence: .sisyphus/evidence/task-13-finalizer.log

  Scenario: Status update after reconcile
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoReconcilerTest#testStatusUpdate`
    Expected Result: Test passes
    Evidence: .sisyphus/evidence/task-13-status-update.log
  ```

  **Evidence to Capture**:
  - [ ] task-13-finalizer.log
  - [ ] task-13-status-update.log

  **Commit**: YES (Wave 3)

- [x] 14. Echo Operator - Main class + local-run script

  **What to do**:
  - Create `com.example.echooperator.EchoOperatorMain` with `main(String[] args)`:
    - Build `KubernetesClient`
    - Create `Operator`
    - Optionally wrap with `LeaderElectionManager` if config enabled
    - Register `EchoReconciler` for `EchoResource.class`
    - Start metrics/health server
    - Start operator
    - Add shutdown hook
  - Read config from environment variables or `application.properties`:
    - `OPERATOR_NAMESPACE` (default to the namespace where the operator pod runs; the informer watches this namespace)
    - `METRICS_PORT` (default 8080)
    - `LEADER_ELECTION_ENABLED` (default false)
    - `LEADER_ELECTION_NAMESPACE` (default operator namespace)
  - Create `example/echo-operator/scripts/local-run.sh`:
    - Exports required env vars
    - Runs `mvn -f example/echo-operator/pom.xml exec:java -Dexec.mainClass=com.example.echooperator.EchoOperatorMain`

  **Must NOT do**:
  - Do NOT hardcode kubeconfig path
  - Do NOT require cluster admin by default

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T15, T18, T20
  - **Blocked By**: T2, T4, T6, T8, T9

  **References**:
  - `operator/framework/src/main/java/.../Operator.java`
  - `operator/framework/src/main/java/.../leader/LeaderElectionManager.java`

  **Acceptance Criteria**:
  - [ ] `EchoOperatorMain.java` exists
  - [ ] `local-run.sh` exists and is executable
  - [ ] Unit test for main class wiring (if feasible with mocks)

  **QA Scenarios**:

  ```
  Scenario: Main class can be instantiated and started with mocks
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test -Dtest=EchoOperatorMainTest`
    Expected Result: Test passes
    Evidence: .sisyphus/evidence/task-14-main-test.log
  ```

  **Evidence to Capture**:
  - [ ] task-14-main-test.log

  **Commit**: YES (Wave 3)

- [x] 15. Echo Operator - Dockerfile + image build script

  **What to do**:
  - Create `example/echo-operator/Dockerfile`:
    - Multi-stage build or single stage using `eclipse-temurin:21-jre-alpine`
    - Copy jar from `target/echo-operator-0.1.0-SNAPSHOT.jar`
    - Entrypoint: `java -jar /app/echo-operator.jar`
    - Expose port 8080
  - Create `example/echo-operator/scripts/build-image.sh`:
    - Builds project with `mvn -f example/echo-operator/pom.xml clean package -DskipTests`
    - Runs `docker build -t example/echo-operator:latest example/echo-operator`
  - Add `.dockerignore`

  **Must NOT do**:
  - Do NOT use Jib (user chose Dockerfile)
  - Do NOT include target/ in image build context unnecessarily

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T18, T20
  - **Blocked By**: T2, T14

  **References**:
  - Standard Java Dockerfile patterns

  **Acceptance Criteria**:
  - [ ] `Dockerfile` exists
  - [ ] `build-image.sh` exists and is executable
  - [ ] `docker build -t example/echo-operator:latest example/echo-operator` succeeds

  **QA Scenarios**:

  ```
  Scenario: Docker image builds successfully
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml clean package -DskipTests`
      2. Run `docker build -t example/echo-operator:latest example/echo-operator`
    Expected Result: Image built successfully, `docker images | grep example/echo-operator` shows entry
    Evidence: .sisyphus/evidence/task-15-docker-build.log
  ```

  **Evidence to Capture**:
  - [ ] task-15-docker-build.log

  **Commit**: YES (Wave 3)

- [x] 16. Echo Operator - Helm chart

  **What to do**:
  - Create `example/echo-operator/helm/echo-operator/` with:
    - `Chart.yaml`: name `echo-operator`, version `0.1.0`, appVersion `0.1.0`
    - `values.yaml`: image repository `example/echo-operator`, tag `latest`, replicas `1`, resources, serviceAccount, rbac, metrics port
    - `templates/crd.yaml`: include generated CRD from `target/classes/META-INF/fabric8/echoresources.example.com-v1.yml` via `tpl` or copy at build time; alternatively use `crds/` directory for Helm 3 CRD handling
    - `templates/deployment.yaml`: operator Deployment with probes, env vars (inject `OPERATOR_NAMESPACE` via downward API so operator watches its own namespace by default)
    - `templates/serviceaccount.yaml`
    - `templates/role.yaml` + `templates/rolebinding.yaml` (namespace-scoped; aligns with default namespace-scoped informer)
    - `templates/service.yaml` for metrics port (optional)
  - Create `example/echo-operator/scripts/package-helm.sh` to copy CRD and package chart
  - Create `example/echo-operator/scripts/deploy.sh` and `undeploy.sh`

  **Must NOT do**:
  - Do NOT use ClusterRole unless user asks (use Role for namespace-scoped)
  - Do NOT include unnecessary Helm subcharts

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T18, T20
  - **Blocked By**: T2

  **References**:
  - Helm 3 CRD handling best practices
  - K8s Deployment probe patterns

  **Acceptance Criteria**:
  - [ ] Helm chart files exist
  - [ ] `helm template echo-operator example/echo-operator/helm/echo-operator` renders valid YAML
  - [ ] `helm lint example/echo-operator/helm/echo-operator` passes
  - [ ] CRD is included in chart

  **QA Scenarios**:

  ```
  Scenario: Helm chart renders valid YAML
    Tool: Bash
    Steps:
      1. Run `helm lint example/echo-operator/helm/echo-operator`
      2. Run `helm template echo-operator example/echo-operator/helm/echo-operator > /tmp/echo-rendered.yaml`
    Expected Result: helm lint exits 0, rendered YAML contains Deployment, ServiceAccount, Role, RoleBinding, CRD
    Evidence: .sisyphus/evidence/task-16-helm-render.yaml
  ```

  **Evidence to Capture**:
  - [ ] task-16-helm-render.yaml

  **Commit**: YES (Wave 3)

- [x] 17. Echo Operator - Unit tests

  **What to do**:
  - Write unit tests for `EchoReconciler`, `EchoOperatorMain`, and CRD generation
  - Use Mockito for K8s client mocking
  - Ensure `mvn -f example/echo-operator/pom.xml test` passes

  **Must NOT do**:
  - Do NOT add integration tests with real cluster

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: F1-F4
  - **Blocked By**: T2, T11, T12, T13

  **References**:
  - Existing example classes

  **Acceptance Criteria**:
  - [ ] Example unit tests pass

  **QA Scenarios**:

  ```
  Scenario: Example test suite passes
    Tool: Bash
    Steps:
      1. Run `mvn -f example/echo-operator/pom.xml test`
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-17-example-tests.log
  ```

  **Evidence to Capture**:
  - [ ] task-17-example-tests.log

  **Commit**: YES (Wave 3)

- [x] 18. Shell scripts - build/deploy/undeploy/local-run

  **What to do**:
  - Create `example/echo-operator/scripts/build.sh`: compile + package
  - Create `example/echo-operator/scripts/deploy.sh`: build docker image, load to kind (if kind is current context), install helm chart
  - Create `example/echo-operator/scripts/undeploy.sh`: helm uninstall
  - Create `example/echo-operator/scripts/local-run.sh`: run operator locally against current kubeconfig
  - Make all scripts executable (`chmod +x`)
  - Scripts should fail fast (`set -euo pipefail`)
  - Use relative paths from script location

  **Must NOT do**:
  - Do NOT hardcode cluster credentials
  - Do NOT auto-push to remote registry unless explicitly configured

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: T20
  - **Blocked By**: T14, T15, T16

  **References**:
  - Helm CLI and kubectl CLI usage

  **Acceptance Criteria**:
  - [ ] All shell scripts exist and are executable
  - [ ] `shellcheck` passes (if available)
  - [ ] `build.sh` runs successfully

  **QA Scenarios**:

  ```
  Scenario: Build script works
    Tool: Bash
    Steps:
      1. Run `example/echo-operator/scripts/build.sh`
    Expected Result: Builds SDK (if needed), example jar created at `example/echo-operator/target/echo-operator-0.1.0-SNAPSHOT.jar`
    Evidence: .sisyphus/evidence/task-18-build-script.log
  ```

  **Evidence to Capture**:
  - [ ] task-18-build-script.log

  **Commit**: YES (Wave 4)

- [x] 19. README + dev-guide (Chinese + English)

  **What to do**:
  - `operator/framework/README.md` + `README.zh-CN.md`:
    - Project overview
    - Maven coordinates
    - Core API introduction (Operator, Reconciler, Informer, Leader election, Metrics/Health, Retry)
    - Build instructions
  - `example/echo-operator/README.md` + `README.zh-CN.md`:
    - What Echo Operator does
    - How to build and run locally
    - How to build Docker image
    - How to deploy with Helm
    - Example CR
  - `docs/dev-guide.md` + `docs/dev-guide.zh-CN.md`:
    - How to create a new operator using this SDK
    - How to define CRD Java classes
    - How to write a Reconciler
    - How to configure leader election
    - How to add metrics/health
    - How to generate CRD YAML
    - How to generate Java from CRD YAML

  **Must NOT do**:
  - Do NOT write documentation-only deliverables as substitutes for tests

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: F1-F4
  - **Blocked By**: T18

  **References**:
  - All implemented classes

  **Acceptance Criteria**:
  - [ ] 6 markdown docs exist
  - [ ] Docs mention exact commands from QA scenarios

  **QA Scenarios**:

  ```
  Scenario: Documentation files exist
    Tool: Bash
    Steps:
      1. Run `ls operator/framework/README.md operator/framework/README.zh-CN.md example/echo-operator/README.md example/echo-operator/README.zh-CN.md docs/dev-guide.md docs/dev-guide.zh-CN.md`
    Expected Result: All files exist
    Evidence: .sisyphus/evidence/task-19-docs-exist.log
  ```

  **Evidence to Capture**:
  - [ ] task-19-docs-exist.log

  **Commit**: YES (Wave 4)

- [x] 20. End-to-end local smoke test

  **What to do**:
  - Create a smoke test script `example/echo-operator/scripts/smoke-test.sh` that:
    - Builds image
    - Starts operator locally OR deploys to local kind cluster (if available)
    - Applies sample Echo CR
    - Waits and asserts Deployment/Service created
    - Deletes CR and asserts cleanup
    - Captures curl output for /healthz /readyz /metrics
  - Create `example/echo-operator/examples/echo-cr.yaml` sample
  - This is a smoke test, not a unit test; it can be skipped in CI

  **Must NOT do**:
  - Do NOT fail the build if no cluster is available; script should detect and skip gracefully

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4 (final integration)
  - **Blocks**: F1-F4
  - **Blocked By**: T4, T6, T7, T8, T9, T13, T14, T15, T16, T18

  **References**:
  - All previous tasks

  **Acceptance Criteria**:
  - [ ] `smoke-test.sh` exists and is executable
  - [ ] `examples/echo-cr.yaml` exists
  - [ ] If cluster available, script completes without error

  **QA Scenarios**:

  ```
  Scenario: Smoke test script exists and is valid
    Tool: Bash
    Steps:
      1. Run `bash -n example/echo-operator/scripts/smoke-test.sh`
      2. Run `ls example/echo-operator/examples/echo-cr.yaml`
    Expected Result: Syntax check passes, sample CR exists
    Evidence: .sisyphus/evidence/task-20-smoke-test.log

  Scenario: Operator endpoints respond when running locally (optional, if cluster available)
    Tool: Bash
    Preconditions: smoke-test.sh started operator locally or in kind
    Steps:
      1. Run `curl -s http://localhost:8080/healthz`
      2. Run `curl -s http://localhost:8080/readyz`
      3. Run `curl -s http://localhost:8080/metrics | head -n 5`
    Expected Result: healthz/readyz return 200, metrics returns Prometheus format
    Evidence: .sisyphus/evidence/task-20-endpoints.log
  ```

  **Evidence to Capture**:
  - [ ] task-20-smoke-test.log
  - [ ] task-20-endpoints.log

  **Commit**: YES (Wave 4)

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

- [x] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists (read file, curl endpoint, run command). For each "Must NOT Have": search codebase for forbidden patterns — reject with file:line if found. Check evidence files exist in `.sisyphus/evidence/`. Compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [x] F2. **Code Quality Review** — `unspecified-high`
  Run `mvn clean install` for SDK and example. Review all changed files for: raw `e.printStackTrace()`, empty catches, `System.exit` in wrong places, commented-out code, unused imports. Check AI slop: excessive comments, over-abstraction, generic names (data/result/item/temp).
  Output: `Build [PASS/FAIL] | Files [N clean/N issues] | VERDICT`

- [x] F3. **Real Manual QA** — `unspecified-high`
  Start from clean state. Execute EVERY QA scenario from EVERY task — follow exact steps, capture evidence. Test cross-task integration (SDK + example working together). Test edge cases: empty spec, invalid replicas, rapid CR updates. Save to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Integration [N/N] | Edge Cases [N tested] | VERDICT`

- [x] F4. **Scope Fidelity Check** — `deep`
  For each task: read "What to do", read actual diff (git log/diff). Verify 1:1 — everything in spec was built (no missing), nothing beyond spec was built (no creep). Check "Must NOT do" compliance. Detect cross-task contamination.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- This project starts from empty workspace; no git repo detected. The executor should initialize git after first wave if needed, but commits are NOT required for every task. Group commits by wave.
- **Wave 1**: `chore(scaffold): create SDK and example Maven projects`
- **Wave 2**: `feat(sdk): implement core operator runtime (informer, retry, leader, metrics, health)`
- **Wave 3**: `feat(example): implement Echo Operator with CRD, reconciler, helm chart`
- **Wave 4**: `docs: add README, dev-guide, and deployment scripts`
- **FINAL**: `chore(release): final review and smoke test`

---

## Success Criteria

### Verification Commands
```bash
# SDK builds and installs to local repo
mvn -f operator/framework/pom.xml clean install
# Expected: BUILD SUCCESS

# Example builds against local SDK
mvn -f example/echo-operator/pom.xml clean package
# Expected: BUILD SUCCESS

# Docker image builds
docker build -t example/echo-operator:latest example/echo-operator
# Expected: image created

# Helm chart renders
helm template echo-operator example/echo-operator/helm/echo-operator
# Expected: valid YAML output

# Operator local endpoints (when running)
curl -s http://localhost:8080/healthz
# Expected: HTTP 200

curl -s http://localhost:8080/readyz
# Expected: HTTP 200

curl -s http://localhost:8080/metrics
# Expected: Prometheus exposition format
```

### Final Checklist
- [ ] SDK `mvn clean install` success
- [ ] Example `mvn clean package` success
- [ ] Docker image builds
- [ ] Helm chart renders
- [ ] Operator endpoints respond
- [ ] Echo CR reconciliation creates Deployment + Service
- [ ] Deleting Echo CR triggers finalizer and garbage collects Deployment
- [ ] All "Must Have" present
- [ ] All "Must NOT Have" absent
- [ ] All tests pass
- [ ] All documentation present (Chinese + English)
- [ ] Evidence files saved to `.sisyphus/evidence/`
