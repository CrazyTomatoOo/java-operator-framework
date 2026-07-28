# F2 Code and Chart Quality Review

## Verdict

**FAIL** — all executable gates passed, but lifecycle review found blocking resource-ownership defects. F2 cannot pass while the required `lifecycle nullability/idempotence` criterion is not satisfied.

## Executed gates

| Gate | Result | Evidence |
|---|---|---|
| Framework full Maven tests | PASS — 166 run, 0 failures, 0 errors, 0 skipped | `framework-maven-test.log` |
| Example full Maven tests | PASS — 68 run, 0 failures, 0 errors, 0 skipped | `example-maven-test.log` |
| Helm lint | PASS — 1 chart linted, 0 failed | `helm-lint.log` |
| All render-contract cases | PASS — combined, split, selector, values isolation, RBAC, ownership, TLS, and negative cases | `helm-contract-test.log` |
| Misleading skip-success scan | PASS — required terminal markers present; no `NOT RUN` or successful skip marker | `no-misleading-skip-scan.log` |

Commands:

```text
mvn -f operator/framework/pom.xml clean test
mvn -f example/echo-operator/pom.xml clean test
helm lint example/echo-operator/helm/echo-operator
example/echo-operator/scripts/helm-contract-test.sh
```

## Blocking lifecycle findings

### 1. Controller pre-start/partial-start shutdown can leak the shared Kubernetes client

- `EchoOperatorMain.create()` constructs `new Operator(client)` before startup (`EchoOperatorMain.java:142-149`).
- `Operator(KubernetesClient)` only captures a supplier (`Operator.java:70-75`); its `client` field remains null until `Operator.start()` invokes the supplier (`Operator.java:143-149`).
- `Operator.stop()` returns immediately when it has not started and `client == null` (`Operator.java:163-166`).
- `EchoOperatorMain.stop()` directly closes the shared client only when `operator == null` (`EchoOperatorMain.java:407-425`).

Therefore a controller-enabled instance stopped before `Operator.start()`—including a metrics or webhook startup failure—has a non-null `operator`, but neither owner closes the shared client. The existing pre-start/failure tests mask this production behavior by stubbing `Operator.stop()` to call `client.close()` (`EchoOperatorMainWiringTest.java:749-756`).

### 2. Composition failure before `create()` returns leaks already-bound servers

- `MetricsHealthServer` binds its `HttpServer` in the constructor (`MetricsHealthServer.java:32-36`).
- It is created before certificate resolution and webhook construction (`EchoOperatorMain.java:140-185`), both of which may throw.
- If `EchoOperatorMain.create()` throws, `main()` observes `operatorMain == null` and closes only the Kubernetes client (`EchoOperatorMain.java:120-132`).

The already-bound metrics server is not closed. If failure occurs after `WebhookServer` construction, that bound server can also be left unclosed. Construction needs rollback of every resource acquired before the aggregate lifecycle object is returned.

### 3. `WebhookServer` pre-start and failed-start cleanup is not idempotent/resource-safe

- Construction binds the `HttpsServer` and creates its executor (`WebhookServer.java:58-76`).
- `stop()` returns whenever `running` is false (`WebhookServer.java:103-107`), so stop-before-start does not release the bound server/executor.
- `start()` starts `CertWatcher` before `server.start()` and sets `running=true` only afterward (`WebhookServer.java:90-100`). If `server.start()` throws, subsequent `stop()` returns without stopping the watcher.

This also makes a stop-before-start path and a partial-start rollback unsafe.

## Passed static review areas

### Immutable selector preservation — PASS

- Combined Deployment and Service selectors still use the baseline two-label selector (`templates/deployment.yaml:12-25`, `templates/service.yaml:23-24`).
- Split selectors add the reserved component label and are reused verbatim by Pod templates and Services (`templates/_helpers.tpl:100-108`, `templates/deployment.yaml:167-192,282-295`).
- The contract compares the current combined selector and Service identity against the immutable archived baseline and tests mutually exclusive split selectors.

### Values duplication/boundaries — PASS

- `values.yaml` explicitly documents top-level values as combined-only and nested `controller.workload` / `webhook.workload` values as split-only.
- Split templates read only nested workload values; combined templates read only top-level workload values.
- Poison-value contract cases prove no cross-mode inheritance or conflict.

### Helper readability — PASS

- Helper names state their responsibility (`deploymentMode`, `watchedNamespace`, `componentName`, `componentSelectorLabels`, `componentServiceAccountName`, `validate`).
- Shared component logic is localized and wrapper helpers keep call sites readable.
- Validation diagnostics are specific and covered by negative render cases.

### Least privilege — PASS against the plan's exact matrix

- Controller, webhook Secret, lease, admission ownership/barrier/cleanup, and conversion rules match the exact matrix in plan lines 68-86.
- Split bindings target distinct ServiceAccounts; watched-namespace bindings preserve the release namespace on subjects.
- Exact normalized rule-set assertions and explicit cross-capability denials pass for all rendered scenarios.

### Shell cleanup/error handling — PASS

- Contract and deployment scripts use `set -euo pipefail`; Helm contract execution is time-bounded and removes its temporary directory through an EXIT trap.
- Smoke verification installs one cleanup trap, preserves original status, records cleanup failure, kills and waits for every retained port-forward PID, and propagates PID state in the parent shell.
- Missing/unreachable clusters fail instead of reporting a skip; expected-failure helpers fail if the command unexpectedly succeeds.

## Required follow-up

Fix the three lifecycle findings and add production-faithful tests that do not mock ownership behavior away. Re-run all five evidence gates before marking F2 passed.
