# Learnings - java-k8s-operator-scaffold

## T17: Echo Operator Unit Tests

- Existing `EchoReconcilerTest` already covered creation, invalid replicas, finalizer lifecycle, status update, and retry-on-failure.
- Added edge-case tests:
  - `testZeroReplicas`: verifies that `spec.replicas=0` produces a Deployment with `replicas=0` (no implicit default).
  - `testMetricsRecorded`: verifies the reconciler increments `echo_reconcile_total` when a `MeterRegistry` is supplied.
- Created `CrdGenerationTest` to assert build-time CRD/Java generation without integration tests:
  - `crdYamlGeneratedFromJavaClasses`: checks `target/classes/META-INF/fabric8/echoresources.example.com-v1.yml` exists and contains kind `EchoResource`, group `example.com`, version `v1alpha1`, plural `echoresources`, and shortNames.
  - `javaClassesGeneratedFromCrdYaml`: checks `target/generated-sources/java/com/example/v1alpha1/EchoResource{,Spec,Status}.java` exist and declare public classes.
  - `sourceCrdYamlExists`: checks the source-of-truth CRD at `src/main/resources/crd/echo-crd.yaml` exists.
- Full example test suite passes: `mvn -f example/echo-operator/pom.xml test` → 12 tests, BUILD SUCCESS.
- Evidence captured at `.sisyphus/evidence/task-17-example-tests.log`.
- Notes:
  - `Path.of("").toAbsolutePath()` is the project base directory during Maven Surefire execution, making file-based generation assertions portable.
  - The Java generator emits classes into package `com.example.v1alpha1` (not `com.example.echooperator.api.v1`), so tests must reference the generated path accordingly.
  - Mockito dynamic agent warning appears but does not fail tests on the current JDK.

## T18: Echo Operator Scripts

- Added `example/echo-operator/scripts/build.sh` to run the full SDK + example build chain from the script directory:
  - `mvn -f operator/framework/pom.xml clean install -DskipTests`
  - `mvn -f example/echo-operator/pom.xml clean package -DskipTests`
- Updated `deploy.sh` to stay self-contained by calling `build.sh` and `build-image.sh` before Helm deployment, then loading the image into kind when the current context starts with `kind-`.
- `undeploy.sh` now fails fast on `helm uninstall` instead of swallowing errors.
- All scripts under `example/echo-operator/scripts/` were marked executable and verified with `bash -n`.
- Evidence captured:
  - `.sisyphus/evidence/task-18-build-script.log`
  - `.sisyphus/evidence/task-18-build-image.log`
  - `.sisyphus/evidence/task-18-scripts-executable.log`
- Build verification succeeded and produced `example/echo-operator/target/echo-operator-0.1.0-SNAPSHOT.jar`.
T19 docs: wrote 6 markdown files (EN/ZH) for SDK README, Echo README, and dev guide. Referenced actual script names: local-run.sh, build-image.sh, deploy.sh, undeploy.sh. Referenced QA commands: mvn -f operator/framework/pom.xml clean install/test, mvn -f example/echo-operator/pom.xml clean compile/package/exec:java, docker build, helm template/lint, curl endpoints.

## T20: End-to-end smoke test

- Created `example/echo-operator/examples/echo-cr.yaml` sample CR with apiVersion `example.com/v1alpha1`, kind `EchoResource`, name `echo-sample`, message "Hello from Echo Operator", replicas 1.
- Created `example/echo-operator/scripts/smoke-test.sh` that:
  - Builds the Docker image via `build-image.sh`.
  - Detects a Kubernetes cluster with `kubectl cluster-info`.
  - Skips cluster checks gracefully when no cluster is available.
  - When cluster is available, deploys via Helm, port-forwards the metrics service, applies CRD and CR, waits for Deployment/Service, captures `/healthz`, `/readyz`, `/metrics`, deletes CR, verifies cleanup, and undeploys.
  - Uses `set -euo pipefail` and relative paths from the script location.
  - Saves evidence to `.sisyphus/evidence/task-20-smoke-test.log` and endpoints to `task-20-endpoints.log`.

### Pre-existing issues discovered and fixed during smoke-test integration

1. **Dockerfile `USER echo` incompatible with Helm `runAsNonRoot: true`**
   - Symptom: Pod failed with `CreateContainerConfigError`: container has runAsNonRoot and image has non-numeric user.
   - Fix: Use explicit numeric UID/GID (`USER 1001:1001`) and create user/group with fixed IDs in the Dockerfile.

2. **Example `pom.xml` produced a thin jar, breaking `java -jar`**
   - Symptom: Pod crashed with `no main manifest attribute, in /app/echo-operator.jar`.
   - Fix: Added `maven-shade-plugin` to build an executable uber jar with `EchoOperatorMain` as the main class and `ServicesResourceTransformer` for SPI files.

### Verification

- `bash -n example/echo-operator/scripts/smoke-test.sh` passes.
- Full smoke test executed successfully on Docker Desktop Kubernetes cluster:
  - Operator Deployment rolled out.
  - `/healthz` and `/readyz` returned HTTP 200.
  - `/metrics` returned Prometheus exposition format including `echo_reconcile_total`.
  - Applying the sample CR created `echo-sample` Deployment and Service.
  - CR status reached `READY`.
  - Deleting the CR removed Deployment and Service (finalizer + owner reference cleanup).


## F2 Code Quality Review - 2026-06-18

- Maven builds for SDK and example both pass with all tests green.
- Codebase is largely free of explicit anti-patterns (printStackTrace, empty catches, System.exit, TODO/FIXME, commented-out code, unused imports).
- Minor quality gaps remain: deprecated fabric8 API usage, small helper duplication, indentation inconsistency, unused Lombok processor config.
- These are fixable in a follow-up polish pass and do not block functional verification.

## F3 Real Manual QA - 2026-06-18

- Executed every QA scenario from T1-T20 from a clean state, capturing evidence to `.sisyphus/evidence/final-qa/`.
- Added a temporary edge-case test class `EdgeCaseTest` in `example/echo-operator/src/test/java/com/example/echooperator/controller/EdgeCaseTest.java` covering empty spec, rapid reconcile idempotency, and zero replicas.
- Cross-task integration verified: SDK `mvn clean install`, example `mvn clean package`, Docker image build, and Helm template render in one chained command.
- Smoke test ran against the Docker Desktop Kubernetes cluster; `/healthz`, `/readyz`, and `/metrics` responded correctly, and the sample CR lifecycle worked end-to-end.
- Result: all 26 T1-T20 QA scenarios, 1 integration scenario, and 3 edge-case tests passed.
- Final verdict: APPROVE.
- Evidence summary: `.sisyphus/evidence/final-qa/final-qa-summary.txt`.

## Final Verification F1/F4 Fixes - 2026-06-18

- T6 fixed in `LeaderElectionManager`: default lock mode remains `LeaseLock`, and `withLockMode(LockMode.CONFIG_MAP)` now builds a fabric8 `ConfigMapLock` using the same namespace, lock name, and identity.
- `LeaderElectionManagerTest` now verifies default `LeaseLock` behavior and explicit `ConfigMapLock` config construction.
- T7 fixed in `Operator`: `withMetricsServer(MetricsServer)` wires reconcile metrics into the worker loop. Each actual reconcile records `operator_reconcile_total` with result tag (`success`, `requeue`, `error`), `operator_reconcile_duration_seconds`, and error reconciles also record `operator_reconcile_errors_total`.
- `OperatorLauncherTest` verifies retry failure metrics and subsequent success metrics while preserving the internal retry error count assertion.
- T10 evidence generated at `.sisyphus/evidence/task-10-sdk-tests.log`; full SDK test suite passed with 23 tests and BUILD SUCCESS.
- Eclipse metadata cleanup removed the requested `.project`, `.classpath`, `.factorypath`, and `.settings` paths from SDK/example modules.

## F1 Plan Compliance Audit Re-run - 2026-06-18
- APPROVE: Must Have 7/7, Must NOT Have 9/9, Tasks 20/20.
- Rechecked previous failures: LeaderElectionManager supports LeaseLock and ConfigMapLock; task-10-sdk-tests.log exists; SDK package uses com.huawei.dcs.modelengine; no Eclipse metadata found.
- Verification passed: operator/framework mvn test, example/echo-operator mvn test, helm lint.

## F2 Code Quality Review Re-run - 2026-06-20

- SDK `mvn -f operator/framework/pom.xml clean install`: BUILD SUCCESS (23 tests).
- Example `mvn -f example/echo-operator/pom.xml clean package`: BUILD SUCCESS (15 tests).
- Reviewed 33 Java source files under `operator/framework/src/` and `example/echo-operator/src/`.
- No explicit anti-patterns found: no `e.printStackTrace()`, no empty catch blocks without explanation, no `System.exit()`, no commented-out code, no unused imports, no TODO/FIXME/XXX/HACK markers, no AI-slop generic naming.
- Two intentional exception swallows in `Operator.java` (lines 290, 332) contain explanatory comments for shutdown paths.
- Non-blocking observations logged to `.sisyphus/evidence/f2-code-quality.log`.
- Final F2 verdict: APPROVE.

## F3 Real Manual QA Re-run - 

- Re-executed the full final QA run script (.sisyphus/evidence/final-qa/run-final-qa.sh) from a clean state.
- All 31 tracked checks passed: 26 T1-T20 QA scenarios, 1 cross-task integration chain, 1 EdgeCaseTest (3 methods), plus full SDK/example clean-test re-runs.
- Docker Desktop engine was initially stopped; after starting the application the daemon became available and the image build succeeded.
- A Kubernetes cluster was available, so smoke-test.sh deployed the operator via Helm, verified /healthz, /readyz, /metrics, applied the sample CR, and confirmed cleanup.
- Endpoint evidence captured: /healthz OK (HTTP 200), /readyz OK (HTTP 200), /metrics returned Prometheus format with echo_reconcile_total.
- Verdict: APPROVE.
